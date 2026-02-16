package me.golemcore.bot.adapter.inbound.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.inbound.ChannelPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Web ChannelPort adapter backed by reactive WebSocket sessions. Maintains a
 * registry of active WebSocket connections keyed by chatId.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebChannelAdapter implements ChannelPort {

    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** Maps connectionId -> chatId for reverse lookup */
    private final Map<String, String> connectionToChatId = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    @Override
    public String getChannelType() {
        return "web";
    }

    @Override
    public void start() {
        running = true;
        log.info("[WebChannel] Started");
    }

    @Override
    public void stop() {
        running = false;
        sessions.values().forEach(session -> session.close().subscribe());
        sessions.clear();
        connectionToChatId.clear();
        log.info("[WebChannel] Stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public CompletableFuture<Void> sendMessage(String chatId, String content) {
        return sendJsonToChat(chatId, Map.of(
                "type", "assistant_chunk",
                "text", content,
                "sessionId", chatId));
    }

    @Override
    public CompletableFuture<Void> sendMessage(String chatId, String content, Map<String, Object> hints) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "assistant_chunk");
        payload.put("text", content);
        payload.put("sessionId", chatId);
        if (hints != null && !hints.isEmpty()) {
            payload.put("hint", hints);
        }
        return sendJsonToChat(chatId, payload);
    }

    @Override
    public CompletableFuture<Void> sendMessage(Message message) {
        String chatId = message.getChatId();
        return sendJsonToChat(chatId, Map.of(
                "type", "assistant_done",
                "text", message.getContent() != null ? message.getContent() : "",
                "sessionId", chatId != null ? chatId : ""));
    }

    @Override
    public CompletableFuture<Void> sendVoice(String chatId, byte[] voiceData) {
        // Voice not supported over WebSocket — fallback handled upstream
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isAuthorized(String senderId) {
        // JWT already validated at WebSocket handshake
        return true;
    }

    @Override
    public void onMessage(Consumer<Message> handler) {
        // Not used — messages are published via ApplicationEventPublisher
    }

    @Override
    public void showTyping(String chatId) {
        sendJsonToChat(chatId, Map.of(
                "type", "system_event",
                "eventType", "typing",
                "sessionId", chatId));
    }

    public void registerSession(String connectionId, WebSocketSession session) {
        sessions.put(connectionId, session);
    }

    public void deregisterSession(String connectionId) {
        sessions.remove(connectionId);
        String chatId = connectionToChatId.remove(connectionId);
        if (chatId != null) {
            // Remove from chatId-based lookup too
            sessions.remove(chatId);
        }
    }

    public void handleIncomingMessage(Message message, String connectionId) {
        String chatId = message.getChatId();
        connectionToChatId.put(connectionId, chatId);
        WebSocketSession wsSession = sessions.get(connectionId);
        if (wsSession != null) {
            sessions.put(chatId, wsSession);
        }
        eventPublisher.publishEvent(new AgentLoop.InboundMessageEvent(message));
    }

    private CompletableFuture<Void> sendJsonToChat(String chatId, Map<String, Object> payload) {
        WebSocketSession session = sessions.get(chatId);
        if (session == null || !session.isOpen()) {
            log.debug("[WebChannel] No active session for chatId: {}", chatId);
            return CompletableFuture.completedFuture(null);
        }

        try {
            String json = objectMapper.writeValueAsString(payload);
            Mono<Void> sendMono = session.send(
                    Mono.just(session.textMessage(json)));
            sendMono.subscribe(
                    unused -> {
                    },
                    error -> log.warn("[WebChannel] Failed to send message to {}: {}", chatId, error.getMessage()));
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) { // NOSONAR
            log.warn("[WebChannel] Failed to serialize message for {}: {}", chatId, e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }
}
