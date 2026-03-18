package me.golemcore.bot.domain.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.outbound.hive.HiveApiClient;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.inbound.ChannelPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class HiveConnectionService {

    private final BotProperties botProperties;
    private final RuntimeConfigService runtimeConfigService;
    private final HiveBootstrapConfigSynchronizer hiveBootstrapConfigSynchronizer;
    private final HiveSessionStateStore hiveSessionStateStore;
    private final HiveApiClient hiveApiClient;
    private final ChannelRegistry channelRegistry;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final ObjectProvider<GitProperties> gitPropertiesProvider;
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

    public HiveConnectionService(
            BotProperties botProperties,
            RuntimeConfigService runtimeConfigService,
            HiveBootstrapConfigSynchronizer hiveBootstrapConfigSynchronizer,
            HiveSessionStateStore hiveSessionStateStore,
            HiveApiClient hiveApiClient,
            ChannelRegistry channelRegistry,
            ObjectProvider<BuildProperties> buildPropertiesProvider,
            ObjectProvider<GitProperties> gitPropertiesProvider,
            Clock clock) {
        this.botProperties = botProperties;
        this.runtimeConfigService = runtimeConfigService;
        this.hiveBootstrapConfigSynchronizer = hiveBootstrapConfigSynchronizer;
        this.hiveSessionStateStore = hiveSessionStateStore;
        this.hiveApiClient = hiveApiClient;
        this.channelRegistry = channelRegistry;
        this.buildPropertiesProvider = buildPropertiesProvider;
        this.gitPropertiesProvider = gitPropertiesProvider;
        this.clock = clock;
    }

    @PostConstruct
    void init() {
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
        executor.shutdownNow();
    }

    public HiveStatusSnapshot getStatus() {
        RuntimeConfig.HiveConfig hiveConfig = runtimeConfigService.getHiveConfig();
        HiveSessionState sessionState = hiveSessionStateStore.load().orElse(null);
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
                lastError);
    }

    public HiveStatusSnapshot join(String requestedJoinCode) {
        synchronized (lock) {
            stateRef.set(HiveConnectionState.JOINING);
            lastErrorRef.set(null);
            String joinCode = resolveJoinCodeForAction(requestedJoinCode);
            HiveJoinCodeParser.ParsedJoinCode parsedJoinCode = HiveJoinCodeParser.parse(joinCode);
            RuntimeConfig.HiveConfig hiveConfig = runtimeConfigService.getHiveConfig();
            try {
                HiveApiClient.GolemAuthResponse response = hiveApiClient.register(
                        parsedJoinCode.serverUrl(),
                        parsedJoinCode.enrollmentToken(),
                        resolveDisplayName(hiveConfig),
                        resolveHostLabel(hiveConfig),
                        resolveRuntimeVersion(),
                        resolveBuildVersion(),
                        resolveSupportedChannels());
                HiveSessionState sessionState = buildSessionState(parsedJoinCode.serverUrl(), response);
                sendHeartbeat(sessionState, "Hive join completed");
                sessionState.setLastConnectedAt(Instant.now(clock));
                sessionState.setLastError(null);
                hiveSessionStateStore.save(sessionState);
                lastErrorRef.set(null);
                stateRef.set(HiveConnectionState.CONNECTED);
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
            HiveSessionState sessionState = sessionStateOptional.get();
            try {
                HiveApiClient.GolemAuthResponse response = hiveApiClient.rotate(
                        sessionState.getServerUrl(),
                        sessionState.getGolemId(),
                        sessionState.getRefreshToken());
                applyAuthResponse(sessionState, response);
                sendHeartbeat(sessionState, "Hive reconnect completed");
                sessionState.setLastConnectedAt(Instant.now(clock));
                sessionState.setLastError(null);
                hiveSessionStateStore.save(sessionState);
                lastErrorRef.set(null);
                stateRef.set(HiveConnectionState.CONNECTED);
                return getStatus();
            } catch (RuntimeException exception) {
                handleFailure(exception);
                throw exception;
            }
        }
    }

    public HiveStatusSnapshot leave() {
        synchronized (lock) {
            hiveSessionStateStore.clear();
            stateRef.set(HiveConnectionState.DISCONNECTED);
            lastErrorRef.set(null);
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
        String configuredDisplayName = botProperties.getHive().getDisplayName();
        if (configuredDisplayName != null && !configuredDisplayName.isBlank()) {
            return configuredDisplayName.trim();
        }
        return "GolemCore Bot";
    }

    private String resolveHostLabel(RuntimeConfig.HiveConfig hiveConfig) {
        if (hiveConfig.getHostLabel() != null && !hiveConfig.getHostLabel().isBlank()) {
            return hiveConfig.getHostLabel().trim();
        }
        String configuredHostLabel = botProperties.getHive().getHostLabel();
        if (configuredHostLabel != null && !configuredHostLabel.isBlank()) {
            return configuredHostLabel.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException exception) {
            return "local-host";
        }
    }

    private String resolveRuntimeVersion() {
        BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        return buildProperties != null ? buildProperties.getVersion() : "dev";
    }

    private String resolveBuildVersion() {
        GitProperties gitProperties = gitPropertiesProvider.getIfAvailable();
        if (gitProperties != null && gitProperties.getShortCommitId() != null) {
            return gitProperties.getShortCommitId();
        }
        return resolveRuntimeVersion();
    }

    private Set<String> resolveSupportedChannels() {
        Set<String> supportedChannels = new LinkedHashSet<>();
        for (ChannelPort port : channelRegistry.getAll()) {
            supportedChannels.add(port.getChannelType());
        }
        return supportedChannels;
    }

    private HiveSessionState buildSessionState(String serverUrl, HiveApiClient.GolemAuthResponse response) {
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

    private void applyAuthResponse(HiveSessionState sessionState, HiveApiClient.GolemAuthResponse response) {
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
        hiveApiClient.heartbeat(
                sessionState.getServerUrl(),
                sessionState.getGolemId(),
                sessionState.getAccessToken(),
                "connected",
                healthSummary,
                null,
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000L);
        sessionState.setLastHeartbeatAt(Instant.now(clock));
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

    private void handleFailure(RuntimeException exception) {
        Optional<HiveSessionState> sessionStateOptional = hiveSessionStateStore.load();
        if (sessionStateOptional.isPresent()) {
            HiveSessionState sessionState = sessionStateOptional.get();
            sessionState.setLastError(exception.getMessage());
            hiveSessionStateStore.save(sessionState);
        }
        lastErrorRef.set(exception.getMessage());
        if (exception instanceof HiveApiClient.HiveApiException hiveApiException
                && (hiveApiException.getStatusCode() == 401 || hiveApiException.getStatusCode() == 403)) {
            stateRef.set(HiveConnectionState.REVOKED);
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
            String lastError) {
    }

    private enum HiveConnectionState {
        DISCONNECTED, JOINING, CONNECTED, DEGRADED, REVOKED, ERROR
    }
}
