package me.golemcore.bot.domain.hive;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.hive.HiveCapabilitySnapshot;
import me.golemcore.bot.domain.model.hive.HiveControlChannelStatusSnapshot;
import me.golemcore.bot.domain.model.hive.HiveOutboxSummary;
import me.golemcore.bot.domain.model.hive.HivePolicyApplyResult;
import me.golemcore.bot.domain.model.hive.HivePolicyPackage;
import me.golemcore.bot.domain.model.hive.HiveStatusSnapshot;
import me.golemcore.bot.domain.model.policy.ManagedPolicyBindingState;
import me.golemcore.bot.port.outbound.ChannelDeliveryPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import me.golemcore.bot.port.outbound.HiveBootstrapSettingsPort;
import me.golemcore.bot.port.outbound.HiveBootstrapSettingsPort.HiveBootstrapSettings;
import me.golemcore.bot.port.outbound.HiveConnectionPort;
import me.golemcore.bot.port.outbound.HiveControlChannelPort;
import me.golemcore.bot.port.outbound.HiveEventOutboxPort;
import me.golemcore.bot.port.outbound.HiveMachinePort;
import me.golemcore.bot.port.outbound.HiveRuntimeMetadataPort;
import me.golemcore.bot.port.outbound.RuntimeConfigAdminPort;
import me.golemcore.bot.port.outbound.RuntimeConfigQueryPort;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class HiveConnectionService implements HiveConnectionPort {

    private static final Duration ACCESS_TOKEN_REFRESH_WINDOW = Duration.ofMinutes(1);
    private static final int DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final int CONTROL_CHANNEL_RECONNECT_INTERVAL_SECONDS = 5;
    private static final String POLICY_READ_SCOPE = "golems:policy:read";
    private static final String POLICY_WRITE_SCOPE = "golems:policy:write";
    private static final String CONTROL_CHANNEL_NAME = "control";
    private static final String POLICY_SYNC_FEATURE = "policy-sync-v1";
    private static final String UNKNOWN_POLICY_BINDING_MESSAGE = "Unknown policy binding for golem:";
    private static final String RECONNECT_AUTH_FAILURE_MESSAGE = "Hive session refresh token is invalid or expired. Use Join with a new Hive join code.";

    private final HiveBootstrapSettingsPort hiveBootstrapSettingsPort;
    private final RuntimeConfigQueryPort runtimeConfigQueryPort;
    private final RuntimeConfigAdminPort runtimeConfigAdminPort;
    private final HiveBootstrapConfigSynchronizer hiveBootstrapConfigSynchronizer;
    private final HiveSessionStateStore hiveSessionStateStore;
    private final HiveControlInboxService hiveControlInboxService;
    private final HiveControlCommandDispatcher hiveControlCommandDispatcher;
    private final HiveManagedPolicyService hiveManagedPolicyService;
    private final HiveMachinePort hiveMachinePort;
    private final HiveEventOutboxPort hiveEventOutboxPort;
    private final HiveControlChannelPort hiveControlChannelPort;
    private final HiveRuntimeMetadataPort hiveRuntimeMetadataPort;
    private final ChannelRuntimePort channelRuntimePort;
    private final Clock clock;

    private final Object lock = new Object();
    private final AtomicReference<HiveConnectionState> stateRef = new AtomicReference<>(
            HiveConnectionState.DISCONNECTED);
    private final AtomicReference<String> lastErrorRef = new AtomicReference<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "hive-connection");
        thread.setDaemon(true);
        return thread;
    });

    private ScheduledFuture<?> heartbeatFuture;
    private ScheduledFuture<?> controlChannelFuture;

    public HiveConnectionService(
            HiveBootstrapSettingsPort hiveBootstrapSettingsPort,
            RuntimeConfigQueryPort runtimeConfigQueryPort,
            RuntimeConfigAdminPort runtimeConfigAdminPort,
            HiveBootstrapConfigSynchronizer hiveBootstrapConfigSynchronizer,
            HiveSessionStateStore hiveSessionStateStore,
            HiveControlInboxService hiveControlInboxService,
            HiveControlCommandDispatcher hiveControlCommandDispatcher,
            HiveManagedPolicyService hiveManagedPolicyService,
            HiveMachinePort hiveMachinePort,
            HiveEventOutboxPort hiveEventOutboxPort,
            HiveControlChannelPort hiveControlChannelPort,
            HiveRuntimeMetadataPort hiveRuntimeMetadataPort,
            ChannelRuntimePort channelRuntimePort,
            Clock clock) {
        this.hiveBootstrapSettingsPort = hiveBootstrapSettingsPort;
        this.runtimeConfigQueryPort = runtimeConfigQueryPort;
        this.runtimeConfigAdminPort = runtimeConfigAdminPort;
        this.hiveBootstrapConfigSynchronizer = hiveBootstrapConfigSynchronizer;
        this.hiveSessionStateStore = hiveSessionStateStore;
        this.hiveControlInboxService = hiveControlInboxService;
        this.hiveControlCommandDispatcher = hiveControlCommandDispatcher;
        this.hiveManagedPolicyService = hiveManagedPolicyService;
        this.hiveMachinePort = hiveMachinePort;
        this.hiveEventOutboxPort = hiveEventOutboxPort;
        this.hiveControlChannelPort = hiveControlChannelPort;
        this.hiveRuntimeMetadataPort = hiveRuntimeMetadataPort;
        this.channelRuntimePort = channelRuntimePort;
        this.clock = clock;
    }

    @PostConstruct
    void init() {
        int resetCount = hiveControlInboxService.resetInFlightCommandsForRestart();
        if (resetCount > 0) {
            log.info("[Hive] Reset {} in-flight control command(s) after restart", resetCount);
        }
        Optional<HiveSessionState> sessionState = hiveSessionStateStore.load();
        if (sessionState.isPresent()) {
            stateRef.set(HiveConnectionState.DEGRADED);
            lastErrorRef.set(sessionState.get().getLastError());
        }
        if (shouldAutoConnect()) {
            executor.execute(this::attemptStartupConnect);
        }
    }

    @PreDestroy
    void destroy() {
        synchronized (lock) {
            cancelBackgroundTasks();
            hiveControlChannelPort.disconnect("shutdown");
        }
        executor.shutdownNow();
    }

    public HiveStatusSnapshot getStatus() {
        RuntimeConfig.HiveConfig hiveConfig = HiveRuntimeConfigSupport
                .getHiveConfig(runtimeConfigQueryPort.getRuntimeConfig());
        HiveSessionState sessionState = hiveSessionStateStore.load().orElse(null);
        HiveControlChannelStatusSnapshot controlChannelStatus = hiveControlChannelPort.getStatus();
        HiveControlInboxService.InboxSummary inboxSummary = hiveControlInboxService.getSummary();
        HiveOutboxSummary outboxSummary = hiveEventOutboxPort.getSummary();
        ManagedPolicyBindingState policyState = hiveManagedPolicyService.getBindingState().orElse(null);
        String lastError = lastErrorRef.get();
        if (lastError == null && sessionState != null) {
            lastError = sessionState.getLastError();
        }
        return new HiveStatusSnapshot(
                stateRef.get().name(),
                Boolean.TRUE.equals(hiveConfig.getEnabled()),
                Boolean.TRUE.equals(hiveConfig.getManagedByProperties()),
                hiveBootstrapConfigSynchronizer.getManagedJoinCode() != null,
                Boolean.TRUE.equals(hiveConfig.getAutoConnect()),
                hiveConfig.getServerUrl(),
                hiveConfig.getDisplayName(),
                hiveConfig.getHostLabel(),
                resolveDashboardBaseUrl(),
                Boolean.TRUE.equals(hiveConfig.getSsoEnabled()),
                sessionState != null,
                sessionState != null ? sessionState.getGolemId() : null,
                sessionState != null ? sessionState.getControlChannelUrl() : null,
                sessionState != null ? sessionState.getHeartbeatIntervalSeconds() : null,
                sessionState != null ? sessionState.getLastConnectedAt() : null,
                sessionState != null ? sessionState.getLastHeartbeatAt() : null,
                sessionState != null ? sessionState.getLastTokenRotatedAt() : null,
                controlChannelStatus.state(),
                controlChannelStatus.connectedAt(),
                controlChannelStatus.lastMessageAt(),
                controlChannelStatus.lastError(),
                inboxSummary.lastReceivedCommandId(),
                inboxSummary.lastReceivedAt(),
                inboxSummary.receivedCommandCount(),
                inboxSummary.bufferedCommandCount(),
                inboxSummary.pendingCommandCount(),
                outboxSummary.pendingBatchCount(),
                outboxSummary.pendingEventCount(),
                outboxSummary.lastError(),
                lastError,
                policyState != null ? policyState.getPolicyGroupId() : null,
                policyState != null ? policyState.getTargetVersion() : null,
                policyState != null ? policyState.getAppliedVersion() : null,
                policyState != null ? policyState.getSyncStatus() : null,
                policyState != null ? policyState.getLastErrorDigest() : null);
    }

    private String resolveDashboardBaseUrl() {
        return HiveDashboardUrlSupport.normalizeDashboardBaseUrl(
                HiveRuntimeConfigSupport.getHiveConfig(runtimeConfigQueryPort.getRuntimeConfig())
                        .getDashboardBaseUrl());
    }

    public HiveStatusSnapshot join(String requestedJoinCode) {
        synchronized (lock) {
            boolean explicitJoinCode = requestedJoinCode != null && !requestedJoinCode.isBlank();
            String joinCode = resolveJoinCodeForAction(requestedJoinCode);
            HiveJoinCodeParser.ParsedJoinCode parsedJoinCode = HiveJoinCodeParser.parse(joinCode);
            Optional<HiveSessionState> existingSession = hiveSessionStateStore.load();
            if (existingSession.isPresent()) {
                if (!explicitJoinCode) {
                    if (!sameServer(existingSession.get().getServerUrl(), parsedJoinCode.serverUrl())) {
                        throw new IllegalStateException(
                                "A Hive session already exists. Leave the current session before joining a different Hive server.");
                    }
                    return reconnect();
                }
                clearLocalHiveSession();
            }
            stateRef.set(HiveConnectionState.JOINING);
            lastErrorRef.set(null);
            stopRuntimeTransport();
            RuntimeConfig.HiveConfig hiveConfig = HiveRuntimeConfigSupport
                    .getHiveConfig(runtimeConfigQueryPort.getRuntimeConfig());
            try {
                HiveMachinePort.AuthSession response = hiveMachinePort.register(
                        parsedJoinCode.serverUrl(),
                        parsedJoinCode.enrollmentToken(),
                        resolveDisplayName(hiveConfig),
                        resolveHostLabel(hiveConfig),
                        hiveRuntimeMetadataPort.runtimeVersion(),
                        resolveBuildVersion(),
                        resolveSupportedChannels(),
                        buildCapabilitySnapshot());
                HiveSessionState sessionState = buildSessionState(parsedJoinCode.serverUrl(), response);
                sessionState.setLastConnectedAt(Instant.now(clock));
                sessionState.setLastError(null);
                hiveSessionStateStore.save(sessionState);
                persistManualJoinServerUrl(parsedJoinCode.serverUrl());
                synchronizeManagedPolicy(sessionState, false);
                if (sessionState.getControlChannelUrl() != null && !sessionState.getControlChannelUrl().isBlank()) {
                    hiveControlChannelPort.connect(sessionState, this::recordControlCommand);
                }
                sendHeartbeat(sessionState, "Hive join completed");
                scheduleBackgroundTasks(sessionState);
                updateStateFromRuntimeHealth();
                return getStatus();
            } catch (RuntimeException exception) {
                handleFailure(exception);
                throw toHiveActionException("Hive join failed", exception);
            }
        }
    }

    public HiveStatusSnapshot reconnect() {
        synchronized (lock) {
            Optional<HiveSessionState> sessionStateOptional = hiveSessionStateStore.load();
            if (sessionStateOptional.isEmpty()) {
                if (hiveBootstrapConfigSynchronizer.getManagedJoinCode() != null) {
                    return join(null);
                }
                throw new IllegalStateException("No persisted Hive session is available for reconnect");
            }
            stateRef.set(HiveConnectionState.JOINING);
            lastErrorRef.set(null);
            stopRuntimeTransport();
            HiveSessionState sessionState = sessionStateOptional.get();
            try {
                rotateSessionTokens(sessionState);
                activateConnectedSession(sessionState, "Hive reconnect completed");
                return getStatus();
            } catch (RuntimeException exception) {
                if (isAuthorizationFailure(exception)) {
                    handleReconnectAuthorizationFailure(exception);
                    throw new IllegalStateException(RECONNECT_AUTH_FAILURE_MESSAGE, exception);
                }
                handleFailure(exception);
                throw toHiveActionException("Hive reconnect failed", exception);
            }
        }
    }

    public HiveStatusSnapshot leave() {
        synchronized (lock) {
            clearLocalHiveSession();
            stateRef.set(HiveConnectionState.DISCONNECTED);
            lastErrorRef.set(null);
            return getStatus();
        }
    }

    public HiveStatusSnapshot heartbeat() {
        synchronized (lock) {
            HiveSessionState sessionState = hiveSessionStateStore.load()
                    .orElseThrow(() -> new IllegalStateException("Hive session is not connected"));
            try {
                refreshSessionTokensIfNeeded(sessionState);
                synchronizeManagedPolicy(sessionState, true);
                flushPendingEventBatches(sessionState);
                ensureControlChannelConnected(sessionState);
                sendHeartbeat(sessionState, buildHealthSummary());
                sessionState.setLastError(null);
                hiveSessionStateStore.save(sessionState);
                updateStateFromRuntimeHealth();
                return getStatus();
            } catch (RuntimeException exception) {
                handleBackgroundFailure(sessionState, exception);
                if (exception instanceof IllegalStateException illegalStateException) {
                    throw illegalStateException;
                }
                throw new IllegalStateException("Hive heartbeat failed", exception);
            }
        }
    }

    public HiveStatusSnapshot syncPolicy() {
        synchronized (lock) {
            HiveSessionState sessionState = hiveSessionStateStore.load()
                    .orElseThrow(() -> new IllegalStateException("Hive session is not connected"));
            synchronizeManagedPolicy(sessionState, false);
            return getStatus();
        }
    }

    public HiveStatusSnapshot drainControlInbox() {
        synchronized (lock) {
            drainPendingControlCommands();
            return getStatus();
        }
    }

    public HiveStatusSnapshot flushOutbox() {
        synchronized (lock) {
            HiveSessionState sessionState = hiveSessionStateStore.load()
                    .orElseThrow(() -> new IllegalStateException("Hive session is not connected"));
            flushPendingEventBatches(sessionState);
            return getStatus();
        }
    }

    public HiveStatusSnapshot clearOutbox() {
        synchronized (lock) {
            hiveEventOutboxPort.clear();
            return getStatus();
        }
    }

    private void attemptStartupConnect() {
        try {
            Optional<HiveSessionState> sessionState = hiveSessionStateStore.load();
            if (sessionState.isPresent()) {
                reconnect();
                return;
            }
            if (hiveBootstrapConfigSynchronizer.getManagedJoinCode() != null) {
                join(null);
            }
        } catch (RuntimeException exception) {
            log.warn("[Hive] Startup auto-connect failed: {}", exception.getMessage());
        }
    }

    private boolean shouldAutoConnect() {
        RuntimeConfig.HiveConfig hiveConfig = HiveRuntimeConfigSupport
                .getHiveConfig(runtimeConfigQueryPort.getRuntimeConfig());
        return Boolean.TRUE.equals(hiveConfig.getEnabled()) && Boolean.TRUE.equals(hiveConfig.getAutoConnect());
    }

    private String resolveJoinCodeForAction(String requestedJoinCode) {
        if (isHiveManagedByProperties()) {
            if (requestedJoinCode != null && !requestedJoinCode.isBlank()) {
                throw new IllegalStateException("Hive join code is managed by bot.hive.* and cannot be overridden");
            }
            String managedJoinCode = hiveBootstrapConfigSynchronizer.getManagedJoinCode();
            if (managedJoinCode == null) {
                throw new IllegalStateException("Hive join is managed by bot.hive.* but bot.hive.joinCode is missing");
            }
            return managedJoinCode;
        }
        if (requestedJoinCode == null || requestedJoinCode.isBlank()) {
            throw new IllegalArgumentException("Hive joinCode is required");
        }
        return requestedJoinCode.trim();
    }

    private boolean sameServer(String currentServerUrl, String newServerUrl) {
        return HiveJoinCodeParser.normalizeServerUrl(currentServerUrl)
                .equals(HiveJoinCodeParser.normalizeServerUrl(newServerUrl));
    }

    private String resolveDisplayName(RuntimeConfig.HiveConfig hiveConfig) {
        if (hiveConfig.getDisplayName() != null && !hiveConfig.getDisplayName().isBlank()) {
            return hiveConfig.getDisplayName().trim();
        }
        String configuredDisplayName = bootstrapSettings().displayName();
        if (configuredDisplayName != null && !configuredDisplayName.isBlank()) {
            return configuredDisplayName.trim();
        }
        return "GolemCore Bot";
    }

    private String resolveHostLabel(RuntimeConfig.HiveConfig hiveConfig) {
        if (hiveConfig.getHostLabel() != null && !hiveConfig.getHostLabel().isBlank()) {
            return hiveConfig.getHostLabel().trim();
        }
        String configuredHostLabel = bootstrapSettings().hostLabel();
        if (configuredHostLabel != null && !configuredHostLabel.isBlank()) {
            return configuredHostLabel.trim();
        }
        return hiveRuntimeMetadataPort.defaultHostLabel();
    }

    private String resolveBuildVersion() {
        return hiveRuntimeMetadataPort.buildVersion();
    }

    private HiveBootstrapSettings bootstrapSettings() {
        HiveBootstrapSettings settings = hiveBootstrapSettingsPort.hiveBootstrapSettings();
        return settings != null ? settings : HiveBootstrapSettings.empty();
    }

    private Set<String> resolveSupportedChannels() {
        Set<String> supportedChannels = new LinkedHashSet<>();
        for (ChannelDeliveryPort port : channelRuntimePort.listChannels()) {
            supportedChannels.add(port.getChannelType());
        }
        return supportedChannels;
    }

    private Set<String> resolveCapabilitySupportedChannels() {
        Set<String> supportedChannels = resolveSupportedChannels();
        supportedChannels.add(CONTROL_CHANNEL_NAME);
        return supportedChannels;
    }

    private boolean isHiveManagedByProperties() {
        RuntimeConfig.HiveConfig hiveConfig = HiveRuntimeConfigSupport
                .getHiveConfig(runtimeConfigQueryPort.getRuntimeConfig());
        return hiveConfig != null && Boolean.TRUE.equals(hiveConfig.getManagedByProperties());
    }

    private Set<String> resolveConfiguredLlmProviders() {
        RuntimeConfig runtimeConfig = runtimeConfigAdminPort.getRuntimeConfig();
        if (runtimeConfig == null
                || runtimeConfig.getLlm() == null
                || runtimeConfig.getLlm().getProviders() == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(runtimeConfig.getLlm().getProviders().keySet());
    }

    private HiveCapabilitySnapshot buildCapabilitySnapshot() {
        Set<String> providers = resolveConfiguredLlmProviders();
        HiveCapabilitySnapshot snapshot = HiveCapabilitySnapshot.builder()
                .providers(providers)
                .enabledAutonomyFeatures(Set.of(POLICY_SYNC_FEATURE))
                .supportedChannels(resolveCapabilitySupportedChannels())
                .defaultModel(resolveDefaultCapabilityModel())
                .build();
        snapshot.setSnapshotHash(computeCapabilitySnapshotHash(snapshot));
        return snapshot;
    }

    private HiveSessionState buildSessionState(String serverUrl, HiveMachinePort.AuthSession response) {
        return HiveSessionState.builder()
                .golemId(response.golemId())
                .serverUrl(serverUrl)
                .controlChannelUrl(response.controlChannelUrl())
                .issuer(response.issuer())
                .audience(response.audience())
                .accessToken(response.accessToken())
                .refreshToken(response.refreshToken())
                .accessTokenExpiresAt(response.accessTokenExpiresAt())
                .refreshTokenExpiresAt(response.refreshTokenExpiresAt())
                .heartbeatIntervalSeconds(response.heartbeatIntervalSeconds())
                .scopes(response.scopes())
                .registeredAt(Instant.now(clock))
                .build();
    }

    private void refreshSessionTokensIfNeeded(HiveSessionState sessionState) {
        Instant accessTokenExpiresAt = sessionState.getAccessTokenExpiresAt();
        if (accessTokenExpiresAt == null
                || accessTokenExpiresAt.isAfter(Instant.now(clock).plus(ACCESS_TOKEN_REFRESH_WINDOW))) {
            return;
        }
        rotateSessionTokens(sessionState);
        hiveControlChannelPort.disconnect("token-refresh");
    }

    private void rotateSessionTokens(HiveSessionState sessionState) {
        HiveMachinePort.AuthSession response = hiveMachinePort.rotate(
                sessionState.getServerUrl(),
                sessionState.getGolemId(),
                sessionState.getRefreshToken());
        applyAuthResponse(sessionState, response);
        sessionState.setLastTokenRotatedAt(Instant.now(clock));
    }

    private void applyAuthResponse(HiveSessionState sessionState, HiveMachinePort.AuthSession response) {
        sessionState.setGolemId(response.golemId());
        sessionState.setControlChannelUrl(response.controlChannelUrl());
        sessionState.setIssuer(response.issuer());
        sessionState.setAudience(response.audience());
        sessionState.setAccessToken(response.accessToken());
        sessionState.setRefreshToken(response.refreshToken());
        sessionState.setAccessTokenExpiresAt(response.accessTokenExpiresAt());
        sessionState.setRefreshTokenExpiresAt(response.refreshTokenExpiresAt());
        sessionState.setHeartbeatIntervalSeconds(response.heartbeatIntervalSeconds());
        sessionState.setScopes(response.scopes());
    }

    private void sendHeartbeat(HiveSessionState sessionState, String healthSummary) {
        HiveControlChannelStatusSnapshot controlChannelStatus = hiveControlChannelPort.getStatus();
        ManagedPolicyBindingState policyState = hasScope(sessionState, POLICY_WRITE_SCOPE)
                ? hiveManagedPolicyService.getBindingState().orElse(null)
                : null;
        hiveMachinePort.heartbeat(
                sessionState.getServerUrl(),
                sessionState.getGolemId(),
                sessionState.getAccessToken(),
                "CONNECTED".equals(controlChannelStatus.state()) ? "connected" : "degraded",
                healthSummary,
                controlChannelStatus.lastError(),
                hiveRuntimeMetadataPort.uptimeSeconds(),
                buildCapabilitySnapshot().getSnapshotHash(),
                policyState != null ? policyState.getPolicyGroupId() : null,
                policyState != null ? policyState.getTargetVersion() : null,
                policyState != null ? policyState.getAppliedVersion() : null,
                policyState != null ? policyState.getSyncStatus() : null,
                policyState != null ? policyState.getLastErrorDigest() : null,
                resolveDashboardBaseUrl());
        sessionState.setLastHeartbeatAt(Instant.now(clock));
    }

    private String buildHealthSummary() {
        HiveControlChannelStatusSnapshot controlChannelStatus = hiveControlChannelPort.getStatus();
        return "CONNECTED".equals(controlChannelStatus.state())
                ? "Control channel connected"
                : "Waiting for control channel reconnect";
    }

    private void ensureControlChannelConnected(HiveSessionState sessionState) {
        if (sessionState.getControlChannelUrl() == null || sessionState.getControlChannelUrl().isBlank()) {
            return;
        }
        HiveControlChannelStatusSnapshot controlChannelStatus = hiveControlChannelPort.getStatus();
        if ("CONNECTED".equals(controlChannelStatus.state()) || "CONNECTING".equals(controlChannelStatus.state())) {
            return;
        }
        hiveControlChannelPort.connect(sessionState, this::recordControlCommand);
    }

    private void flushPendingEventBatches(HiveSessionState sessionState) {
        hiveEventOutboxPort.flushPending(sessionState);
    }

    private void recordControlCommand(HiveControlCommandEnvelope envelope) {
        HiveControlInboxService.RecordResult result = hiveControlInboxService.recordReceived(envelope);
        String trackingId = hiveControlInboxService.resolveTrackingId(envelope);
        drainPendingControlCommands();
        log.info("[Hive] Received control command: trackingId={}, threadId={}, buffered={}, duplicate={}",
                trackingId,
                envelope.getThreadId(),
                result.summary().bufferedCommandCount(),
                result.duplicate());
    }

    private void synchronizeManagedPolicy(HiveSessionState sessionState, boolean onlyIfPending) {
        if (sessionState == null || !hasScope(sessionState, POLICY_READ_SCOPE)) {
            return;
        }
        if (onlyIfPending && !hiveManagedPolicyService.isSyncPending()) {
            return;
        }
        try {
            HivePolicyPackage policyPackage = hiveMachinePort.getPolicyPackage(
                    sessionState.getServerUrl(),
                    sessionState.getGolemId(),
                    sessionState.getAccessToken());
            HivePolicyApplyResult applyResult = hiveManagedPolicyService.applyPolicyPackage(policyPackage);
            if (hasScope(sessionState, POLICY_WRITE_SCOPE)) {
                hiveMachinePort.reportPolicyApplyResult(
                        sessionState.getServerUrl(),
                        sessionState.getGolemId(),
                        sessionState.getAccessToken(),
                        applyResult);
            }
        } catch (HiveMachinePort.HiveMachineException exception) {
            if (isMissingPolicyBinding(exception)) {
                hiveManagedPolicyService.clearBinding();
                return;
            }
            throw exception;
        }
    }

    private boolean isMissingPolicyBinding(HiveMachinePort.HiveMachineException exception) {
        if (exception == null) {
            return false;
        }
        int statusCode = exception.getStatusCode();
        if (statusCode == 404) {
            return true;
        }
        return (statusCode == 400 || statusCode == 409)
                && exception.getMessage() != null
                && exception.getMessage().contains(UNKNOWN_POLICY_BINDING_MESSAGE);
    }

    private void drainPendingControlCommands() {
        int replayedCount = hiveControlInboxService.drainPending(hiveControlCommandDispatcher::dispatch);
        if (replayedCount > 0) {
            log.info("[Hive] Replayed {} pending control command(s) from inbox", replayedCount);
        }
    }

    private void scheduleBackgroundTasks(HiveSessionState sessionState) {
        cancelBackgroundTasks();
        int heartbeatIntervalSeconds = sessionState.getHeartbeatIntervalSeconds() != null
                ? Math.max(sessionState.getHeartbeatIntervalSeconds(), 5)
                : DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
        heartbeatFuture = executor.scheduleWithFixedDelay(
                this::safeRunHeartbeatMaintenanceCycle,
                heartbeatIntervalSeconds,
                heartbeatIntervalSeconds,
                TimeUnit.SECONDS);
        controlChannelFuture = executor.scheduleWithFixedDelay(
                this::safeRunControlChannelMaintenanceCycle,
                1,
                CONTROL_CHANNEL_RECONNECT_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    private void safeRunHeartbeatMaintenanceCycle() {
        try {
            runHeartbeatMaintenanceCycle();
        } catch (RuntimeException exception) { // NOSONAR - scheduler loop must stay alive
            log.warn("[Hive] Heartbeat cycle failed: {}", exception.getMessage());
        }
    }

    private void safeRunControlChannelMaintenanceCycle() {
        try {
            runControlChannelMaintenanceCycle();
        } catch (RuntimeException exception) { // NOSONAR - scheduler loop must stay alive
            log.warn("[Hive] Control channel cycle failed: {}", exception.getMessage());
        }
    }

    private void persistManualJoinServerUrl(String serverUrl) {
        if (isHiveManagedByProperties()) {
            return;
        }
        RuntimeConfig runtimeConfig = runtimeConfigAdminPort.getRuntimeConfig();
        RuntimeConfig.HiveConfig hiveConfig = runtimeConfig.getHive();
        if (hiveConfig == null) {
            hiveConfig = RuntimeConfig.HiveConfig.builder().build();
            runtimeConfig.setHive(hiveConfig);
        }
        boolean changed = !Boolean.TRUE.equals(hiveConfig.getEnabled())
                || !serverUrl.equals(hiveConfig.getServerUrl());
        if (!changed) {
            return;
        }
        hiveConfig.setEnabled(true);
        hiveConfig.setServerUrl(serverUrl);
        runtimeConfigAdminPort.updateRuntimeConfig(runtimeConfig);
    }

    private void updateStateFromRuntimeHealth() {
        if (stateRef.get() == HiveConnectionState.REVOKED) {
            return;
        }
        Optional<HiveSessionState> sessionStateOptional = hiveSessionStateStore.load();
        if (sessionStateOptional.isEmpty()) {
            stateRef.set(HiveConnectionState.DISCONNECTED);
            return;
        }
        HiveSessionState sessionState = sessionStateOptional.get();
        HiveControlChannelStatusSnapshot controlChannelStatus = hiveControlChannelPort.getStatus();
        if (sessionState.getControlChannelUrl() == null || sessionState.getControlChannelUrl().isBlank()) {
            stateRef.set(HiveConnectionState.CONNECTED);
            return;
        }
        if ("CONNECTED".equals(controlChannelStatus.state())) {
            stateRef.set(HiveConnectionState.CONNECTED);
            return;
        }
        stateRef.set(HiveConnectionState.DEGRADED);
    }

    private void stopRuntimeTransport() {
        cancelBackgroundTasks();
        hiveControlChannelPort.disconnect("stop");
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void cancelBackgroundTasks() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
            heartbeatFuture = null;
        }
        if (controlChannelFuture != null) {
            controlChannelFuture.cancel(true);
            controlChannelFuture = null;
        }
    }

    private void handleBackgroundFailure(HiveSessionState sessionState, RuntimeException exception) {
        sessionState.setLastError(exception.getMessage());
        hiveSessionStateStore.save(sessionState);
        handleFailure(exception);
    }

    private void handleFailure(RuntimeException exception) {
        Optional<HiveSessionState> sessionStateOptional = hiveSessionStateStore.load();
        if (sessionStateOptional.isPresent()) {
            HiveSessionState sessionState = sessionStateOptional.get();
            sessionState.setLastError(exception.getMessage());
            hiveSessionStateStore.save(sessionState);
        }
        lastErrorRef.set(exception.getMessage());
        if (isAuthorizationFailure(exception)) {
            stateRef.set(HiveConnectionState.REVOKED);
            stopRuntimeTransport();
            return;
        }
        stateRef.set(HiveConnectionState.ERROR);
    }

    private void handleReconnectAuthorizationFailure(RuntimeException exception) {
        clearLocalHiveSession();
        lastErrorRef.set(RECONNECT_AUTH_FAILURE_MESSAGE);
        stateRef.set(HiveConnectionState.DISCONNECTED);
        log.warn("[Hive] Cleared stale Hive session after reconnect authorization failure: {}",
                exception.getMessage());
    }

    private IllegalStateException toHiveActionException(String message, RuntimeException exception) {
        if (exception instanceof IllegalStateException illegalStateException) {
            return illegalStateException;
        }
        return new IllegalStateException(message, exception);
    }

    private void clearLocalHiveSession() {
        stopRuntimeTransport();
        hiveSessionStateStore.clear();
        hiveControlInboxService.clear();
        hiveEventOutboxPort.clear();
        hiveManagedPolicyService.clearBinding();
    }

    private boolean isAuthorizationFailure(RuntimeException exception) {
        return exception instanceof HiveMachinePort.HiveMachineException hiveApiException
                && (hiveApiException.getStatusCode() == 401 || hiveApiException.getStatusCode() == 403);
    }

    private boolean hasScope(HiveSessionState sessionState, String scope) {
        if (sessionState == null || sessionState.getScopes() == null || scope == null || scope.isBlank()) {
            return false;
        }
        return sessionState.getScopes().stream().anyMatch(candidate -> scope.equalsIgnoreCase(candidate));
    }

    private void activateConnectedSession(HiveSessionState sessionState, String heartbeatSummary) {
        ensureControlChannelConnected(sessionState);
        synchronizeManagedPolicy(sessionState, false);
        sendHeartbeat(sessionState, heartbeatSummary);
        flushPendingEventBatches(sessionState);
        drainPendingControlCommands();
        sessionState.setLastConnectedAt(Instant.now(clock));
        sessionState.setLastError(null);
        hiveSessionStateStore.save(sessionState);
        lastErrorRef.set(null);
        updateStateFromRuntimeHealth();
        scheduleBackgroundTasks(sessionState);
    }

    private String resolveDefaultCapabilityModel() {
        RuntimeConfig runtimeConfig = runtimeConfigAdminPort.getRuntimeConfig();
        if (runtimeConfig.getModelRouter() == null || runtimeConfig.getModelRouter().getRouting() == null) {
            return null;
        }
        return runtimeConfig.getModelRouter().getRouting().getModel();
    }

    private String computeCapabilitySnapshotHash(HiveCapabilitySnapshot snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = String.join("|",
                    snapshot.getProviders().stream().sorted().toList().toString(),
                    snapshot.getEnabledAutonomyFeatures().stream().sorted().toList().toString(),
                    snapshot.getSupportedChannels().stream().sorted().toList().toString(),
                    snapshot.getDefaultModel() != null ? snapshot.getDefaultModel() : "");
            byte[] hash = digest.digest(payload.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Failed to compute Hive capability snapshot hash", exception);
        }
    }

    void runHeartbeatMaintenanceCycle() {
        synchronized (lock) {
            Optional<HiveSessionState> sessionStateOptional = hiveSessionStateStore.load();
            if (sessionStateOptional.isEmpty()) {
                return;
            }
            HiveSessionState sessionState = sessionStateOptional.get();
            try {
                refreshSessionTokensIfNeeded(sessionState);
                synchronizeManagedPolicy(sessionState, false);
                sendHeartbeat(sessionState, buildHealthSummary());
                flushPendingEventBatches(sessionState);
                drainPendingControlCommands();
                sessionState.setLastError(null);
                hiveSessionStateStore.save(sessionState);
                updateStateFromRuntimeHealth();
            } catch (RuntimeException exception) {
                handleBackgroundFailure(sessionState, exception);
            }
        }
    }

    void runControlChannelMaintenanceCycle() {
        synchronized (lock) {
            Optional<HiveSessionState> sessionStateOptional = hiveSessionStateStore.load();
            if (sessionStateOptional.isEmpty()) {
                return;
            }
            HiveSessionState sessionState = sessionStateOptional.get();
            try {
                ensureControlChannelConnected(sessionState);
                synchronizeManagedPolicy(sessionState, true);
                flushPendingEventBatches(sessionState);
                drainPendingControlCommands();
                updateStateFromRuntimeHealth();
            } catch (RuntimeException exception) {
                handleBackgroundFailure(sessionState, exception);
            }
        }
    }

    private enum HiveConnectionState {
        DISCONNECTED, JOINING, CONNECTED, DEGRADED, REVOKED, ERROR
    }
}
