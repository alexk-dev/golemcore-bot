package me.golemcore.bot.domain.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.hive.HiveSsoTokenResponse;
import me.golemcore.bot.port.outbound.HiveGatewayPort;
import me.golemcore.bot.port.outbound.RuntimeConfigQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveSsoServiceTest {

    private RuntimeConfigQueryPort runtimeConfigQueryPort;
    private HiveSessionStateStore hiveSessionStateStore;
    private HiveGatewayPort hiveGatewayPort;
    private HiveSsoService service;

    @BeforeEach
    void setUp() {
        runtimeConfigQueryPort = mock(RuntimeConfigQueryPort.class);
        hiveSessionStateStore = mock(HiveSessionStateStore.class);
        hiveGatewayPort = mock(HiveGatewayPort.class);
        when(runtimeConfigQueryPort.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder()
                        .ssoEnabled(true)
                        .dashboardBaseUrl("https://bot.example.com/dashboard")
                        .build())
                .build());
        when(hiveSessionStateStore.load()).thenReturn(Optional.of(HiveSessionState.builder()
                .serverUrl("https://hive.example.com")
                .golemId("golem_1")
                .build()));
        service = new HiveSsoService(runtimeConfigQueryPort, hiveSessionStateStore, hiveGatewayPort);
    }

    @Test
    void shouldBuildAuthorizeUrlWhenHiveSsoIsAvailable() {
        HiveSsoService.HiveSsoStatus status = service.getStatus();

        assertTrue(status.available());
        assertEquals(
                "https://hive.example.com/api/v1/oauth2/authorize?response_type=code&client_id=golem_1&redirect_uri=https%3A%2F%2Fbot.example.com%2Fdashboard%2Fapi%2Fauth%2Fhive%2Fcallback",
                status.loginUrl());
    }

    @Test
    void shouldAppendDashboardPathWhenMissingFromPublicUrl() {
        when(runtimeConfigQueryPort.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder()
                        .ssoEnabled(true)
                        .dashboardBaseUrl("https://bot.example.com")
                        .build())
                .build());

        HiveSsoService.HiveSsoStatus status = service.getStatus();

        assertEquals(
                "https://hive.example.com/api/v1/oauth2/authorize?response_type=code&client_id=golem_1&redirect_uri=https%3A%2F%2Fbot.example.com%2Fdashboard%2Fapi%2Fauth%2Fhive%2Fcallback",
                status.loginUrl());
    }

    @Test
    void shouldReportUnavailableWhenSsoDisabled() {
        when(runtimeConfigQueryPort.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder()
                        .ssoEnabled(false)
                        .dashboardBaseUrl("https://bot.example.com/dashboard")
                        .build())
                .build());

        HiveSsoService.HiveSsoStatus status = service.getStatus();

        assertFalse(status.available());
        assertEquals("Hive SSO is disabled in bot settings", status.reason());
    }

    @Test
    void shouldReportUnavailableWhenSessionIsMissing() {
        when(hiveSessionStateStore.load()).thenReturn(Optional.empty());

        HiveSsoService.HiveSsoStatus status = service.getStatus();

        assertFalse(status.available());
        assertEquals("Hive session is not connected", status.reason());
    }

    @Test
    void shouldReportUnavailableWhenDashboardBaseUrlMissing() {
        when(runtimeConfigQueryPort.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder()
                        .ssoEnabled(true)
                        .build())
                .build());

        HiveSsoService.HiveSsoStatus status = service.getStatus();

        assertFalse(status.available());
        assertEquals("Bot dashboard public URL is not configured", status.reason());
    }

    @Test
    void shouldExchangeCodeThroughHiveGateway() {
        when(hiveGatewayPort.exchangeSsoCode(
                "https://hive.example.com",
                "code-1",
                "golem_1",
                "https://bot.example.com/dashboard/api/auth/hive/callback",
                "verifier-1"))
                .thenReturn(new HiveSsoTokenResponse("access", "admin", "Hive Admin", java.util.List.of("ADMIN")));

        HiveSsoTokenResponse response = service.exchange("code-1", "verifier-1");

        assertEquals("access", response.accessToken());
    }

    @Test
    void shouldRejectExchangeWhenSsoDisabled() {
        when(runtimeConfigQueryPort.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder()
                        .ssoEnabled(false)
                        .dashboardBaseUrl("https://bot.example.com/dashboard")
                        .build())
                .build());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.exchange("code-1", "verifier-1"));

        assertEquals("Hive SSO is disabled in bot settings", error.getMessage());
    }

    @Test
    void shouldRejectExchangeWhenDashboardBaseUrlMissing() {
        when(runtimeConfigQueryPort.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder()
                        .ssoEnabled(true)
                        .build())
                .build());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.exchange("code-1", "verifier-1"));

        assertEquals("Bot dashboard public URL is not configured", error.getMessage());
    }
}
