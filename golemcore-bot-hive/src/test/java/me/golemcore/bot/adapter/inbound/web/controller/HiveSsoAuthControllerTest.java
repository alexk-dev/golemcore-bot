package me.golemcore.bot.adapter.inbound.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import me.golemcore.bot.client.dto.HiveSsoExchangeRequest;
import me.golemcore.bot.domain.model.hive.HiveSsoTokenResponse;
import me.golemcore.bot.domain.service.HiveSsoService;
import me.golemcore.bot.port.outbound.DashboardFederatedAuthPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

class HiveSsoAuthControllerTest {

    private HiveSsoService hiveSsoService;
    private DashboardFederatedAuthPort dashboardFederatedAuthPort;
    private HiveSsoAuthController controller;

    @BeforeEach
    void setUp() {
        hiveSsoService = mock(HiveSsoService.class);
        dashboardFederatedAuthPort = mock(DashboardFederatedAuthPort.class);
        controller = new HiveSsoAuthController(hiveSsoService, dashboardFederatedAuthPort);
    }

    @Test
    void shouldReturnHiveSsoStatus() {
        when(hiveSsoService.getStatus()).thenReturn(new HiveSsoService.HiveSsoStatus(
                true,
                true,
                "https://hive.example.com/api/v1/oauth2/authorize",
                null));

        StepVerifier.create(controller.hiveSsoStatus())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertTrue(response.getBody().isAvailable());
                })
                .verifyComplete();
    }

    @Test
    void shouldExchangeHiveSsoCode() {
        HiveSsoTokenResponse tokenResponse = new HiveSsoTokenResponse(
                "hive-access",
                "admin",
                "Hive Admin",
                java.util.List.of("ADMIN"));
        DashboardFederatedAuthPort.DashboardFederatedPrincipal principal = new DashboardFederatedAuthPort.DashboardFederatedPrincipal(
                "admin",
                "Hive Admin",
                java.util.List.of("ADMIN"));
        DashboardFederatedAuthPort.DashboardSessionTokens tokens = new DashboardFederatedAuthPort.DashboardSessionTokens(
                "local-access",
                "local-refresh");
        when(hiveSsoService.exchange("code-1", "verifier-1")).thenReturn(tokenResponse);
        when(dashboardFederatedAuthPort.issueDashboardTokensForPrincipal(principal)).thenReturn(tokens);

        HiveSsoExchangeRequest request = new HiveSsoExchangeRequest();
        request.setCode("code-1");
        request.setCodeVerifier("verifier-1");

        StepVerifier.create(controller.exchangeHiveSso(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("local-access", response.getBody().getAccessToken());
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectHiveSsoOperatorWithoutDashboardTokens() {
        HiveSsoTokenResponse tokenResponse = new HiveSsoTokenResponse(
                "hive-access",
                "viewer",
                "Hive Viewer",
                java.util.List.of("VIEWER"));
        DashboardFederatedAuthPort.DashboardFederatedPrincipal principal = new DashboardFederatedAuthPort.DashboardFederatedPrincipal(
                "viewer",
                "Hive Viewer",
                java.util.List.of("VIEWER"));
        when(hiveSsoService.exchange("code-1", "verifier-1")).thenReturn(tokenResponse);
        when(dashboardFederatedAuthPort.issueDashboardTokensForPrincipal(principal)).thenReturn(null);

        HiveSsoExchangeRequest request = new HiveSsoExchangeRequest();
        request.setCode("code-1");
        request.setCodeVerifier("verifier-1");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.exchangeHiveSso(request));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        assertEquals("Hive SSO operator is not allowed", ex.getReason());
    }
}
