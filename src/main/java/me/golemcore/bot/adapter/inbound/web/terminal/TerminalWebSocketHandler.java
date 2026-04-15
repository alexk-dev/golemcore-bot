package me.golemcore.bot.adapter.inbound.web.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.infrastructure.security.JwtTokenProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

/**
 * Reactive WebSocket handler that exposes a user-side shell via
 * {@link TerminalConnection}. The browser (xterm.js) connects with a JWT access
 * token and exchanges base64-wrapped {@code input}/{@code output}/{@code
 * resize}/{@code close}/{@code exit} frames.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TerminalWebSocketHandler implements WebSocketHandler {

    private static final int DEFAULT_COLS = 120;
    private static final int DEFAULT_ROWS = 32;
    private static final String[] DEFAULT_SHELL = resolveDefaultShell();

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String token = extractQueryParam(session, "token");
        if (token == null || !jwtTokenProvider.validateToken(token) || !jwtTokenProvider.isAccessToken(token)) {
            log.warn("[TerminalWS] Connection rejected: invalid or missing JWT");
            return session.close();
        }

        String username = jwtTokenProvider.getUsernameFromToken(token);
        String connectionId = UUID.randomUUID().toString();
        log.info("[TerminalWS] Connection established: user={}, connectionId={}", username, connectionId);

        Sinks.Many<String> outboundSink = Sinks.many().unicast().onBackpressureBuffer();

        TerminalConnection connection;
        try {
            connection = TerminalConnection.open(
                    DEFAULT_SHELL,
                    DEFAULT_COLS,
                    DEFAULT_ROWS,
                    objectMapper,
                    frame -> outboundSink.tryEmitNext(frame));
        } catch (IOException e) {
            log.warn("[TerminalWS] Failed to start pty: {}", e.getMessage());
            return session.close();
        }

        Flux<WebSocketMessage> outbound = outboundSink.asFlux().map(session::textMessage);

        Mono<Void> inbound = session.receive()
                .doOnNext(wsMessage -> connection.handleFrame(wsMessage.getPayloadAsText()))
                .then();

        return session.send(outbound)
                .and(inbound)
                .doFinally(signal -> {
                    log.info(
                            "[TerminalWS] Connection closed: connectionId={}, signal={}",
                            connectionId,
                            signal);
                    connection.close();
                    outboundSink.tryEmitComplete();
                });
    }

    private String extractQueryParam(WebSocketSession session, String key) {
        URI uri = session.getHandshakeInfo().getUri();
        String query = uri.getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        return UriComponentsBuilder.newInstance()
                .query(query)
                .build()
                .getQueryParams()
                .getFirst(key);
    }

    private static String[] resolveDefaultShell() {
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank()) {
            return new String[] { "/bin/sh" };
        }
        return new String[] { shell, "-l" };
    }
}
