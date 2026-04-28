package me.golemcore.bot.adapter.inbound.webhook;

import me.golemcore.bot.domain.tracing.TraceService;
import me.golemcore.bot.adapter.inbound.web.security.DashboardSecurityConfig;
import me.golemcore.bot.adapter.inbound.web.security.JwtAuthenticationFilter;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.memory.MemoryPresetService;
import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.security.JwtTokenProvider;
import me.golemcore.bot.security.InputSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookHttpIntegrationTest {

    private static final String TOKEN = "test-token-value";
    private static final String JSON_BODY = """
            {"text":"Deploy finished","chatId":"webhook:ci"}
            """;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        BotProperties properties = new BotProperties();
        properties.getDashboard().setEnabled(true);
        properties.getDashboard().setJwtSecret("test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm");
        properties.getDashboard().setJwtExpirationMinutes(30);
        properties.getDashboard().setRefreshExpirationDays(7);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getPreferences()).thenReturn(UserPreferences.builder()
                .webhooks(UserPreferences.WebhookConfig.builder()
                        .enabled(true)
                        .token(Secret.of(TOKEN))
                        .mappings(List.of(UserPreferences.HookMapping.builder()
                                .name("test")
                                .action("wake")
                                .authMode("bearer")
                                .build()))
                        .build())
                .build());

        WebhookController controller = new WebhookController(
                preferencesService,
                new WebhookAuthenticator(preferencesService),
                mock(WebhookChannelAdapter.class),
                new WebhookPayloadTransformer(),
                mock(WebhookDeliveryTracker.class),
                mock(WebhookResponseSchemaService.class),
                new MemoryPresetService(),
                mock(ApplicationEventPublisher.class),
                mock(me.golemcore.bot.port.outbound.SessionPort.class),
                mock(TraceService.class),
                new InputSanitizer());

        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(properties);
        jwtTokenProvider.init();
        JwtAuthenticationFilter jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtTokenProvider);
        DashboardSecurityConfig securityConfig = new DashboardSecurityConfig(
                properties,
                jwtAuthenticationFilter,
                List.of());
        SecurityWebFilterChain securityWebFilterChain = securityConfig
                .securityWebFilterChain(ServerHttpSecurity.http());

        webTestClient = WebTestClient.bindToController(controller)
                .webFilter(new WebFilterChainProxy(securityWebFilterChain))
                .configureClient()
                .build();
    }

    @Test
    void customHookShouldReturn401ForWrongTokenWhenPayloadIsValid() {
        webTestClient.post()
                .uri("/api/hooks/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(JSON_BODY)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void customHookShouldAcceptCorrectTokenWhenPayloadIsValid() {
        webTestClient.post()
                .uri("/api/hooks/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(JSON_BODY)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void customHookShouldAcceptAlternativeTokenHeader() {
        webTestClient.post()
                .uri("/api/hooks/test")
                .header("X-Golemcore-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(JSON_BODY)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void wakeShouldReturn401BeforeBodyValidationWhenTokenIsWrong() {
        webTestClient.post()
                .uri("/api/hooks/wake")
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void agentShouldReturn401BeforeBodyValidationWhenTokenIsWrong() {
        webTestClient.post()
                .uri("/api/hooks/agent")
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
