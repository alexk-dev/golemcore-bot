package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.hive.HiveCapabilitySnapshot;
import me.golemcore.bot.domain.model.hive.HiveControlChannelStatusSnapshot;
import me.golemcore.bot.domain.model.hive.HiveOutboxSummary;
import me.golemcore.bot.domain.model.hive.HivePolicyApplyResult;
import me.golemcore.bot.domain.model.hive.HivePolicyBindingState;
import me.golemcore.bot.domain.model.hive.HivePolicyPackage;
import me.golemcore.bot.domain.model.hive.HiveStatusSnapshot;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import me.golemcore.bot.port.outbound.HiveBootstrapSettingsPort;
import me.golemcore.bot.port.outbound.HiveControlChannelPort;
import me.golemcore.bot.port.outbound.HiveEventOutboxPort;
import me.golemcore.bot.port.outbound.HiveMachinePort;
import me.golemcore.bot.port.outbound.HiveRuntimeMetadataPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HiveConnectionServiceTest {

    private HiveBootstrapSettingsPort hiveBootstrapSettingsPort;
    private RuntimeConfigService runtimeConfigService;
    private HiveBootstrapConfigSynchronizer hiveBootstrapConfigSynchronizer;
    private HiveSessionStateStore hiveSessionStateStore;
    private HiveControlInboxService hiveControlInboxService;
    private HiveControlCommandDispatcher hiveControlCommandDispatcher;
    private HiveManagedPolicyService hiveManagedPolicyService;
    private HiveMachinePort hiveMachinePort;
    private HiveEventOutboxPort hiveEventOutboxPort;
    private HiveControlChannelPort hiveControlChannelPort;
    private HiveRuntimeMetadataPort hiveRuntimeMetadataPort;
    private ChannelRuntimePort channelRuntimePort;
    private ChannelPort webPort;
    private AtomicReference<Optional<HiveSessionState>> storedSession;
    private AtomicReference<HiveControlChannelStatusSnapshot> controlChannelStatus;
    private AtomicReference<Consumer<HiveControlCommandEnvelope>> controlCommandConsumer;
    private HiveConnectionService service;

    @BeforeEach
    void setUp() {
        hiveBootstrapSettingsPort = mock(HiveBootstrapSettingsPort.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        hiveBootstrapConfigSynchronizer = mock(HiveBootstrapConfigSynchronizer.class);
        hiveSessionStateStore = mock(HiveSessionStateStore.class);
        hiveControlInboxService = mock(HiveControlInboxService.class);
        hiveControlCommandDispatcher = mock(HiveControlCommandDispatcher.class);
        hiveManagedPolicyService = mock(HiveManagedPolicyService.class);
        hiveMachinePort = mock(HiveMachinePort.class);
        hiveEventOutboxPort = mock(HiveEventOutboxPort.class);
        hiveControlChannelPort = mock(HiveControlChannelPort.class);
        hiveRuntimeMetadataPort = mock(HiveRuntimeMetadataPort.class);
        channelRuntimePort = mock(ChannelRuntimePort.class);
        webPort = mock(ChannelPort.class);
        storedSession = new AtomicReference<>(Optional.empty());
        controlChannelStatus = new AtomicReference<>(HiveControlChannelStatusSnapshot.disconnected());
        controlCommandConsumer = new AtomicReference<>();

        when(webPort.getChannelType()).thenReturn("web");
        when(channelRuntimePort.listChannels()).thenReturn(List.of(webPort));
        when(hiveBootstrapSettingsPort.joinCode()).thenReturn(null);
        when(hiveBootstrapSettingsPort.displayName()).thenReturn("Builder");
        when(hiveBootstrapSettingsPort.hostLabel()).thenReturn("lab-a");
        when(hiveRuntimeMetadataPort.runtimeVersion()).thenReturn("dev");
        when(hiveRuntimeMetadataPort.buildVersion()).thenReturn("dev");
        when(hiveRuntimeMetadataPort.defaultHostLabel()).thenReturn("local-host");
        when(hiveRuntimeMetadataPort.uptimeSeconds()).thenReturn(42L);
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
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().routing(
                        RuntimeConfig.TierBinding.builder().model("openai/gpt-5.1").build()).build())
                .build());
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("openai"));
        when(runtimeConfigService.isHiveManagedByProperties()).thenReturn(false);
        when(hiveSessionStateStore.load()).thenAnswer(invocation -> storedSession.get());
        doAnswer(invocation -> {
            storedSession.set(Optional.of(invocation.getArgument(0)));
            return null;
        }).when(hiveSessionStateStore).save(any(HiveSessionState.class));
        when(hiveControlInboxService.getSummary())
                .thenReturn(new HiveControlInboxService.InboxSummary(0, 0, 0, null, null));
        when(hiveControlInboxService.drainPending(any())).thenReturn(0);
        when(hiveEventOutboxPort.getSummary()).thenReturn(new HiveOutboxSummary(0, 0, null));
        when(hiveControlChannelPort.getStatus()).thenAnswer(invocation -> controlChannelStatus.get());
        doAnswer(invocation -> {
            controlChannelStatus.set(new HiveControlChannelStatusSnapshot(
                    "CONNECTED",
                    Instant.parse("2026-03-18T00:00:00Z"),
                    null,
                    null,
                    null,
                    0));
            controlCommandConsumer.set(invocation.getArgument(1));
            return null;
        }).when(hiveControlChannelPort).connect(any(HiveSessionState.class), any());
        doAnswer(invocation -> {
            controlChannelStatus.set(HiveControlChannelStatusSnapshot.disconnected());
            return null;
        }).when(hiveControlChannelPort).disconnect(any());
        when(hiveBootstrapConfigSynchronizer.getManagedJoinCode()).thenReturn(null);
        when(hiveManagedPolicyService.getBindingState()).thenReturn(Optional.empty());
        when(hiveManagedPolicyService.isSyncPending()).thenReturn(false);

        service = new HiveConnectionService(
                hiveBootstrapSettingsPort,
                runtimeConfigService,
                hiveBootstrapConfigSynchronizer,
                hiveSessionStateStore,
                hiveControlInboxService,
                hiveControlCommandDispatcher,
                hiveManagedPolicyService,
                hiveMachinePort,
                hiveEventOutboxPort,
                hiveControlChannelPort,
                hiveRuntimeMetadataPort,
                channelRuntimePort,
                Clock.fixed(Instant.parse("2026-03-18T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldJoinWithManualJoinCodeAndPersistSession() {
        when(hiveMachinePort.register(
                eq("https://hive.example.com"),
                eq("token-id.secret"),
                eq("Builder"),
                eq("lab-a"),
                eq("dev"),
                eq("dev"),
                anySet(),
                any(HiveCapabilitySnapshot.class))).thenReturn(new HiveMachinePort.AuthSession(
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

        HiveStatusSnapshot status = service.join("token-id.secret:https://hive.example.com/");

        assertEquals("CONNECTED", status.state());
        assertEquals("golem-1", status.golemId());
        assertEquals("CONNECTED", status.controlChannelState());
        verify(hiveMachinePort).heartbeat(
                eq("https://hive.example.com"),
                eq("golem-1"),
                eq("access"),
                eq("connected"),
                eq("Hive join completed"),
                isNull(),
                anyLong(),
                any(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull());
        verify(hiveControlChannelPort).connect(any(HiveSessionState.class), any());
        verify(hiveSessionStateStore).save(any(HiveSessionState.class));
        verify(runtimeConfigService).updateRuntimeConfig(any(RuntimeConfig.class));
    }

    @Test
    void shouldPublishPolicyCapabilitySnapshotOnJoin() {
        when(hiveMachinePort.register(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anySet(),
                any(HiveCapabilitySnapshot.class))).thenReturn(new HiveMachinePort.AuthSession(
                        "golem-1",
                        "access",
                        "refresh",
                        Instant.parse("2026-03-18T00:10:00Z"),
                        Instant.parse("2026-03-19T00:10:00Z"),
                        "hive",
                        "golems",
                        null,
                        30,
                        List.of("golems:heartbeat")));

        service.join("token-id.secret:https://hive.example.com/");

        ArgumentCaptor<HiveCapabilitySnapshot> snapshotCaptor = ArgumentCaptor.forClass(HiveCapabilitySnapshot.class);
        verify(hiveMachinePort).register(any(), any(), any(), any(), any(), any(), anySet(), snapshotCaptor.capture());
        HiveCapabilitySnapshot snapshot = snapshotCaptor.getValue();
        assertEquals(Set.of("openai"), snapshot.getProviders());
        assertEquals(Set.of("policy-sync-v1"), snapshot.getEnabledAutonomyFeatures());
        assertEquals(Set.of("web", "control"), snapshot.getSupportedChannels());
        assertEquals("openai/gpt-5.1", snapshot.getDefaultModel());
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
        when(hiveMachinePort.rotate("https://hive.example.com", "golem-1", "refresh"))
                .thenReturn(new HiveMachinePort.AuthSession(
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

        HiveStatusSnapshot status = service.reconnect();

        assertEquals("CONNECTED", status.state());
        verify(hiveMachinePort).heartbeat(
                eq("https://hive.example.com"),
                eq("golem-1"),
                eq("access-new"),
                eq("connected"),
                eq("Hive reconnect completed"),
                isNull(),
                anyLong(),
                any(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull());
        verify(runtimeConfigService, never()).updateRuntimeConfig(any(RuntimeConfig.class));
    }

    @Test
    void shouldUseManagedJoinCodeWhenManagedModeIsActive() {
        when(runtimeConfigService.isHiveManagedByProperties()).thenReturn(true);
        when(hiveBootstrapConfigSynchronizer.getManagedJoinCode())
                .thenReturn("managed.secret:https://hive.example.com");
        when(hiveMachinePort.register(any(), any(), any(), any(), any(), any(), anySet(), any()))
                .thenReturn(new HiveMachinePort.AuthSession(
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

        HiveStatusSnapshot status = service.join(null);

        assertEquals("CONNECTED", status.state());
        verify(runtimeConfigService, never()).updateRuntimeConfig(any(RuntimeConfig.class));
    }

    @Test
    void shouldMarkStateRevokedWhenJoinFailsWithAuthorizationFailure() {
        when(hiveMachinePort.register(any(), any(), any(), any(), any(), any(), anySet(), any()))
                .thenThrow(new HiveMachinePort.HiveMachineException(403, "forbidden", null));

        HiveMachinePort.HiveMachineException error = assertThrows(
                HiveMachinePort.HiveMachineException.class,
                () -> service.join("token-id.secret:https://hive.example.com/"));

        assertEquals("forbidden", error.getMessage());
        assertEquals("REVOKED", service.getStatus().state());
        verify(hiveControlChannelPort, atLeastOnce()).disconnect("stop");
    }

    @Test
    void shouldClearManagedPolicyBindingWhenLeaving() {
        storedSession.set(Optional.of(HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .build()));

        HiveStatusSnapshot status = service.leave();

        assertEquals("DISCONNECTED", status.state());
        verify(hiveSessionStateStore).clear();
        verify(hiveControlInboxService).clear();
        verify(hiveEventOutboxPort).clear();
        verify(hiveManagedPolicyService).clearBinding();
    }

    @Test
    void shouldExposePolicyStateInStatus() {
        storedSession.set(Optional.of(HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .build()));
        when(hiveManagedPolicyService.getBindingState()).thenReturn(Optional.of(HivePolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(4)
                .appliedVersion(3)
                .syncStatus("OUT_OF_SYNC")
                .lastErrorDigest("bad-key")
                .build()));

        HiveStatusSnapshot status = service.getStatus();

        assertEquals("pg-1", status.policyGroupId());
        assertEquals(4, status.targetPolicyVersion());
        assertEquals(3, status.appliedPolicyVersion());
        assertEquals("OUT_OF_SYNC", status.policySyncStatus());
        assertEquals("bad-key", status.lastPolicyErrorDigest());
    }

    @Test
    void shouldSynchronizeManagedPolicyDuringHeartbeatWhenScopesAllowIt() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .accessToken("access")
                .controlChannelUrl("/ws/golems/control")
                .scopes(List.of("golems:policy:read", "golems:policy:write"))
                .build();
        storedSession.set(Optional.of(sessionState));
        when(hiveMachinePort.getPolicyPackage("https://hive.example.com", "golem-1", "access"))
                .thenReturn(HivePolicyPackage.builder()
                        .policyGroupId("pg-1")
                        .targetVersion(5)
                        .checksum("abc")
                        .build());
        when(hiveManagedPolicyService.applyPolicyPackage(any(HivePolicyPackage.class)))
                .thenReturn(HivePolicyApplyResult.builder()
                        .policyGroupId("pg-1")
                        .targetVersion(5)
                        .appliedVersion(5)
                        .syncStatus("IN_SYNC")
                        .checksum("abc")
                        .build());
        when(hiveManagedPolicyService.getBindingState()).thenReturn(Optional.of(HivePolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(5)
                .appliedVersion(5)
                .syncStatus("IN_SYNC")
                .lastErrorDigest(null)
                .build()));
        controlChannelStatus.set(new HiveControlChannelStatusSnapshot(
                "CONNECTED",
                Instant.parse("2026-03-18T00:00:00Z"),
                null,
                null,
                null,
                0));

        service.runHeartbeatMaintenanceCycle();

        verify(hiveMachinePort).getPolicyPackage("https://hive.example.com", "golem-1", "access");
        verify(hiveManagedPolicyService).applyPolicyPackage(any(HivePolicyPackage.class));
        verify(hiveMachinePort).reportPolicyApplyResult(
                eq("https://hive.example.com"),
                eq("golem-1"),
                eq("access"),
                any(HivePolicyApplyResult.class));
        verify(hiveMachinePort).heartbeat(
                eq("https://hive.example.com"),
                eq("golem-1"),
                eq("access"),
                eq("connected"),
                eq("Control channel connected"),
                isNull(),
                anyLong(),
                any(),
                eq("pg-1"),
                eq(5),
                eq(5),
                eq("IN_SYNC"),
                isNull());
    }

    @Test
    void shouldNotSendPolicyFieldsWithoutWriteScope() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .accessToken("access")
                .controlChannelUrl("/ws/golems/control")
                .scopes(List.of("golems:heartbeat"))
                .build();
        storedSession.set(Optional.of(sessionState));
        when(hiveManagedPolicyService.getBindingState()).thenReturn(Optional.of(HivePolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(5)
                .appliedVersion(4)
                .syncStatus("OUT_OF_SYNC")
                .lastErrorDigest("boom")
                .build()));
        controlChannelStatus.set(new HiveControlChannelStatusSnapshot(
                "CONNECTED",
                Instant.parse("2026-03-18T00:00:00Z"),
                null,
                null,
                null,
                0));

        service.runHeartbeatMaintenanceCycle();

        verify(hiveMachinePort, never()).getPolicyPackage(any(), any(), any());
        verify(hiveMachinePort).heartbeat(
                eq("https://hive.example.com"),
                eq("golem-1"),
                eq("access"),
                eq("connected"),
                eq("Control channel connected"),
                isNull(),
                anyLong(),
                any(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull());
    }

    @Test
    void shouldClearBindingWhenPolicyPackageIsMissing() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .accessToken("access")
                .controlChannelUrl("/ws/golems/control")
                .scopes(List.of("golems:policy:read"))
                .build();
        storedSession.set(Optional.of(sessionState));
        when(hiveManagedPolicyService.isSyncPending()).thenReturn(true);
        when(hiveMachinePort.getPolicyPackage("https://hive.example.com", "golem-1", "access"))
                .thenThrow(new HiveMachinePort.HiveMachineException(404, "missing", null));

        service.runControlChannelMaintenanceCycle();

        verify(hiveManagedPolicyService).clearBinding();
    }
}
