package me.golemcore.bot.domain.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveSsoServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private HiveSessionStateStore hiveSessionStateStore;
    private HiveGatewayPort hiveGatewayPort;
    private HiveSsoService service;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        hiveSessionStateStore = mock(HiveSessionStateStore.class);
        hiveGatewayPort = mock(HiveGatewayPort.class);
        when(runtimeConfigService.getHiveConfig()).thenReturn(RuntimeConfig.HiveConfig.builder()
                .ssoEnabled(true)
                .dashboardBaseUrl("https://bot.example.com/dashboard")
                .build());
        when(hiveSessionStateStore.load()).thenReturn(Optional.of(HiveSessionState.builder()
                .serverUrl("https://hive.example.com")
                .golemId("golem_1")
                .build()));
        service = new HiveSsoService(runtimeConfigService, hiveSessionStateStore, hiveGatewayPort);
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
        when(runtimeConfigService.getHiveConfig()).thenReturn(RuntimeConfig.HiveConfig.builder()
                .ssoEnabled(true)
                .dashboardBaseUrl("https://bot.example.com")
                .build());

        HiveSsoService.HiveSsoStatus status = service.getStatus();

        assertEquals(
                "https://hive.example.com/api/v1/oauth2/authorize?response_type=code&client_id=golem_1&redirect_uri=https%3A%2F%2Fbot.example.com%2Fdashboard%2Fapi%2Fauth%2Fhive%2Fcallback",
                status.loginUrl());
    }

    @Test
    void shouldReportUnavailableWhenSsoDisabled() {
        when(runtimeConfigService.getHiveConfig()).thenReturn(RuntimeConfig.HiveConfig.builder()
                .ssoEnabled(false)
                .dashboardBaseUrl("https://bot.example.com/dashboard")
                .build());

        HiveSsoService.HiveSsoStatus status = service.getStatus();

        assertFalse(status.available());
        assertEquals("Hive SSO is disabled in bot settings", status.reason());
    }

    @Test
    void shouldExchangeCodeThroughHiveGateway() {
        when(hiveGatewayPort.exchangeSsoCode(
                "https://hive.example.com",
                "code-1",
                "golem_1",
                "https://bot.example.com/dashboard/api/auth/hive/callback"))
                .thenReturn(new HiveSsoTokenResponse("access", "admin", "Hive Admin", java.util.List.of("ADMIN")));

        HiveSsoTokenResponse response = service.exchange("code-1");

        assertEquals("access", response.accessToken());
    }

    @Test
    void shouldRejectExchangeWhenDashboardBaseUrlMissing() {
        when(runtimeConfigService.getHiveConfig()).thenReturn(RuntimeConfig.HiveConfig.builder()
                .ssoEnabled(true)
                .build());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.exchange("code-1"));

        assertEquals("Bot dashboard public URL is not configured", error.getMessage());
    }
}
