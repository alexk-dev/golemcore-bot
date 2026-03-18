package me.golemcore.bot.adapter.inbound.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.domain.model.ProgressUpdateType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebChannelAdapterTest {

    private WebChannelAdapter adapter;
    private ObjectMapper objectMapper;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        eventPublisher = mock(ApplicationEventPublisher.class);
        adapter = new WebChannelAdapter(objectMapper, eventPublisher);
    }

    @Test
    void shouldReturnWebChannelType() {
        assertEquals("web", adapter.getChannelType());
    }

    @Test
    void shouldStartAndStop() {
        assertFalse(adapter.isRunning());
        adapter.start();
        assertTrue(adapter.isRunning());
        adapter.stop();
        assertFalse(adapter.isRunning());
    }

    @Test
    void shouldAlwaysAuthorize() {
        assertTrue(adapter.isAuthorized("anyone"));
    }

    @Test
    void shouldRegisterAndDeregisterSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.close()).thenReturn(Mono.empty());

        adapter.registerSession("conn-1", session);
        adapter.deregisterSession("conn-1");
        // No exception means success
    }

    @Test
    void shouldPublishEventOnIncomingMessage() {
        Message message = Message.builder()
                .id("msg-1")
                .role("user")
                .content("hello")
                .channelType("web")
                .chatId("chat-1")
                .senderId("admin")
                .timestamp(Instant.now())
                .build();

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.send(any())).thenReturn(Mono.empty());
        adapter.registerSession("conn-1", session);

        adapter.handleIncomingMessage(message, "conn-1");

        verify(eventPublisher).publishEvent(any(AgentLoop.InboundMessageEvent.class));
    }

    @Test
    void shouldSendMessageToNoSession() {
        CompletableFuture<Void> result = adapter.sendMessage("nonexistent-chat", "hello");
        assertNotNull(result);
        // Should complete without error
        result.join();
    }

    @Test
    void shouldSendVoiceReturnsCompleted() {
        CompletableFuture<Void> result = adapter.sendVoice("chat-1", new byte[] { 1, 2, 3 });
        assertNotNull(result);
        result.join();
    }

    @Test
    void shouldShowTypingWithNoSession() {
        // Should not throw
        adapter.showTyping("nonexistent-chat");
    }

    @Test
    void shouldSendMessageObjectToNoSession() {
        Message message = Message.builder()
                .chatId("nonexistent")
                .content("test")
                .build();
        CompletableFuture<Void> result = adapter.sendMessage(message);
        assertNotNull(result);
        result.join();
    }

    @Test
    void shouldHandleNullContentInMessage() {
        Message message = Message.builder()
                .chatId("chat-1")
                .build();
        CompletableFuture<Void> result = adapter.sendMessage(message);
        assertNotNull(result);
        result.join();
    }

    @Test
    void shouldSendMessageToActiveSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.textMessage(any(String.class)))
                .thenReturn(mock(org.springframework.web.reactive.socket.WebSocketMessage.class));

        adapter.registerSession("chat-1", session);
        CompletableFuture<Void> result = adapter.sendMessage("chat-1", "hello");
        assertNotNull(result);
        result.join();
    }

    @Test
    void shouldShowTypingToActiveSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.textMessage(any(String.class)))
                .thenReturn(mock(org.springframework.web.reactive.socket.WebSocketMessage.class));

        adapter.registerSession("chat-1", session);
        adapter.showTyping("chat-1");
    }

    @Test
    void shouldSendProgressUpdateToActiveSession() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.textMessage(any(String.class)))
                .thenReturn(mock(org.springframework.web.reactive.socket.WebSocketMessage.class));

        adapter.registerSession("chat-progress", session);
        adapter.sendProgressUpdate("chat-progress", new ProgressUpdate(
                ProgressUpdateType.SUMMARY,
                "Grouped the recent tool calls into one update.",
                Map.of("toolCount", 8)))
                .join();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(session).textMessage(payloadCaptor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(payloadCaptor.getValue(), Map.class);
        assertEquals("system_event", payload.get("type"));
        assertEquals("progress_update", payload.get("eventType"));
        assertEquals("summary", payload.get("progressType"));
        assertEquals("Grouped the recent tool calls into one update.", payload.get("text"));
    }

    @Test
    void shouldSendMessageObjectToActiveSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.textMessage(any(String.class)))
                .thenReturn(mock(org.springframework.web.reactive.socket.WebSocketMessage.class));

        adapter.registerSession("chat-2", session);
        Message message = Message.builder()
                .chatId("chat-2")
                .content("test response")
                .build();
        CompletableFuture<Void> result = adapter.sendMessage(message);
        assertNotNull(result);
        result.join();
    }

    @Test
    void shouldIncludeAssistantHintsWhenSendingCompletedMessage() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.textMessage(any(String.class)))
                .thenReturn(mock(org.springframework.web.reactive.socket.WebSocketMessage.class));

        adapter.registerSession("chat-3", session);
        Message message = Message.builder()
                .chatId("chat-3")
                .content("final answer")
                .metadata(new LinkedHashMap<>(Map.of(
                        "model", "gemini-3.1-flash-lite-preview",
                        "modelTier", "smart",
                        "reasoning", "medium")))
                .build();

        adapter.sendMessage(message).join();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(session).textMessage(payloadCaptor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(payloadCaptor.getValue(), Map.class);
        assertEquals("assistant_done", payload.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> hint = (Map<String, Object>) payload.get("hint");
        assertNotNull(hint);
        assertEquals("gemini-3.1-flash-lite-preview", hint.get("model"));
        assertEquals("smart", hint.get("tier"));
        assertEquals("medium", hint.get("reasoning"));
    }

    @Test
    void shouldOmitAssistantHintsWhenMetadataHasNoUsableValues() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.textMessage(any(String.class)))
                .thenReturn(mock(org.springframework.web.reactive.socket.WebSocketMessage.class));

        adapter.registerSession("chat-4", session);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model", "   ");
        metadata.put("modelTier", 42);
        metadata.put("reasoning", "");

        Message message = Message.builder()
                .chatId("chat-4")
                .content("final answer")
                .metadata(metadata)
                .build();

        adapter.sendMessage(message).join();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(session).textMessage(payloadCaptor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(payloadCaptor.getValue(), Map.class);
        assertEquals("assistant_done", payload.get("type"));
        assertFalse(payload.containsKey("hint"));
    }

    @Test
    void shouldHandleClosedSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(false);

        adapter.registerSession("chat-1", session);
        CompletableFuture<Void> result = adapter.sendMessage("chat-1", "hello");
        assertNotNull(result);
        result.join();
    }

    @Test
    void shouldStopClearsSessions() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.close()).thenReturn(Mono.empty());

        adapter.registerSession("conn-1", session);
        adapter.start();
        adapter.stop();

        assertFalse(adapter.isRunning());
    }

    @Test
    void shouldCleanupAllChatRoutesBoundToConnectionOnDisconnect() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.textMessage(any(String.class)))
                .thenReturn(mock(org.springframework.web.reactive.socket.WebSocketMessage.class));

        adapter.registerSession("conn-1", session);

        Message first = Message.builder()
                .id("msg-1")
                .role("user")
                .content("hello-a")
                .channelType("web")
                .chatId("chat-a")
                .senderId("admin")
                .timestamp(Instant.now())
                .build();
        Message second = Message.builder()
                .id("msg-2")
                .role("user")
                .content("hello-b")
                .channelType("web")
                .chatId("chat-b")
                .senderId("admin")
                .timestamp(Instant.now())
                .build();

        adapter.handleIncomingMessage(first, "conn-1");
        adapter.handleIncomingMessage(second, "conn-1");

        adapter.sendMessage("chat-a", "before-disconnect").join();
        adapter.sendMessage("chat-b", "before-disconnect").join();
        verify(session, times(2)).send(any());

        adapter.deregisterSession("conn-1");

        adapter.sendMessage("chat-a", "after-disconnect").join();
        adapter.sendMessage("chat-b", "after-disconnect").join();
        verify(session, times(2)).send(any());
    }
}
