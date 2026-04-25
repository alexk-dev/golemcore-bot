package me.golemcore.bot.adapter.inbound.telegram;

import me.golemcore.bot.adapter.inbound.web.security.DashboardSecurityConfig;
import me.golemcore.bot.adapter.inbound.web.security.JwtAuthenticationFilter;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.security.JwtTokenProvider;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebHandler;

class TelegramWebhookSecurityIntegrationTest {

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        BotProperties properties = new BotProperties();
        properties.getDashboard().setEnabled(true);
        properties.getDashboard().setJwtSecret("test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm");
        properties.getDashboard().setJwtExpirationMinutes(30);
        properties.getDashboard().setRefreshExpirationDays(7);

        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(properties);
        jwtTokenProvider.init();
        JwtAuthenticationFilter jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtTokenProvider);
        DashboardSecurityConfig securityConfig = new DashboardSecurityConfig(
                properties,
                jwtAuthenticationFilter,
                List.of());
        SecurityWebFilterChain securityWebFilterChain = securityConfig
                .securityWebFilterChain(ServerHttpSecurity.http());

        WebHandler okHandler = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        webTestClient = WebTestClient.bindToWebHandler(okHandler)
                .webFilter(new WebFilterChainProxy(securityWebFilterChain))
                .configureClient()
                .build();
    }

    @Test
    void telegramWebhookShouldBypassDashboardJwtRequirement() {
        webTestClient.post()
                .uri("/api/telegram/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Telegram-Bot-Api-Secret-Token", "telegram-secret")
                .bodyValue("{\"update_id\":123}")
                .exchange()
                .expectStatus().isOk();
    }
}
