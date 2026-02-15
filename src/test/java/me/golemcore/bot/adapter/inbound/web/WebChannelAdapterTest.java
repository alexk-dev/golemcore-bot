package me.golemcore.bot.adapter.inbound.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebChannelAdapterTest {

    private WebChannelAdapter adapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new WebChannelAdapter(objectMapper);
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
    void shouldSetMessageHandler() {
        AtomicReference<Message> received = new AtomicReference<>();
        adapter.onMessage(received::set);

        Message message = Message.builder()
                .id("msg-1")
                .role("user")
                .content("hello")
                .channelType("web")
                .chatId("chat-1")
                .senderId("admin")
                .timestamp(Instant.now())
                .build();

        // Register a mock session for the chat
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.send(any())).thenReturn(Mono.empty());
        adapter.registerSession("conn-1", session);

        adapter.handleIncomingMessage(message);

        assertNotNull(received.get());
        assertEquals("hello", received.get().getContent());
        assertEquals("chat-1", received.get().getChatId());
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
    void shouldStopClearsSessions() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.close()).thenReturn(Mono.empty());

        adapter.registerSession("conn-1", session);
        adapter.start();
        adapter.stop();

        assertFalse(adapter.isRunning());
    }
}
