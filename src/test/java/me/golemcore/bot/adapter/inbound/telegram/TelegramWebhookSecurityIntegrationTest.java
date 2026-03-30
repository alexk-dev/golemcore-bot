package me.golemcore.bot.adapter.inbound.telegram;

import me.golemcore.bot.adapter.inbound.web.security.DashboardSecurityConfig;
import me.golemcore.bot.adapter.inbound.web.security.JwtAuthenticationFilter;
import me.golemcore.bot.adapter.inbound.web.security.JwtTokenProvider;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.plugin.runtime.TelegramWebhookUpdateBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelegramWebhookSecurityIntegrationTest {

    private RuntimeConfigService runtimeConfigService;
    private TelegramWebhookUpdateBridge updateBridge;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        BotProperties properties = new BotProperties();
        properties.getDashboard().setEnabled(true);
        properties.getDashboard().setJwtSecret("test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm");
        properties.getDashboard().setJwtExpirationMinutes(30);
        properties.getDashboard().setRefreshExpirationDays(7);

        runtimeConfigService = mock(RuntimeConfigService.class);
        updateBridge = mock(TelegramWebhookUpdateBridge.class);

        TelegramWebhookController controller = new TelegramWebhookController(runtimeConfigService, updateBridge);
        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(properties);
        jwtTokenProvider.init();
        JwtAuthenticationFilter jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtTokenProvider);
        DashboardSecurityConfig securityConfig = new DashboardSecurityConfig(properties, jwtAuthenticationFilter);
        SecurityWebFilterChain securityWebFilterChain = securityConfig
                .securityWebFilterChain(ServerHttpSecurity.http());

        webTestClient = WebTestClient.bindToController(controller)
                .webFilter(new WebFilterChainProxy(securityWebFilterChain))
                .configureClient()
                .build();
    }

    @Test
    void telegramWebhookShouldBypassDashboardJwtRequirementWhenSecretMatches() {
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .telegram(RuntimeConfig.TelegramConfig.builder()
                        .enabled(true)
                        .webhookSecretToken("telegram-secret")
                        .build())
                .build());
        doReturn(CompletableFuture.completedFuture(null)).when(updateBridge)
                .dispatch(org.mockito.ArgumentMatchers.any());

        webTestClient.post()
                .uri("/api/telegram/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Telegram-Bot-Api-Secret-Token", "telegram-secret")
                .bodyValue("{\"update_id\":123}")
                .exchange()
                .expectStatus().isOk();
    }
}
