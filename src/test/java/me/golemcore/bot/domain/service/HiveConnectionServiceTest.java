package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import me.golemcore.bot.adapter.outbound.hive.HiveApiClient;
import me.golemcore.bot.adapter.outbound.hive.HiveControlChannelClient;
import me.golemcore.bot.adapter.outbound.hive.HiveControlChannelStatus;
import me.golemcore.bot.adapter.outbound.hive.HiveEventOutboxService;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveInspectionRequestBody;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.hive.HiveCapabilitySnapshot;
import me.golemcore.bot.domain.model.hive.HivePolicyApplyResult;
import me.golemcore.bot.domain.model.hive.HivePolicyBindingState;
import me.golemcore.bot.domain.model.hive.HivePolicyModelCatalog;
import me.golemcore.bot.domain.model.hive.HivePolicyPackage;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.inbound.ChannelPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class HiveConnectionServiceTest {

    private BotProperties botProperties;
    private RuntimeConfigService runtimeConfigService;
    private HiveBootstrapConfigSynchronizer hiveBootstrapConfigSynchronizer;
    private HiveSessionStateStore hiveSessionStateStore;
    private HiveControlInboxService hiveControlInboxService;
    private HiveControlCommandDispatcher hiveControlCommandDispatcher;
    private HiveManagedPolicyService hiveManagedPolicyService;
    private HiveApiClient hiveApiClient;
    private HiveEventOutboxService hiveEventOutboxService;
    private HiveControlChannelClient hiveControlChannelClient;
    private ChannelPort webPort;
    private AtomicReference<Optional<HiveSessionState>> storedSession;
    private AtomicReference<HiveControlChannelStatus> controlChannelStatus;
    private AtomicReference<Consumer<HiveControlCommandEnvelope>> controlCommandConsumer;
    private HiveConnectionService service;

    @BeforeEach
    void setUp() {
        botProperties = new BotProperties();
        runtimeConfigService = mock(RuntimeConfigService.class);
        hiveBootstrapConfigSynchronizer = mock(HiveBootstrapConfigSynchronizer.class);
        hiveSessionStateStore = mock(HiveSessionStateStore.class);
        hiveControlInboxService = mock(HiveControlInboxService.class);
        hiveControlCommandDispatcher = mock(HiveControlCommandDispatcher.class);
        hiveManagedPolicyService = mock(HiveManagedPolicyService.class);
        hiveApiClient = mock(HiveApiClient.class);
        hiveEventOutboxService = mock(HiveEventOutboxService.class);
        hiveControlChannelClient = mock(HiveControlChannelClient.class);
        webPort = mock(ChannelPort.class);
        storedSession = new AtomicReference<>(Optional.empty());
        controlChannelStatus = new AtomicReference<>(HiveControlChannelStatus.disconnected());
        controlCommandConsumer = new AtomicReference<>();

        when(webPort.getChannelType()).thenReturn("web");
        when(runtimeConfigService.getHiveConfig()).thenReturn(RuntimeConfig.HiveConfig.builder()
                .enabled(true)
                .serverUrl("https://hive.example.com")
                .displayName("Builder")
                .hostLabel("lab-a")
                .autoConnect(false)
                .managedByProperties(false)
                .build());
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder()
                        .enabled(false)
                        .managedByProperties(false)
                        .build())
                .build());
        when(runtimeConfigService.isHiveManagedByProperties()).thenReturn(false);
        when(hiveSessionStateStore.load()).thenAnswer(invocation -> storedSession.get());
        doAnswer(invocation -> {
            storedSession.set(Optional.of(invocation.getArgument(0)));
            return null;
        }).when(hiveSessionStateStore).save(any(HiveSessionState.class));
        when(hiveControlInboxService.getSummary())
                .thenReturn(new HiveControlInboxService.InboxSummary(0, 0, 0, null, null));
        when(hiveControlInboxService.recordReceived(any(HiveControlCommandEnvelope.class)))
                .thenReturn(new HiveControlInboxService.RecordResult(
                        false,
                        new HiveControlInboxService.InboxSummary(1, 1, 1, "req-1", "2026-03-18T00:00:00Z")));
        when(hiveEventOutboxService.getSummary())
                .thenReturn(new HiveEventOutboxService.OutboxSummary(0, 0, null));
        when(hiveControlChannelClient.getStatus()).thenAnswer(invocation -> controlChannelStatus.get());
        doAnswer(invocation -> {
            controlChannelStatus.set(new HiveControlChannelStatus(
                    "CONNECTED",
                    Instant.parse("2026-03-18T00:00:00Z"),
                    null,
                    null,
                    null,
                    0));
            controlCommandConsumer.set(invocation.getArgument(1));
            return null;
        }).when(hiveControlChannelClient).connect(any(HiveSessionState.class), any());
        doAnswer(invocation -> {
            controlChannelStatus.set(HiveControlChannelStatus.disconnected());
            return null;
        }).when(hiveControlChannelClient).disconnect(any());
        when(hiveBootstrapConfigSynchronizer.getManagedJoinCode()).thenReturn(null);
        when(hiveManagedPolicyService.getBindingState()).thenReturn(Optional.empty());

        service = new HiveConnectionService(
                botProperties,
                runtimeConfigService,
                hiveBootstrapConfigSynchronizer,
                hiveSessionStateStore,
                hiveControlInboxService,
                hiveControlCommandDispatcher,
                hiveManagedPolicyService,
                hiveApiClient,
                hiveEventOutboxService,
                hiveControlChannelClient,
                new ChannelRegistry(List.of(webPort)),
                objectProvider(null),
                objectProvider(null),
                Clock.fixed(Instant.parse("2026-03-18T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldJoinWithManualJoinCodeAndPersistSession() {
        when(hiveApiClient.register(
                eq("https://hive.example.com"),
                eq("token-id.secret"),
                eq("Builder"),
                eq("lab-a"),
                eq("dev"),
                eq("dev"),
                anySet(),
                any(HiveCapabilitySnapshot.class))).thenReturn(new HiveApiClient.GolemAuthResponse(
                        "golem-1",
                        "access",
                        "refresh",
                        Instant.parse("2026-03-18T00:10:00Z"),
                        Instant.parse("2026-03-19T00:10:00Z"),
                        "hive",
                        "golems",
                        "/ws/golems/control",
                        30,
                        List.of("golems:heartbeat")));

        HiveConnectionService.HiveStatusSnapshot status = service.join("token-id.secret:https://hive.example.com/");

        assertEquals("CONNECTED", status.state());
        assertEquals("golem-1", status.golemId());
        assertEquals("CONNECTED", status.controlChannelState());
        verify(hiveApiClient).heartbeat(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eq("connected"), eq("Hive join completed"), eq(null), anyLong(),
                any(), any(), any(), any(), any(), any());
        verify(hiveControlChannelClient).connect(any(HiveSessionState.class), any());
        verify(hiveSessionStateStore).save(any(HiveSessionState.class));
        verify(runtimeConfigService).updateRuntimeConfig(any(RuntimeConfig.class));
        verify(hiveApiClient).register(eq("https://hive.example.com"),
                eq("token-id.secret"),
                eq("Builder"),
                eq("lab-a"),
                eq("dev"),
                eq("dev"),
                anySet(),
                any(HiveCapabilitySnapshot.class));
    }

    @Test
    void shouldResetInFlightInboxCommandsOnInit() {
        when(hiveControlInboxService.resetInFlightCommandsForRestart()).thenReturn(1);

        service.init();

        verify(hiveControlInboxService).resetInFlightCommandsForRestart();
    }

    @Test
    void shouldReconnectUsingPersistedSession() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .refreshToken("refresh")
                .accessToken("access-old")
                .controlChannelUrl("/ws/golems/control")
                .build();
        storedSession.set(Optional.of(sessionState));
        when(hiveApiClient.rotate("https://hive.example.com", "golem-1", "refresh"))
                .thenReturn(new HiveApiClient.GolemAuthResponse(
                        "golem-1",
                        "access-new",
                        "refresh-new",
                        Instant.parse("2026-03-18T00:10:00Z"),
                        Instant.parse("2026-03-19T00:10:00Z"),
                        "hive",
                        "golems",
                        "/ws/golems/control",
                        30,
                        List.of("golems:heartbeat")));

        HiveConnectionService.HiveStatusSnapshot status = service.reconnect();

        assertEquals("CONNECTED", status.state());
        verify(hiveApiClient).heartbeat(eq("https://hive.example.com"), eq("golem-1"), eq("access-new"),
                eq("connected"), eq("Hive reconnect completed"), eq(null), anyLong(),
                any(), any(), any(), any(), any(), any());
        verify(hiveControlChannelClient).connect(any(HiveSessionState.class), any());
        verify(runtimeConfigService, never()).updateRuntimeConfig(any(RuntimeConfig.class));
    }

    @Test
    void shouldUseManagedJoinCodeWhenManagedModeIsActive() {
        when(runtimeConfigService.isHiveManagedByProperties()).thenReturn(true);
        when(hiveBootstrapConfigSynchronizer.getManagedJoinCode())
                .thenReturn("managed.secret:https://hive.example.com");
        when(hiveApiClient.register(
                eq("https://hive.example.com"),
                eq("managed.secret"),
                eq("Builder"),
                eq("lab-a"),
                eq("dev"),
                eq("dev"),
                anySet(),
                any(HiveCapabilitySnapshot.class))).thenReturn(new HiveApiClient.GolemAuthResponse(
                        "golem-1",
                        "access",
                        "refresh",
                        Instant.parse("2026-03-18T00:10:00Z"),
                        Instant.parse("2026-03-19T00:10:00Z"),
                        "hive",
                        "golems",
                        null,
                        30,
                        List.of()));

        HiveConnectionService.HiveStatusSnapshot status = service.join(null);

        assertEquals("CONNECTED", status.state());
        verify(runtimeConfigService, never()).updateRuntimeConfig(any(RuntimeConfig.class));
    }

    @Test
    void shouldReconnectInsteadOfRegisteringAgainWhenSessionAlreadyExistsForSameServer() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .refreshToken("refresh")
                .accessToken("access-old")
                .controlChannelUrl("/ws/golems/control")
                .build();
        storedSession.set(Optional.of(sessionState));
        when(hiveApiClient.rotate("https://hive.example.com", "golem-1", "refresh"))
                .thenReturn(new HiveApiClient.GolemAuthResponse(
                        "golem-1",
                        "access-new",
                        "refresh-new",
                        Instant.parse("2026-03-18T00:10:00Z"),
                        Instant.parse("2026-03-19T00:10:00Z"),
                        "hive",
                        "golems",
                        "/ws/golems/control",
                        30,
                        List.of("golems:heartbeat")));

        HiveConnectionService.HiveStatusSnapshot status = service.join("token-id.secret:https://hive.example.com/");

        assertEquals("CONNECTED", status.state());
        verify(hiveApiClient, never()).register(any(), any(), any(), any(), any(), any(), anySet(), any());
        verify(hiveApiClient).rotate("https://hive.example.com", "golem-1", "refresh");
    }

    @Test
    void shouldRejectJoinToDifferentServerWhenSessionAlreadyExists() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .refreshToken("refresh")
                .accessToken("access-old")
                .controlChannelUrl("/ws/golems/control")
                .build();
        storedSession.set(Optional.of(sessionState));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.join("token-id.secret:https://other-hive.example.com"));

        assertEquals(
                "A Hive session already exists. Leave the current session before joining a different Hive server.",
                error.getMessage());
        verify(hiveApiClient, never()).register(any(), any(), any(), any(), any(), any(), anySet(), any());
        verify(hiveApiClient, never()).rotate(any(), any(), any());
    }

    @Test
    void shouldRunHeartbeatMaintenanceCycle() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .accessToken("access")
                .refreshToken("refresh")
                .accessTokenExpiresAt(Instant.parse("2026-03-18T00:10:00Z"))
                .heartbeatIntervalSeconds(30)
                .controlChannelUrl("/ws/golems/control")
                .build();
        storedSession.set(Optional.of(sessionState));
        controlChannelStatus.set(new HiveControlChannelStatus(
                "CONNECTED",
                Instant.parse("2026-03-18T00:00:00Z"),
                null,
                null,
                null,
                0));

        service.runHeartbeatMaintenanceCycle();

        verify(hiveApiClient).heartbeat(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eq("connected"), eq("Control channel connected"), eq(null), anyLong(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldRotateTokensBeforeHeartbeatWhenAccessTokenIsNearExpiry() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .accessToken("access-old")
                .refreshToken("refresh-old")
                .accessTokenExpiresAt(Instant.parse("2026-03-18T00:00:30Z"))
                .heartbeatIntervalSeconds(30)
                .controlChannelUrl("/ws/golems/control")
                .build();
        storedSession.set(Optional.of(sessionState));
        controlChannelStatus.set(new HiveControlChannelStatus(
                "CONNECTED",
                Instant.parse("2026-03-18T00:00:00Z"),
                null,
                null,
                null,
                0));
        when(hiveApiClient.rotate("https://hive.example.com", "golem-1", "refresh-old"))
                .thenReturn(new HiveApiClient.GolemAuthResponse(
                        "golem-1",
                        "access-new",
                        "refresh-new",
                        Instant.parse("2026-03-18T00:10:00Z"),
                        Instant.parse("2026-03-19T00:10:00Z"),
                        "hive",
                        "golems",
                        "/ws/golems/control",
                        30,
                        List.of("golems:heartbeat")));

        service.runHeartbeatMaintenanceCycle();

        assertEquals("access-new", storedSession.get().orElseThrow().getAccessToken());
        assertNotNull(storedSession.get().orElseThrow().getLastTokenRotatedAt());
        verify(hiveApiClient).rotate("https://hive.example.com", "golem-1", "refresh-old");
        verify(hiveControlChannelClient).disconnect("token-refresh");
        verify(hiveApiClient).heartbeat(eq("https://hive.example.com"), eq("golem-1"), eq("access-new"),
                eq("degraded"), eq("Waiting for control channel reconnect"), eq(null), anyLong(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldReconnectControlChannelDuringMaintenanceCycle() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .accessToken("access")
                .refreshToken("refresh")
                .controlChannelUrl("/ws/golems/control")
                .build();
        storedSession.set(Optional.of(sessionState));
        controlChannelStatus.set(HiveControlChannelStatus.disconnected());

        service.runControlChannelMaintenanceCycle();

        verify(hiveControlChannelClient).connect(any(HiveSessionState.class), any());
        assertEquals("CONNECTED", service.getStatus().state());
    }

    @Test
    void shouldRecordReceivedControlCommandFromChannelCallback() {
        when(hiveApiClient.register(
                eq("https://hive.example.com"),
                eq("token-id.secret"),
                eq("Builder"),
                eq("lab-a"),
                eq("dev"),
                eq("dev"),
                anySet(),
                any(HiveCapabilitySnapshot.class))).thenReturn(new HiveApiClient.GolemAuthResponse(
                        "golem-1",
                        "access",
                        "refresh",
                        Instant.parse("2026-03-18T00:10:00Z"),
                        Instant.parse("2026-03-19T00:10:00Z"),
                        "hive",
                        "golems",
                        "/ws/golems/control",
                        30,
                        List.of("golems:heartbeat")));
        when(hiveControlInboxService.resolveTrackingId(any(HiveControlCommandEnvelope.class))).thenAnswer(
                invocation -> invocation.<HiveControlCommandEnvelope>getArgument(0).getRequestId());

        service.join("token-id.secret:https://hive.example.com/");
        Consumer<HiveControlCommandEnvelope> consumer = controlCommandConsumer.get();
        assertNotNull(consumer);

        consumer.accept(HiveControlCommandEnvelope.builder()
                .eventType("inspection.request")
                .requestId("req-1")
                .threadId("thread-1")
                .inspection(HiveInspectionRequestBody.builder().operation("sessions.list").build())
                .build());

        verify(hiveControlInboxService).recordReceived(any(HiveControlCommandEnvelope.class));
        verify(hiveControlInboxService, org.mockito.Mockito.times(2)).drainPending(any());
    }

    @Test
    void shouldFetchAndReportManagedPolicyDuringHeartbeatMaintenance() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .accessToken("access")
                .refreshToken("refresh")
                .accessTokenExpiresAt(Instant.parse("2026-03-18T00:10:00Z"))
                .heartbeatIntervalSeconds(30)
                .controlChannelUrl("/ws/golems/control")
                .scopes(List.of("golems:heartbeat", "golems:policy:read", "golems:policy:write"))
                .build();
        storedSession.set(Optional.of(sessionState));
        controlChannelStatus.set(new HiveControlChannelStatus(
                "CONNECTED",
                Instant.parse("2026-03-18T00:00:00Z"),
                null,
                null,
                null,
                0));
        HivePolicyPackage policyPackage = HivePolicyPackage.builder()
                .policyGroupId("pg-1")
                .targetVersion(5)
                .checksum("sha256:abcd")
                .llmProviders(Map.of("openai", RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of("sk-test"))
                        .apiType("openai")
                        .build()))
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder()
                        .balancedModel("openai/gpt-5.1")
                        .build())
                .modelCatalog(HivePolicyModelCatalog.builder()
                        .defaultModel("openai/gpt-5.1")
                        .build())
                .build();
        when(hiveApiClient.getPolicyPackage("https://hive.example.com", "golem-1", "access"))
                .thenReturn(policyPackage);
        when(hiveManagedPolicyService.applyPolicyPackage(policyPackage)).thenReturn(HivePolicyApplyResult.builder()
                .policyGroupId("pg-1")
                .targetVersion(5)
                .appliedVersion(5)
                .syncStatus("IN_SYNC")
                .checksum("sha256:abcd")
                .build());
        when(hiveManagedPolicyService.getBindingState()).thenReturn(Optional.of(HivePolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(5)
                .appliedVersion(5)
                .syncStatus("IN_SYNC")
                .checksum("sha256:abcd")
                .build()));

        service.runHeartbeatMaintenanceCycle();

        verify(hiveApiClient).getPolicyPackage("https://hive.example.com", "golem-1", "access");
        verify(hiveManagedPolicyService).applyPolicyPackage(policyPackage);
        verify(hiveApiClient).reportPolicyApplyResult(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                any(HivePolicyApplyResult.class));
    }

    @Test
    void shouldOmitPolicyHeartbeatFieldsWithoutPolicyWriteScope() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .accessToken("access")
                .refreshToken("refresh")
                .accessTokenExpiresAt(Instant.parse("2026-03-18T00:10:00Z"))
                .heartbeatIntervalSeconds(30)
                .controlChannelUrl("/ws/golems/control")
                .scopes(List.of("golems:heartbeat", "golems:policy:read"))
                .build();
        storedSession.set(Optional.of(sessionState));
        controlChannelStatus.set(new HiveControlChannelStatus(
                "CONNECTED",
                Instant.parse("2026-03-18T00:00:00Z"),
                null,
                null,
                null,
                0));
        when(hiveManagedPolicyService.getBindingState()).thenReturn(Optional.of(HivePolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(5)
                .appliedVersion(4)
                .syncStatus("OUT_OF_SYNC")
                .lastErrorDigest("provider timeout")
                .build()));
        when(hiveManagedPolicyService.isSyncPending()).thenReturn(false);

        service.runHeartbeatMaintenanceCycle();

        verify(hiveApiClient).heartbeat(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eq("connected"), eq("Control channel connected"), eq(null), anyLong(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void shouldClearManagedPolicyBindingWhenLeavingHive() {
        AtomicReference<Optional<HivePolicyBindingState>> bindingState = new AtomicReference<>(Optional.of(
                HivePolicyBindingState.builder()
                        .policyGroupId("pg-1")
                        .targetVersion(5)
                        .appliedVersion(5)
                        .syncStatus("IN_SYNC")
                        .build()));
        when(hiveManagedPolicyService.getBindingState()).thenAnswer(invocation -> bindingState.get());
        doAnswer(invocation -> {
            bindingState.set(Optional.empty());
            return null;
        }).when(hiveManagedPolicyService).clearBinding();

        HiveConnectionService.HiveStatusSnapshot status = service.leave();

        verify(hiveManagedPolicyService).clearBinding();
        assertEquals("DISCONNECTED", status.state());
        assertNull(status.policyGroupId());
        assertNull(status.targetPolicyVersion());
        assertNull(status.appliedPolicyVersion());
        assertNull(status.policySyncStatus());
    }

    private <T> ObjectProvider<T> objectProvider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
