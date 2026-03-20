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
import me.golemcore.bot.adapter.outbound.hive.HiveApiClient;
import me.golemcore.bot.adapter.outbound.hive.HiveControlChannelClient;
import me.golemcore.bot.adapter.outbound.hive.HiveControlChannelStatus;
import me.golemcore.bot.adapter.outbound.hive.HiveEventOutboxService;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.RuntimeConfig;
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
    private HiveApiClient hiveApiClient;
    private HiveEventOutboxService hiveEventOutboxService;
    private HiveControlChannelClient hiveControlChannelClient;
    private ChannelPort webPort;
    private AtomicReference<Optional<HiveSessionState>> storedSession;
    private AtomicReference<HiveControlChannelStatus> controlChannelStatus;
    private HiveConnectionService service;

    @BeforeEach
    void setUp() {
        botProperties = new BotProperties();
        runtimeConfigService = mock(RuntimeConfigService.class);
        hiveBootstrapConfigSynchronizer = mock(HiveBootstrapConfigSynchronizer.class);
        hiveSessionStateStore = mock(HiveSessionStateStore.class);
        hiveControlInboxService = mock(HiveControlInboxService.class);
        hiveControlCommandDispatcher = mock(HiveControlCommandDispatcher.class);
        hiveApiClient = mock(HiveApiClient.class);
        hiveEventOutboxService = mock(HiveEventOutboxService.class);
        hiveControlChannelClient = mock(HiveControlChannelClient.class);
        webPort = mock(ChannelPort.class);
        storedSession = new AtomicReference<>(Optional.empty());
        controlChannelStatus = new AtomicReference<>(HiveControlChannelStatus.disconnected());

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
            return null;
        }).when(hiveControlChannelClient).connect(any(HiveSessionState.class), any());
        doAnswer(invocation -> {
            controlChannelStatus.set(HiveControlChannelStatus.disconnected());
            return null;
        }).when(hiveControlChannelClient).disconnect(any());
        when(hiveBootstrapConfigSynchronizer.getManagedJoinCode()).thenReturn(null);

        service = new HiveConnectionService(
                botProperties,
                runtimeConfigService,
                hiveBootstrapConfigSynchronizer,
                hiveSessionStateStore,
                hiveControlInboxService,
                hiveControlCommandDispatcher,
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
                anySet())).thenReturn(new HiveApiClient.GolemAuthResponse(
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
                eq("connected"), eq("Hive join completed"), eq(null), anyLong());
        verify(hiveControlChannelClient).connect(any(HiveSessionState.class), any());
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
                eq("connected"), eq("Hive reconnect completed"), eq(null), anyLong());
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
                anySet())).thenReturn(new HiveApiClient.GolemAuthResponse(
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
        verify(hiveApiClient, never()).register(any(), any(), any(), any(), any(), any(), anySet());
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
        verify(hiveApiClient, never()).register(any(), any(), any(), any(), any(), any(), anySet());
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
        controlChannelStatus.set(HiveControlChannelStatus.disconnected());

        service.runControlChannelMaintenanceCycle();

        verify(hiveControlChannelClient).connect(any(HiveSessionState.class), any());
        assertEquals("CONNECTED", service.getStatus().state());
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
