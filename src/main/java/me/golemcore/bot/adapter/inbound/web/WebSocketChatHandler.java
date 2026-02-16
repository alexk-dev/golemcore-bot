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

            if (text == null || text.isBlank()) {
                return;
            }

            Message message = Message.builder()
                    .id(UUID.randomUUID().toString())
                    .role("user")
                    .content(text)
                    .channelType("web")
                    .chatId(sessionId)
                    .senderId(username)
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
}
