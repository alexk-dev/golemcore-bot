package me.golemcore.bot.adapter.inbound.web.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.reactivestreams.Publisher;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TerminalWebSocketHandlerTest {

    private BotProperties botProperties;
    private JwtTokenProvider tokenProvider;
    private TerminalSessionLimiter limiter;
    private TerminalWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        botProperties = new BotProperties();
        botProperties.getDashboard()
                .setJwtSecret("test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm");
        botProperties.getDashboard().getTerminal().setEnabled(true);
        botProperties.getDashboard().getTerminal().setMaxSessionsPerUser(1);
        botProperties.getDashboard().getTerminal().setIdleTimeout(Duration.ofMillis(100));
        botProperties.getDashboard().getTerminal().setMaxSessionDuration(Duration.ofSeconds(2));

        tokenProvider = new JwtTokenProvider(botProperties);
        tokenProvider.init();

        limiter = new TerminalSessionLimiter(botProperties);
        handler = new TerminalWebSocketHandler(tokenProvider, new ObjectMapper(), limiter);
    }

    @Test
    void shouldRejectWhenTerminalFeatureDisabled() {
        botProperties.getDashboard().getTerminal().setEnabled(false);
        String accessToken = tokenProvider.generateAccessToken("admin");
        WebSocketSession session = mockSession("ws://localhost/ws/terminal?token=" + accessToken);
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(session).close();
        assertEquals(0, limiter.activeCount("admin"), "disabled feature should not register any lease");
    }

    @Test
    void shouldRejectWhenTokenMissing() {
        WebSocketSession session = mockSession("ws://localhost/ws/terminal");
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(session).close();
    }

    @Test
    void shouldRejectWhenRefreshTokenProvided() {
        String refreshToken = tokenProvider.generateRefreshToken("admin");
        WebSocketSession session = mockSession("ws://localhost/ws/terminal?token=" + refreshToken);
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(session).close();
    }

    @Test
    void shouldRejectWhenPerUserCapReached() {
        // Pre-fill the single allowed slot for this user.
        limiter.tryAcquire("admin").orElseThrow();

        String accessToken = tokenProvider.generateAccessToken("admin");
        WebSocketSession session = mockSession("ws://localhost/ws/terminal?token=" + accessToken);
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(session).close();
        assertEquals(1, limiter.activeCount("admin"), "cap-rejected connection must not consume a new slot");
    }

    @Test
    void shouldReleaseLeaseOnClose() {
        String accessToken = tokenProvider.generateAccessToken("admin");
        WebSocketSession session = mockStreamingSession(
                "ws://localhost/ws/terminal?token=" + accessToken);

        StepVerifier.create(handler.handle(session))
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        awaitLeaseReleased("admin");
        assertEquals(0, limiter.activeCount("admin"), "lease should be released after connection completes");
    }

    @Test
    void shouldCompleteWhenIdleTimeoutElapses() {
        String accessToken = tokenProvider.generateAccessToken("admin");
        // Force immediate idle: inbound never emits anything.
        WebSocketSession session = mockStreamingSession(
                "ws://localhost/ws/terminal?token=" + accessToken,
                Flux.never());

        StepVerifier.create(handler.handle(session))
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        awaitLeaseReleased("admin");
        assertEquals(0, limiter.activeCount("admin"));
    }

    private void awaitLeaseReleased(String username) {
        // The handler releases the lease from a reactor doFinally that may run on a
        // parallel scheduler thread, so by the time StepVerifier.verify() returns the
        // side effect can still be in flight. Poll briefly so the assertion observes
        // the post-finally state without depending on scheduler timing.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && limiter.activeCount(username) > 0) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private WebSocketSession mockSession(String uri) {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create(uri));
        return session;
    }

    private WebSocketSession mockStreamingSession(String uri) {
        return mockStreamingSession(uri, Flux.empty());
    }

    private WebSocketSession mockStreamingSession(String uri, Flux<WebSocketMessage> inbound) {
        WebSocketSession session = mockSession(uri);
        when(session.receive()).thenReturn(inbound);
        when(session.textMessage(anyString())).thenAnswer(invocation -> {
            String payload = invocation.getArgument(0, String.class);
            WebSocketMessage message = mock(WebSocketMessage.class);
            when(message.getPayloadAsText()).thenReturn(payload);
            return message;
        });
        when(session.send(ArgumentMatchers.<Publisher<WebSocketMessage>>any()))
                .thenAnswer(invocation -> {
                    Publisher<?> publisher = invocation.getArgument(0);
                    return Flux.from(publisher).cast(WebSocketMessage.class).then();
                });
        when(session.close()).thenReturn(Mono.empty());
        return session;
    }
}
