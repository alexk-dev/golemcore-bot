package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import me.golemcore.bot.domain.model.hive.HiveAuthSession;
import me.golemcore.bot.domain.model.hive.HiveCapabilitySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveGatewayAdapterTest {

    private HiveApiClient hiveApiClient;
    private HiveGatewayAdapter adapter;

    @BeforeEach
    void setUp() {
        hiveApiClient = mock(HiveApiClient.class);
        adapter = new HiveGatewayAdapter(hiveApiClient);
    }

    @Test
    void shouldMapRegisterResponseToDomainSession() {
        when(hiveApiClient.register(
                "https://hive.example.com",
                "enroll.secret",
                "Builder",
                "lab-a",
                "1.2.3",
                "abc123",
                Set.of("web"),
                HiveCapabilitySnapshot.builder().build())).thenReturn(new HiveApiClient.GolemAuthResponse(
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

        HiveAuthSession session = adapter.registerGolem(
                "https://hive.example.com",
                "enroll.secret",
                "Builder",
                "lab-a",
                "1.2.3",
                "abc123",
                Set.of("web"));

        assertEquals("golem-1", session.golemId());
        assertEquals("access", session.accessToken());
        assertEquals("refresh", session.refreshToken());
        assertEquals("/ws/golems/control", session.controlChannelUrl());
        assertEquals(30, session.heartbeatIntervalSeconds());
    }

    @Test
    void shouldMapRotateResponseToDomainSession() {
        when(hiveApiClient.rotate("https://hive.example.com", "golem-1", "refresh")).thenReturn(
                new HiveApiClient.GolemAuthResponse(
                        "golem-1",
                        "access-2",
                        "refresh-2",
                        Instant.parse("2026-03-18T00:10:00Z"),
                        Instant.parse("2026-03-19T00:10:00Z"),
                        "hive",
                        "golems",
                        "/ws/golems/control",
                        45,
                        List.of("golems:heartbeat")));

        HiveAuthSession session = adapter.rotateSession("https://hive.example.com", "golem-1", "refresh");

        assertEquals("access-2", session.accessToken());
        assertEquals("refresh-2", session.refreshToken());
        assertEquals(45, session.heartbeatIntervalSeconds());
    }

    @Test
    void shouldDelegateHeartbeat() {
        adapter.sendHeartbeat(
                "https://hive.example.com",
                "golem-1",
                "access",
                "connected",
                "healthy",
                null,
                42L);

        verify(hiveApiClient).heartbeat(
                "https://hive.example.com",
                "golem-1",
                "access",
                "connected",
                "healthy",
                null,
                42L,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void shouldClassifyAuthorizationFailures() {
        assertTrue(adapter.isAuthorizationFailure(new HiveApiClient.HiveApiException(401, "unauthorized")));
        assertTrue(adapter.isAuthorizationFailure(new HiveApiClient.HiveApiException(403, "forbidden")));
        assertFalse(adapter.isAuthorizationFailure(new HiveApiClient.HiveApiException(500, "boom")));
        assertFalse(adapter.isAuthorizationFailure(new IllegalStateException("boom")));
    }
}
