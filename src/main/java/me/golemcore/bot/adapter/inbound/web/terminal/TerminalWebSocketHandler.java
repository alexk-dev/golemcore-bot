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
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Reactive WebSocket handler that exposes a user-side shell via
 * {@link TerminalConnection}. The browser (xterm.js) connects with a JWT access
 * token and exchanges base64-wrapped {@code input}/{@code output}/{@code
 * resize}/{@code close}/{@code exit} frames.
 *
 * <p>
 * <b>Security model:</b> the dashboard is single-user, so any holder of a valid
 * access JWT is effectively the owner of the host. The terminal feature is
 * therefore gated behind an explicit opt-in flag
 * ({@code bot.dashboard.terminal.enabled}) and each session is bounded by a
 * per-user concurrency cap, an idle timeout, and an absolute session duration
 * enforced through {@link TerminalSessionLimiter}.
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
    private final TerminalSessionLimiter limiter;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        if (!limiter.isEnabled()) {
            log.warn("[TerminalWS] Connection rejected: terminal feature is disabled");
            return session.close();
        }

        String token = extractQueryParam(session, "token");
        if (token == null || !jwtTokenProvider.validateToken(token) || !jwtTokenProvider.isAccessToken(token)) {
            log.warn("[TerminalWS] Connection rejected: invalid or missing JWT");
            return session.close();
        }

        String username = jwtTokenProvider.getUsernameFromToken(token);
        Optional<TerminalSessionLimiter.Lease> leaseOpt = limiter.tryAcquire(username);
        if (leaseOpt.isEmpty()) {
            log.warn("[TerminalWS] Connection rejected: concurrency cap reached for user={}", username);
            return session.close();
        }
        TerminalSessionLimiter.Lease lease = leaseOpt.get();

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
            lease.release();
            return session.close();
        }

        Flux<WebSocketMessage> outbound = outboundSink.asFlux().map(session::textMessage);

        Duration idleTimeout = limiter.idleTimeout();
        Duration maxDuration = limiter.maxSessionDuration();

        Flux<WebSocketMessage> inboundFlux = session.receive()
                .doOnNext(wsMessage -> connection.handleFrame(wsMessage.getPayloadAsText()));
        if (isPositive(idleTimeout)) {
            inboundFlux = inboundFlux
                    .timeout(idleTimeout)
                    .onErrorResume(TimeoutException.class, e -> {
                        log.info(
                                "[TerminalWS] Idle timeout elapsed: connectionId={}, idle={}",
                                connectionId,
                                idleTimeout);
                        return Flux.empty();
                    });
        }
        if (isPositive(maxDuration)) {
            inboundFlux = inboundFlux.take(maxDuration);
        }
        Mono<Void> inbound = inboundFlux
                .then()
                .doFinally(signal -> outboundSink.tryEmitComplete());

        return session.send(outbound)
                .and(inbound)
                .doFinally(signal -> {
                    log.info(
                            "[TerminalWS] Connection closed: connectionId={}, signal={}",
                            connectionId,
                            signal);
                    connection.close();
                    lease.release();
                });
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
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
