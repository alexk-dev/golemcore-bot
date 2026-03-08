package me.golemcore.bot.adapter.inbound.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebChannelAdapterRuntimeEventTest {

    private WebChannelAdapter adapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        adapter = new WebChannelAdapter(objectMapper, eventPublisher);
    }

    @Test
    void shouldSerializeRuntimeEventPayload() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        WebSocketMessage webSocketMessage = mock(WebSocketMessage.class);
        when(session.isOpen()).thenReturn(true);
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.textMessage(anyString())).thenReturn(webSocketMessage);

        adapter.registerSession("chat-rt", session);

        RuntimeEvent event = RuntimeEvent.builder()
                .type(RuntimeEventType.RETRY_STARTED)
                .timestamp(Instant.parse("2026-03-01T00:00:00Z"))
                .sessionId("s1")
                .channelType("web")
                .chatId("chat-rt")
                .payload(Map.of("attempt", 2, "delayMs", 300))
                .build();

        adapter.sendRuntimeEvent("chat-rt", event).join();

        org.mockito.ArgumentCaptor<String> payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(session).textMessage(payloadCaptor.capture());
        Map<?, ?> payload = objectMapper.readValue(payloadCaptor.getValue(), Map.class);
        assertEquals("system_event", payload.get("type"));
        assertEquals("runtime_event", payload.get("eventType"));
        assertEquals("RETRY_STARTED", payload.get("runtimeEventType"));
        assertEquals("2026-03-01T00:00:00Z", payload.get("runtimeEventTimestamp"));
        assertEquals("chat-rt", payload.get("sessionId"));
        assertNotNull(payload.get("runtimeEventPayload"));
    }

    @Test
    void shouldSkipRuntimeEventWhenChatIdBlank() {
        RuntimeEvent event = RuntimeEvent.builder()
                .type(RuntimeEventType.TURN_STARTED)
                .timestamp(Instant.now())
                .payload(Map.of())
                .build();

        adapter.sendRuntimeEvent("  ", event).join();
    }

    @Test
    void shouldSkipRuntimeEventWhenSessionNotFound() {
        RuntimeEvent event = RuntimeEvent.builder()
                .type(RuntimeEventType.TURN_STARTED)
                .timestamp(Instant.now())
                .payload(Map.of())
                .build();

        adapter.sendRuntimeEvent("missing-chat", event).join();
    }
}
