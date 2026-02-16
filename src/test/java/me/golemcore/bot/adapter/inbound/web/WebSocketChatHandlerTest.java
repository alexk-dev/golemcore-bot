package me.golemcore.bot.adapter.inbound.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.adapter.inbound.web.security.JwtTokenProvider;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;

import org.springframework.web.reactive.socket.WebSocketMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketChatHandlerTest {

    private JwtTokenProvider tokenProvider;
    private WebChannelAdapter webChannelAdapter;
    private ObjectMapper objectMapper;
    private ApplicationEventPublisher eventPublisher;
    private WebSocketChatHandler handler;

    @BeforeEach
    void setUp() {
        BotProperties props = new BotProperties();
        props.getDashboard().setJwtSecret("test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm");
        tokenProvider = new JwtTokenProvider(props);
        tokenProvider.init();

        objectMapper = new ObjectMapper();
        eventPublisher = mock(ApplicationEventPublisher.class);
        webChannelAdapter = new WebChannelAdapter(objectMapper, eventPublisher);
        handler = new WebSocketChatHandler(tokenProvider, webChannelAdapter, objectMapper);
    }

    @Test
    void shouldRejectConnectionWithoutToken() {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat"));
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();
        verify(session).close();
    }

    @Test
    void shouldRejectConnectionWithInvalidToken() {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=invalid"));
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();
        verify(session).close();
    }

    @Test
    void shouldAcceptConnectionWithValidToken() {
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-1");
        when(session.receive()).thenReturn(Flux.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();
    }

    @Test
    void shouldHandleIncomingMessage() {
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-1");

        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn("{\"text\":\"hello\",\"sessionId\":\"chat-1\"}");

        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        verify(eventPublisher).publishEvent(any(AgentLoop.InboundMessageEvent.class));
    }

    @Test
    void shouldIgnoreBlankMessages() {
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-2");

        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn("{\"text\":\"\"}");

        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();
    }

    @Test
    void shouldHandleMalformedJson() {
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-3");

        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn("not json");

        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();
    }

    @Test
    void shouldRejectRefreshTokenForWebSocket() {
        String refreshToken = tokenProvider.generateRefreshToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + refreshToken));
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();
        verify(session).close();
    }
}
