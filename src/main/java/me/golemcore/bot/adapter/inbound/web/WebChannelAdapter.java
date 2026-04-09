package me.golemcore.bot.adapter.inbound.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.port.inbound.ChannelPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    private static final String KEY_TYPE = "type";
    private static final String KEY_SESSION_ID = "sessionId";
    private static final String KEY_TEXT = "text";
    private static final String VALUE_ASSISTANT_CHUNK = "assistant_chunk";
    private static final String VALUE_ASSISTANT_DONE = "assistant_done";
    private static final String VALUE_SYSTEM_EVENT = "system_event";

    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** Maps connectionId -> all chatIds bound during socket lifetime. */
    private final Map<String, Set<String>> connectionToChatIds = new ConcurrentHashMap<>();
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
        connectionToChatIds.clear();
        log.info("[WebChannel] Stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public CompletableFuture<Void> sendMessage(String chatId, String content) {
        return sendJsonToChat(chatId, Map.of(
                KEY_TYPE, VALUE_ASSISTANT_CHUNK,
                KEY_TEXT, content,
                KEY_SESSION_ID, chatId));
    }

    @Override
    public CompletableFuture<Void> sendMessage(String chatId, String content, Map<String, Object> hints) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(KEY_TYPE, VALUE_ASSISTANT_CHUNK);
        payload.put(KEY_TEXT, content);
        payload.put(KEY_SESSION_ID, chatId);
        if (hints != null && !hints.isEmpty()) {
            payload.put("hint", hints);
        }
        return sendJsonToChat(chatId, payload);
    }

    @Override
    public CompletableFuture<Void> sendMessage(Message message) {
        String chatId = message.getChatId();
        if (StringValueSupport.isBlank(chatId)) {
            log.debug("[WebChannel] Skip anonymous message event without chatId");
            return CompletableFuture.completedFuture(null);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(KEY_TYPE, VALUE_ASSISTANT_DONE);
        payload.put(KEY_TEXT, message.getContent() != null ? message.getContent() : "");
        payload.put(KEY_SESSION_ID, chatId);
        Map<String, Object> hint = extractHint(message);
        if (!hint.isEmpty()) {
            payload.put("hint", hint);
        }
        List<Map<String, Object>> attachments = extractAttachments(message);
        if (!attachments.isEmpty()) {
            payload.put("attachments", attachments);
        }
        return sendJsonToChat(chatId, payload);
    }

    @Override
    public CompletableFuture<Void> sendVoice(String chatId, byte[] voiceData) {
        // Voice not supported over WebSocket — fallback handled upstream
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendRuntimeEvent(String chatId, RuntimeEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(KEY_TYPE, VALUE_SYSTEM_EVENT);
        payload.put("eventType", "runtime_event");
        payload.put("runtimeEventType", event.type().name());
        payload.put("runtimeEventTimestamp", event.timestamp().toString());
        payload.put("runtimeEventPayload", event.payload());
        payload.put(KEY_SESSION_ID, chatId);
        return sendJsonToChat(chatId, payload);
    }

    @Override
    public CompletableFuture<Void> sendProgressUpdate(String chatId, ProgressUpdate update) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(KEY_TYPE, VALUE_SYSTEM_EVENT);
        payload.put("eventType", "progress_update");
        payload.put("progressType", update.type().name().toLowerCase(java.util.Locale.ROOT));
        payload.put(KEY_TEXT, update.text());
        payload.put("progressMetadata", update.metadata());
        payload.put(KEY_SESSION_ID, chatId);
        return sendJsonToChat(chatId, payload);
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
                KEY_TYPE, VALUE_SYSTEM_EVENT,
                "eventType", "typing",
                KEY_SESSION_ID, chatId));
    }

    public void registerSession(String connectionId, WebSocketSession session) {
        sessions.put(connectionId, session);
    }

    public void deregisterSession(String connectionId) {
        WebSocketSession disconnectedSession = sessions.remove(connectionId);
        Set<String> chatIds = connectionToChatIds.remove(connectionId);
        if (chatIds == null || chatIds.isEmpty() || disconnectedSession == null) {
            return;
        }
        for (String chatId : chatIds) {
            WebSocketSession mappedSession = sessions.get(chatId);
            if (Objects.equals(mappedSession, disconnectedSession)) {
                sessions.remove(chatId, disconnectedSession);
            }
        }
    }

    public void bindConnectionToChatId(String connectionId, String chatId) {
        if (StringValueSupport.isBlank(connectionId) || StringValueSupport.isBlank(chatId)) {
            return;
        }
        WebSocketSession wsSession = sessions.get(connectionId);
        if (wsSession == null) {
            return;
        }
        connectionToChatIds.computeIfAbsent(connectionId, key -> ConcurrentHashMap.newKeySet()).add(chatId);
        sessions.put(chatId, wsSession);
    }

    public boolean hasActiveSession(String chatId) {
        if (StringValueSupport.isBlank(chatId)) {
            return false;
        }
        WebSocketSession session = sessions.get(chatId);
        return session != null && session.isOpen();
    }

    @Override
    public boolean supportsProactiveMessage(String chatId) {
        return isRunning() && hasActiveSession(chatId);
    }

    public void handleIncomingMessage(Message message, String connectionId) {
        bindConnectionToChatId(connectionId, message.getChatId());
        eventPublisher.publishEvent(new AgentLoop.InboundMessageEvent(message));
    }

    private CompletableFuture<Void> sendJsonToChat(String chatId, Map<String, Object> payload) {
        if (StringValueSupport.isBlank(chatId)) {
            log.debug("[WebChannel] Skip send without chatId");
            return CompletableFuture.completedFuture(null);
        }
        Object payloadSessionId = payload != null ? payload.get(KEY_SESSION_ID) : null;
        if (!(payloadSessionId instanceof String) || StringValueSupport.isBlank((String) payloadSessionId)) {
            log.debug("[WebChannel] Skip anonymous websocket payload without sessionId");
            return CompletableFuture.completedFuture(null);
        }

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

    private Map<String, Object> extractHint(Message message) {
        Map<String, Object> hint = new LinkedHashMap<>();
        if (message == null || message.getMetadata() == null) {
            return hint;
        }

        copyHintValue(message, hint, "model");
        copyHintValue(message, hint, "modelTier", "tier");
        copyHintValue(message, hint, "reasoning");
        return hint;
    }

    private void copyHintValue(Message message, Map<String, Object> hint, String sourceKey) {
        copyHintValue(message, hint, sourceKey, sourceKey);
    }

    private void copyHintValue(Message message, Map<String, Object> hint, String sourceKey, String targetKey) {
        Object value = message.getMetadata().get(sourceKey);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            hint.put(targetKey, stringValue);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractAttachments(Message message) {
        if (message == null || message.getMetadata() == null) {
            return List.of();
        }
        Object attachmentsRaw = message.getMetadata().get("attachments");
        if (!(attachmentsRaw instanceof List<?> attachments) || attachments.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> normalized = new java.util.ArrayList<>();
        for (Object attachmentObj : attachments) {
            if (!(attachmentObj instanceof Map<?, ?> attachmentMap)) {
                continue;
            }
            Map<String, Object> next = new LinkedHashMap<>();
            copyAttachmentValue(attachmentMap, next, "type");
            copyAttachmentValue(attachmentMap, next, "name");
            copyAttachmentValue(attachmentMap, next, "mimeType");
            copyAttachmentValue(attachmentMap, next, "url");
            copyAttachmentValue(attachmentMap, next, "internalFilePath");
            copyAttachmentValue(attachmentMap, next, "thumbnailBase64");
            copyAttachmentValue(attachmentMap, next, "caption");
            if (!next.isEmpty()) {
                normalized.add(next);
            }
        }
        return normalized;
    }

    private void copyAttachmentValue(Map<?, ?> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            target.put(key, stringValue);
        }
    }
}
