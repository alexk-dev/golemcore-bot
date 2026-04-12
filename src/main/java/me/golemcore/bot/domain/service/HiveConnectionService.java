package me.golemcore.bot.domain.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
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
import me.golemcore.bot.domain.model.hive.HiveAuthSession;
import me.golemcore.bot.domain.model.hive.HiveControlChannelStatusSnapshot;
import me.golemcore.bot.domain.model.hive.HiveOutboxSummary;
import me.golemcore.bot.port.outbound.ChannelDeliveryPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import me.golemcore.bot.port.outbound.HiveBootstrapSettingsPort;
import me.golemcore.bot.port.outbound.HiveControlChannelPort;
import me.golemcore.bot.port.outbound.HiveEventOutboxPort;
import me.golemcore.bot.port.outbound.HiveGatewayPort;
import me.golemcore.bot.port.outbound.HiveRuntimeMetadataPort;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class HiveConnectionService {

    private static final Duration ACCESS_TOKEN_REFRESH_WINDOW = Duration.ofMinutes(1);
    private static final int DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final int CONTROL_CHANNEL_RECONNECT_INTERVAL_SECONDS = 5;

    private final HiveBootstrapSettingsPort hiveBootstrapSettingsPort;
    private final RuntimeConfigService runtimeConfigService;
    private final HiveBootstrapConfigSynchronizer hiveBootstrapConfigSynchronizer;
    private final HiveSessionStateStore hiveSessionStateStore;
    private final HiveControlInboxService hiveControlInboxService;
    private final HiveControlCommandDispatcher hiveControlCommandDispatcher;
    private final HiveGatewayPort hiveGatewayPort;
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
            RuntimeConfigService runtimeConfigService,
            HiveBootstrapConfigSynchronizer hiveBootstrapConfigSynchronizer,
            HiveSessionStateStore hiveSessionStateStore,
            HiveControlInboxService hiveControlInboxService,
            HiveControlCommandDispatcher hiveControlCommandDispatcher,
            HiveGatewayPort hiveGatewayPort,
            HiveEventOutboxPort hiveEventOutboxPort,
            HiveControlChannelPort hiveControlChannelPort,
            HiveRuntimeMetadataPort hiveRuntimeMetadataPort,
            ChannelRuntimePort channelRuntimePort,
            Clock clock) {
        this.hiveBootstrapSettingsPort = hiveBootstrapSettingsPort;
        this.runtimeConfigService = runtimeConfigService;
        this.hiveBootstrapConfigSynchronizer = hiveBootstrapConfigSynchronizer;
        this.hiveSessionStateStore = hiveSessionStateStore;
        this.hiveControlInboxService = hiveControlInboxService;
        this.hiveControlCommandDispatcher = hiveControlCommandDispatcher;
        this.hiveGatewayPort = hiveGatewayPort;
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
        RuntimeConfig.HiveConfig hiveConfig = runtimeConfigService.getHiveConfig();
        HiveSessionState sessionState = hiveSessionStateStore.load().orElse(null);
        HiveControlChannelStatusSnapshot controlChannelStatus = hiveControlChannelPort.getStatus();
        HiveControlInboxService.InboxSummary inboxSummary = hiveControlInboxService.getSummary();
        HiveOutboxSummary outboxSummary = hiveEventOutboxPort.getSummary();
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
                lastError);
    }

    public HiveStatusSnapshot join(String requestedJoinCode) {
        synchronized (lock) {
            String joinCode = resolveJoinCodeForAction(requestedJoinCode);
            HiveJoinCodeParser.ParsedJoinCode parsedJoinCode = HiveJoinCodeParser.parse(joinCode);
            Optional<HiveSessionState> existingSession = hiveSessionStateStore.load();
            if (existingSession.isPresent()) {
                if (!sameServer(existingSession.get().getServerUrl(), parsedJoinCode.serverUrl())) {
                    throw new IllegalStateException(
                            "A Hive session already exists. Leave the current session before joining a different Hive server.");
                }
                return reconnect();
            }
            stateRef.set(HiveConnectionState.JOINING);
            lastErrorRef.set(null);
            stopRuntimeTransport();
            RuntimeConfig.HiveConfig hiveConfig = runtimeConfigService.getHiveConfig();
            try {
                HiveAuthSession response = hiveGatewayPort.registerGolem(
                        parsedJoinCode.serverUrl(),
                        parsedJoinCode.enrollmentToken(),
                        resolveDisplayName(hiveConfig),
                        resolveHostLabel(hiveConfig),
                        resolveRuntimeVersion(),
                        resolveBuildVersion(),
                        resolveSupportedChannels());
                HiveSessionState sessionState = buildSessionState(parsedJoinCode.serverUrl(), response);
                activateConnectedSession(sessionState, "Hive join completed");
                persistManualJoinServerUrl(parsedJoinCode.serverUrl());
                return getStatus();
            } catch (RuntimeException exception) {
                handleFailure(exception);
                throw exception;
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
                handleFailure(exception);
                throw exception;
            }
        }
    }

    public HiveStatusSnapshot leave() {
        synchronized (lock) {
            stopRuntimeTransport();
            hiveSessionStateStore.clear();
            hiveControlInboxService.clear();
            hiveEventOutboxPort.clear();
            stateRef.set(HiveConnectionState.DISCONNECTED);
            lastErrorRef.set(null);
            return getStatus();
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
                flushPendingEventBatches(sessionState);
                drainPendingControlCommands();
                updateStateFromRuntimeHealth();
            } catch (RuntimeException exception) {
                handleBackgroundFailure(sessionState, exception);
            }
        }
    }

    private void activateConnectedSession(HiveSessionState sessionState, String heartbeatSummary) {
        ensureControlChannelConnected(sessionState);
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

    private boolean sameServer(String currentServerUrl, String requestedServerUrl) {
        if (currentServerUrl == null || currentServerUrl.isBlank()
                || requestedServerUrl == null || requestedServerUrl.isBlank()) {
            return false;
        }
        return HiveJoinCodeParser.normalizeServerUrl(currentServerUrl)
                .equals(HiveJoinCodeParser.normalizeServerUrl(requestedServerUrl));
    }

    private boolean shouldAutoConnect() {
        RuntimeConfig.HiveConfig hiveConfig = runtimeConfigService.getHiveConfig();
        return Boolean.TRUE.equals(hiveConfig.getEnabled()) && Boolean.TRUE.equals(hiveConfig.getAutoConnect());
    }

    private String resolveJoinCodeForAction(String requestedJoinCode) {
        if (runtimeConfigService.isHiveManagedByProperties()) {
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

    private String resolveDisplayName(RuntimeConfig.HiveConfig hiveConfig) {
        if (hiveConfig.getDisplayName() != null && !hiveConfig.getDisplayName().isBlank()) {
            return hiveConfig.getDisplayName().trim();
        }
        String configuredDisplayName = hiveBootstrapSettingsPort.displayName();
        if (configuredDisplayName != null && !configuredDisplayName.isBlank()) {
            return configuredDisplayName.trim();
        }
        return "GolemCore Bot";
    }

    private String resolveHostLabel(RuntimeConfig.HiveConfig hiveConfig) {
        if (hiveConfig.getHostLabel() != null && !hiveConfig.getHostLabel().isBlank()) {
            return hiveConfig.getHostLabel().trim();
        }
        String configuredHostLabel = hiveBootstrapSettingsPort.hostLabel();
        if (configuredHostLabel != null && !configuredHostLabel.isBlank()) {
            return configuredHostLabel.trim();
        }
        return hiveRuntimeMetadataPort.defaultHostLabel();
    }

    private String resolveRuntimeVersion() {
        return hiveRuntimeMetadataPort.runtimeVersion();
    }

    private String resolveBuildVersion() {
        return hiveRuntimeMetadataPort.buildVersion();
    }

    private Set<String> resolveSupportedChannels() {
        Set<String> supportedChannels = new LinkedHashSet<>();
        for (ChannelDeliveryPort port : channelRuntimePort.listChannels()) {
            supportedChannels.add(port.getChannelType());
        }
        return supportedChannels;
    }

    private HiveSessionState buildSessionState(String serverUrl, HiveAuthSession response) {
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
        HiveAuthSession response = hiveGatewayPort.rotateSession(
                sessionState.getServerUrl(),
                sessionState.getGolemId(),
                sessionState.getRefreshToken());
        applyAuthResponse(sessionState, response);
        sessionState.setLastTokenRotatedAt(Instant.now(clock));
    }

    private void applyAuthResponse(HiveSessionState sessionState, HiveAuthSession response) {
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
        hiveGatewayPort.sendHeartbeat(
                sessionState.getServerUrl(),
                sessionState.getGolemId(),
                sessionState.getAccessToken(),
                "CONNECTED".equals(controlChannelStatus.state()) ? "connected" : "degraded",
                healthSummary,
                controlChannelStatus.lastError(),
                hiveRuntimeMetadataPort.uptimeSeconds());
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
        if (runtimeConfigService.isHiveManagedByProperties()) {
            return;
        }
        RuntimeConfig runtimeConfig = runtimeConfigService.getRuntimeConfig();
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
        runtimeConfigService.updateRuntimeConfig(runtimeConfig);
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
        if (hiveGatewayPort.isAuthorizationFailure(exception)) {
            stateRef.set(HiveConnectionState.REVOKED);
            stopRuntimeTransport();
            return;
        }
        stateRef.set(HiveConnectionState.ERROR);
    }

    public record HiveStatusSnapshot(
            String state,
            boolean enabled,
            boolean managedByProperties,
            boolean managedJoinCodeAvailable,
            boolean autoConnect,
            String serverUrl,
            String displayName,
            String hostLabel,
            boolean sessionPresent,
            String golemId,
            String controlChannelUrl,
            Integer heartbeatIntervalSeconds,
            Instant lastConnectedAt,
            Instant lastHeartbeatAt,
            Instant lastTokenRotatedAt,
            String controlChannelState,
            Instant controlChannelConnectedAt,
            Instant controlChannelLastMessageAt,
            String controlChannelLastError,
            String lastReceivedCommandId,
            String lastReceivedCommandAt,
            int receivedCommandCount,
            int bufferedCommandCount,
            int pendingCommandCount,
            int pendingEventBatchCount,
            int pendingEventCount,
            String outboxLastError,
            String lastError) {
    }

    private enum HiveConnectionState {
        DISCONNECTED, JOINING, CONNECTED, DEGRADED, REVOKED, ERROR
    }
}
