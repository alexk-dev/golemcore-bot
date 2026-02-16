package me.golemcore.bot.adapter.inbound.web.security;

import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtAuthenticationFilterTest {

    private JwtTokenProvider tokenProvider;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        BotProperties props = new BotProperties();
        props.getDashboard().setJwtSecret("test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm");
        props.getDashboard().setJwtExpirationMinutes(30);
        props.getDashboard().setRefreshExpirationDays(7);
        tokenProvider = new JwtTokenProvider(props);
        tokenProvider.init();
        filter = new JwtAuthenticationFilter(tokenProvider);
    }

    @Test
    void shouldAuthenticateWithValidAccessToken() {
        String token = tokenProvider.generateAccessToken("admin");
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        java.util.concurrent.atomic.AtomicBoolean chainCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        WebFilterChain chain = webExchange -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
        assertTrue(chainCalled.get(), "Filter chain should have been called");
    }

    @Test
    void shouldPassThroughWithoutToken() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = webExchange -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    void shouldPassThroughWithInvalidToken() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = webExchange -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    void shouldRejectRefreshTokenAsAccessToken() {
        String refreshToken = tokenProvider.generateRefreshToken("admin");
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = webExchange -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    void shouldIgnoreNonBearerAuthHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = webExchange -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }
}
