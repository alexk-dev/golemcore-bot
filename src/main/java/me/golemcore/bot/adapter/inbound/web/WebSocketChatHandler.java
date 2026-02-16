package me.golemcore.bot.adapter.inbound.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.web.security.JwtTokenProvider;
import me.golemcore.bot.domain.model.Message;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Reactive WebSocket handler for dashboard chat. Handles JSON messages: {
 * "text": "...", "sessionId": "..." } Delegates to WebChannelAdapter for
 * message routing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketChatHandler implements WebSocketHandler {

    private static final int MAX_IMAGE_ATTACHMENTS = 6;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    private final JwtTokenProvider jwtTokenProvider;
    private final WebChannelAdapter webChannelAdapter;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String token = extractToken(session);
        if (token == null || !jwtTokenProvider.validateToken(token) || !jwtTokenProvider.isAccessToken(token)) {
            log.warn("[WebSocket] Connection rejected: invalid or missing JWT");
            return session.close();
        }

        String username = jwtTokenProvider.getUsernameFromToken(token);
        String connectionId = UUID.randomUUID().toString();
        log.info("[WebSocket] Connection established: user={}, connectionId={}", username, connectionId);

        webChannelAdapter.registerSession(connectionId, session);

        return session.receive()
                .doOnNext(wsMessage -> handleIncoming(wsMessage, connectionId, username))
                .doFinally(signal -> {
                    log.info("[WebSocket] Connection closed: connectionId={}, signal={}", connectionId, signal);
                    webChannelAdapter.deregisterSession(connectionId);
                })
                .then();
    }

    private void handleIncoming(WebSocketMessage wsMessage, String connectionId, String username) {
        try {
            String payload = wsMessage.getPayloadAsText();
            @SuppressWarnings("unchecked")
            Map<String, Object> json = objectMapper.readValue(payload, Map.class);

            String text = (String) json.get("text");
            String sessionId = (String) json.getOrDefault("sessionId", connectionId);
            List<Map<String, Object>> attachments = extractImageAttachments(json.get("attachments"));

            if ((text == null || text.isBlank()) && attachments.isEmpty()) {
                return;
            }

            Map<String, Object> metadata = null;
            if (!attachments.isEmpty()) {
                metadata = Map.of("attachments", attachments);
            }

            Message message = Message.builder()
                    .id(UUID.randomUUID().toString())
                    .role("user")
                    .content(text != null ? text : "")
                    .channelType("web")
                    .chatId(sessionId)
                    .senderId(username)
                    .metadata(metadata)
                    .timestamp(Instant.now())
                    .build();

            webChannelAdapter.handleIncomingMessage(message, connectionId);
        } catch (Exception e) { // NOSONAR
            log.warn("[WebSocket] Failed to process incoming message: {}", e.getMessage());
        }
    }

    private String extractToken(WebSocketSession session) {
        URI uri = session.getHandshakeInfo().getUri();
        String query = uri.getQuery();
        if (query != null) {
            return UriComponentsBuilder.newInstance()
                    .query(query)
                    .build()
                    .getQueryParams()
                    .getFirst("token");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractImageAttachments(Object rawAttachments) {
        List<Map<String, Object>> result = new ArrayList<>();

        if (!(rawAttachments instanceof List<?> attachments)) {
            return result;
        }

        for (Object attachmentObj : attachments) {
            if (result.size() >= MAX_IMAGE_ATTACHMENTS) {
                break;
            }
            if (!(attachmentObj instanceof Map<?, ?> attachmentMap)) {
                continue;
            }

            String type = asString(attachmentMap.get("type"));
            String mimeType = asString(attachmentMap.get("mimeType"));
            String dataBase64 = asString(attachmentMap.get("dataBase64"));
            String name = asString(attachmentMap.get("name"));

            if (!"image".equals(type) || mimeType == null || !mimeType.startsWith("image/")
                    || dataBase64 == null || dataBase64.isBlank()) {
                continue;
            }

            try {
                byte[] decoded = Base64.getDecoder().decode(dataBase64);
                if (decoded.length == 0 || decoded.length > MAX_IMAGE_BYTES) {
                    continue;
                }
            } catch (IllegalArgumentException ex) {
                continue;
            }

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("type", "image");
            normalized.put("mimeType", mimeType);
            normalized.put("dataBase64", dataBase64);
            normalized.put("name", name != null ? name : "image");
            result.add(normalized);
        }

        return result;
    }

    private String asString(Object value) {
        if (value instanceof String stringValue) {
            return stringValue;
        }
        return null;
    }
}
