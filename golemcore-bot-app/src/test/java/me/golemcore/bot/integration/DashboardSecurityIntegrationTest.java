package me.golemcore.bot.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardSecurityIntegrationTest extends GolemCoreBotIntegrationTestBase {

    @Test
    void shouldRejectProtectedApiWithoutJwt() {
        webTestClient().get()
                .uri("/api/settings/runtime")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldRejectProtectedApiWithInvalidJwt() {
        webTestClient().get()
                .uri("/api/settings/runtime")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldLoginAndAccessProtectedRuntimeSettings() {
        String accessToken = loginAndExtractAccessToken();

        authenticatedGet("/api/settings/runtime", accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.hive.enabled").exists()
                .jsonPath("$.plan.modelTier").isEqualTo(null);
    }

    @Test
    void shouldIssueRefreshCookieAndRefreshAccessToken() {
        WebTestClient.ResponseSpec loginResponse = webTestClient().post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"password\":\"" + ADMIN_PASSWORD + "\"}")
                .exchange()
                .expectStatus().isOk();
        String refreshCookie = loginResponse.returnResult(String.class)
                .getResponseHeaders()
                .getFirst(HttpHeaders.SET_COOKIE);

        assertNotNull(refreshCookie);
        assertTrue(refreshCookie.contains("refresh_token="));

        webTestClient().post()
                .uri("/api/auth/refresh")
                .header(HttpHeaders.COOKIE, refreshCookie)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isNotEmpty();
    }

    @Test
    void shouldRejectWrongDashboardPassword() {
        webTestClient().post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"password\":\"wrong\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
