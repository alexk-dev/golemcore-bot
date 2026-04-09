package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveInspectionRequestBody;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.hive.HiveAuthSession;
import me.golemcore.bot.domain.model.hive.HiveControlChannelStatusSnapshot;
import me.golemcore.bot.domain.model.hive.HiveOutboxSummary;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import me.golemcore.bot.port.outbound.HiveBootstrapSettingsPort;
import me.golemcore.bot.port.outbound.HiveControlChannelPort;
import me.golemcore.bot.port.outbound.HiveEventOutboxPort;
import me.golemcore.bot.port.outbound.HiveGatewayPort;
import me.golemcore.bot.port.outbound.HiveRuntimeMetadataPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveConnectionServiceTest {

    private HiveBootstrapSettingsPort hiveBootstrapSettingsPort;
    private RuntimeConfigService runtimeConfigService;
    private HiveBootstrapConfigSynchronizer hiveBootstrapConfigSynchronizer;
    private HiveSessionStateStore hiveSessionStateStore;
    private HiveControlInboxService hiveControlInboxService;
    private HiveControlCommandDispatcher hiveControlCommandDispatcher;
    private HiveGatewayPort hiveGatewayPort;
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
        hiveGatewayPort = mock(HiveGatewayPort.class);
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
        when(hiveRuntimeMetadataPort.uptimeSeconds()).thenReturn(0L);
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
        when(hiveEventOutboxPort.getSummary())
                .thenReturn(new HiveOutboxSummary(0, 0, null));
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
        when(hiveGatewayPort.isAuthorizationFailure(any())).thenReturn(false);

        service = new HiveConnectionService(
                hiveBootstrapSettingsPort,
                runtimeConfigService,
                hiveBootstrapConfigSynchronizer,
                hiveSessionStateStore,
                hiveControlInboxService,
                hiveControlCommandDispatcher,
                hiveGatewayPort,
                hiveEventOutboxPort,
                hiveControlChannelPort,
                hiveRuntimeMetadataPort,
                channelRuntimePort,
                Clock.fixed(Instant.parse("2026-03-18T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldJoinWithManualJoinCodeAndPersistSession() {
        when(hiveGatewayPort.registerGolem(
                eq("https://hive.example.com"),
                eq("token-id.secret"),
                eq("Builder"),
                eq("lab-a"),
                eq("dev"),
                eq("dev"),
                anySet())).thenReturn(new HiveAuthSession(
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
        verify(hiveGatewayPort).sendHeartbeat(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eq("connected"), eq("Hive join completed"), eq(null), anyLong());
        verify(hiveControlChannelPort).connect(any(HiveSessionState.class), any());
        verify(hiveSessionStateStore).save(any(HiveSessionState.class));
        verify(runtimeConfigService).updateRuntimeConfig(any(RuntimeConfig.class));
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
        when(hiveGatewayPort.rotateSession("https://hive.example.com", "golem-1", "refresh"))
                .thenReturn(new HiveAuthSession(
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
        verify(hiveGatewayPort).sendHeartbeat(eq("https://hive.example.com"), eq("golem-1"), eq("access-new"),
                eq("connected"), eq("Hive reconnect completed"), eq(null), anyLong());
        verify(hiveControlChannelPort).connect(any(HiveSessionState.class), any());
        verify(runtimeConfigService, never()).updateRuntimeConfig(any(RuntimeConfig.class));
    }

    @Test
    void shouldUseManagedJoinCodeWhenManagedModeIsActive() {
        when(runtimeConfigService.isHiveManagedByProperties()).thenReturn(true);
        when(hiveBootstrapConfigSynchronizer.getManagedJoinCode())
                .thenReturn("managed.secret:https://hive.example.com");
        when(hiveGatewayPort.registerGolem(
                eq("https://hive.example.com"),
                eq("managed.secret"),
                eq("Builder"),
                eq("lab-a"),
                eq("dev"),
                eq("dev"),
                anySet())).thenReturn(new HiveAuthSession(
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
        when(hiveGatewayPort.rotateSession("https://hive.example.com", "golem-1", "refresh"))
                .thenReturn(new HiveAuthSession(
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
        verify(hiveGatewayPort, never()).registerGolem(any(), any(), any(), any(), any(), any(), anySet());
        verify(hiveGatewayPort).rotateSession("https://hive.example.com", "golem-1", "refresh");
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
        verify(hiveGatewayPort, never()).registerGolem(any(), any(), any(), any(), any(), any(), anySet());
        verify(hiveGatewayPort, never()).rotateSession(any(), any(), any());
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
        controlChannelStatus.set(new HiveControlChannelStatusSnapshot(
                "CONNECTED",
                Instant.parse("2026-03-18T00:00:00Z"),
                null,
                null,
                null,
                0));

        service.runHeartbeatMaintenanceCycle();

        verify(hiveGatewayPort).sendHeartbeat(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eq("connected"), eq("Control channel connected"), eq(null), anyLong());
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
        controlChannelStatus.set(new HiveControlChannelStatusSnapshot(
                "CONNECTED",
                Instant.parse("2026-03-18T00:00:00Z"),
                null,
                null,
                null,
                0));
        when(hiveGatewayPort.rotateSession("https://hive.example.com", "golem-1", "refresh-old"))
                .thenReturn(new HiveAuthSession(
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
        verify(hiveGatewayPort).rotateSession("https://hive.example.com", "golem-1", "refresh-old");
        verify(hiveControlChannelPort).disconnect("token-refresh");
        verify(hiveGatewayPort).sendHeartbeat(eq("https://hive.example.com"), eq("golem-1"), eq("access-new"),
                eq("degraded"), eq("Waiting for control channel reconnect"), eq(null), anyLong());
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
        controlChannelStatus.set(HiveControlChannelStatusSnapshot.disconnected());

        service.runControlChannelMaintenanceCycle();

        verify(hiveControlChannelPort).connect(any(HiveSessionState.class), any());
        assertEquals("CONNECTED", service.getStatus().state());
    }

    @Test
    void shouldRecordReceivedControlCommandFromChannelCallback() {
        when(hiveGatewayPort.registerGolem(
                eq("https://hive.example.com"),
                eq("token-id.secret"),
                eq("Builder"),
                eq("lab-a"),
                eq("dev"),
                eq("dev"),
                anySet())).thenReturn(new HiveAuthSession(
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

}
