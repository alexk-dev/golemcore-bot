package me.golemcore.bot.adapter.inbound.web.security;

import java.util.List;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.DashboardPublicPathPort;
import me.golemcore.bot.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

class DashboardSecurityConfigTest {

    private JwtTokenProvider jwtTokenProvider;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        BotProperties properties = new BotProperties();
        properties.getDashboard().setEnabled(true);
        properties.getDashboard().setJwtSecret("test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm");
        properties.getDashboard().setJwtExpirationMinutes(30);
        properties.getDashboard().setRefreshExpirationDays(7);

        jwtTokenProvider = new JwtTokenProvider(properties);
        jwtTokenProvider.init();

        webTestClient = buildClient(List.of());
    }

    private WebTestClient buildClient(List<DashboardPublicPathPort> dashboardPublicPathPorts) {
        BotProperties properties = new BotProperties();
        properties.getDashboard().setEnabled(true);
        properties.getDashboard().setJwtSecret("test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm");
        properties.getDashboard().setJwtExpirationMinutes(30);
        properties.getDashboard().setRefreshExpirationDays(7);

        JwtAuthenticationFilter jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtTokenProvider);
        DashboardSecurityConfig securityConfig = new DashboardSecurityConfig(
                properties,
                jwtAuthenticationFilter,
                dashboardPublicPathPorts);
        SecurityWebFilterChain securityWebFilterChain = securityConfig
                .securityWebFilterChain(ServerHttpSecurity.http());

        return WebTestClient.bindToController(new SecurityTestEndpointController())
                .webFilter(new WebFilterChainProxy(securityWebFilterChain))
                .configureClient()
                .build();
    }

    @Test
    void webhookEndpointsShouldBypassDashboardJwtRequirement() {
        webTestClient.post()
                .uri("/api/hooks/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer webhook-shared-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ok");
    }

    @Test
    void contributedPublicApiShouldBypassDashboardJwtRequirement() {
        DashboardPublicPathPort dashboardPublicPathPort = () -> List.of("/api/public/contributed");
        WebTestClient contributedClient = buildClient(List.of(dashboardPublicPathPort));

        contributedClient.get()
                .uri("/api/public/contributed")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ok");
    }

    @Test
    void genericApiShouldStillRequireDashboardJwt() {
        webTestClient.get()
                .uri("/api/secured/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer webhook-shared-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void genericApiShouldAcceptValidDashboardJwt() {
        String token = jwtTokenProvider.generateAccessToken("admin");

        webTestClient.get()
                .uri("/api/secured/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ok");
    }

    @RestController
    static class SecurityTestEndpointController {

        @PostMapping("/api/hooks/test")
        Mono<String> hook() {
            return Mono.just("ok");
        }

        @GetMapping("/api/secured/test")
        Mono<String> secured() {
            return Mono.just("ok");
        }

        @GetMapping("/api/public/contributed")
        Mono<String> contributed() {
            return Mono.just("ok");
        }
    }
}
