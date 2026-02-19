package me.golemcore.bot.adapter.inbound.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.adapter.inbound.web.dto.LogEntryDto;
import me.golemcore.bot.adapter.inbound.web.logstream.DashboardLogService;
import me.golemcore.bot.adapter.inbound.web.security.JwtTokenProvider;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketLogsHandlerTest {

    private JwtTokenProvider tokenProvider;
    private DashboardLogService dashboardLogService;
    private ObjectMapper objectMapper;
    private WebSocketLogsHandler handler;

    @BeforeEach
    void setUp() {
        BotProperties props = new BotProperties();
        props.getDashboard().setJwtSecret("test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm");
        tokenProvider = new JwtTokenProvider(props);
        tokenProvider.init();

        dashboardLogService = mock(DashboardLogService.class);
        objectMapper = new ObjectMapper();
        handler = new WebSocketLogsHandler(tokenProvider, dashboardLogService, objectMapper);
    }

    @Test
    void shouldRejectConnectionWhenTokenMissing() {
        WebSocketSession session = mockSession("ws://localhost/ws/logs");
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        verify(session).close();
        verify(dashboardLogService, never()).streamAfter(anyLong());
    }

    @Test
    void shouldRejectConnectionWhenRefreshTokenProvided() {
        String refreshToken = tokenProvider.generateRefreshToken("admin");
        WebSocketSession session = mockSession("ws://localhost/ws/logs?token=" + refreshToken);
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        verify(session).close();
        verify(dashboardLogService, never()).streamAfter(anyLong());
    }

    @Test
    void shouldFallbackToZeroWhenAfterSeqInvalid() {
        String accessToken = tokenProvider.generateAccessToken("admin");
        WebSocketSession session = mockStreamingSession(
                "ws://localhost/ws/logs?token=" + accessToken + "&afterSeq=invalid");
        when(dashboardLogService.streamAfter(0L)).thenReturn(Flux.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        verify(dashboardLogService).streamAfter(0L);
    }

    @Test
    void shouldStreamLogBatchWithExpectedPayload() throws Exception {
        String accessToken = tokenProvider.generateAccessToken("admin");
        List<String> sentPayloads = new ArrayList<>();
        WebSocketSession session = mockStreamingSession(
                "ws://localhost/ws/logs?token=" + accessToken + "&afterSeq=5",
                sentPayloads);

        LogEntryDto first = LogEntryDto.builder()
                .seq(6L)
                .timestamp("2026-02-19T00:00:00Z")
                .level("INFO")
                .logger("me.golemcore.bot.Test")
                .thread("main")
                .message("first")
                .build();
        LogEntryDto second = LogEntryDto.builder()
                .seq(7L)
                .timestamp("2026-02-19T00:00:01Z")
                .level("WARN")
                .logger("me.golemcore.bot.Test")
                .thread("main")
                .message("second")
                .build();
        when(dashboardLogService.streamAfter(5L)).thenReturn(Flux.just(first, second));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        verify(dashboardLogService).streamAfter(5L);
        assertEquals(1, sentPayloads.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(sentPayloads.get(0), Map.class);
        assertEquals("log_batch", payload.get("type"));

        Object rawItems = payload.get("items");
        assertTrue(rawItems instanceof List<?>);
        List<?> items = (List<?>) rawItems;
        assertEquals(2, items.size());
        assertTrue(items.get(0) instanceof Map<?, ?>);
        Map<?, ?> firstItem = (Map<?, ?>) items.get(0);
        assertEquals(6, ((Number) firstItem.get("seq")).intValue());
        assertEquals("first", firstItem.get("message"));
    }

    private WebSocketSession mockSession(String uri) {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create(uri));
        return session;
    }

    private WebSocketSession mockStreamingSession(String uri) {
        return mockStreamingSession(uri, null);
    }

    private WebSocketSession mockStreamingSession(String uri, List<String> sentPayloads) {
        WebSocketSession session = mockSession(uri);
        when(session.receive()).thenReturn(Flux.empty());
        when(session.textMessage(anyString())).thenAnswer(invocation -> {
            String payload = invocation.getArgument(0, String.class);
            WebSocketMessage message = mock(WebSocketMessage.class);
            when(message.getPayloadAsText()).thenReturn(payload);
            return message;
        });
        when(session.send(any(Publisher.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Publisher<WebSocketMessage> publisher = invocation.getArgument(0, Publisher.class);
            return Flux.from(publisher)
                    .doOnNext(msg -> {
                        if (sentPayloads != null) {
                            sentPayloads.add(msg.getPayloadAsText());
                        }
                    })
                    .then();
        });
        return session;
    }
}
