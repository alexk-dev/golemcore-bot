package me.golemcore.bot.adapter.inbound.web.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.security.JwtTokenProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

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
@Slf4j
public class TerminalWebSocketHandler implements WebSocketHandler {

    private static final int DEFAULT_COLS = 120;
    private static final int DEFAULT_ROWS = 32;
    private static final String[] DEFAULT_SHELL = resolveDefaultShell();
    private static final long OUTBOUND_COMPLETE_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;
    private final TerminalSessionLimiter limiter;
    private final BotProperties botProperties;

    public TerminalWebSocketHandler(
            JwtTokenProvider jwtTokenProvider,
            ObjectMapper objectMapper,
            TerminalSessionLimiter limiter,
            BotProperties botProperties) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
        this.limiter = limiter;
        this.botProperties = botProperties;
    }

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
        Path workingDirectory = resolveWorkingDirectory(session);
        log.info(
                "[TerminalWS] Connection established: user={}, connectionId={}, cwd={}",
                username,
                connectionId,
                workingDirectory);

        Sinks.Many<String> outboundSink = Sinks.many().unicast().onBackpressureBuffer();

        TerminalConnection connection;
        try {
            connection = TerminalConnection.open(
                    DEFAULT_SHELL,
                    DEFAULT_COLS,
                    DEFAULT_ROWS,
                    workingDirectory,
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
                .doFinally(signal -> {
                    // The PTY reader may still be emitting output when the inbound side ends.
                    // Close it first, then complete the outbound stream so session.send(...)
                    // cannot hang behind a failed concurrent sink completion.
                    connection.close();
                    completeOutbound(outboundSink);
                });

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

    static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    void completeOutbound(Sinks.Many<String> outboundSink) {
        long deadline = System.nanoTime() + OUTBOUND_COMPLETE_RETRY_NANOS;
        Sinks.EmitResult result;
        do {
            result = outboundSink.tryEmitComplete();
            if (result == Sinks.EmitResult.OK
                    || result == Sinks.EmitResult.FAIL_TERMINATED
                    || result == Sinks.EmitResult.FAIL_CANCELLED) {
                return;
            }
            if (result != Sinks.EmitResult.FAIL_NON_SERIALIZED) {
                log.debug("[TerminalWS] Failed to complete outbound stream: {}", result);
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        } while (System.nanoTime() < deadline);
        log.debug("[TerminalWS] Failed to complete outbound stream after retry: {}", result);
    }

    private String extractQueryParam(WebSocketSession session, String key) {
        URI uri = session.getHandshakeInfo().getUri();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int separator = pair.indexOf('=');
            String rawName = separator >= 0 ? pair.substring(0, separator) : pair;
            if (key.equals(decodeQueryComponent(rawName))) {
                String rawValue = separator >= 0 ? pair.substring(separator + 1) : "";
                return decodeQueryComponent(rawValue);
            }
        }
        return null;
    }

    private String decodeQueryComponent(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    /**
     * Resolves the optional {@code cwd} query parameter from the websocket
     * handshake into a real workspace directory.
     *
     * <p>
     * The parameter is always interpreted relative to the configured shell
     * workspace. Traversal attempts, absolute paths, non-directories, missing
     * paths, and symlinks escaping the workspace all fall back to the workspace
     * root.
     */
    Path resolveWorkingDirectory(WebSocketSession session) {
        return resolveWorkingDirectory(extractQueryParam(session, "cwd"));
    }

    private Path resolveWorkingDirectory(String rawCwd) {
        Path workspaceRoot = Paths.get(botProperties.getTools().getShell().getWorkspace())
                .toAbsolutePath()
                .normalize();
        Path realWorkspace = prepareWorkspaceRoot(workspaceRoot);
        if (rawCwd == null || rawCwd.isBlank()) {
            return realWorkspace;
        }

        Path relativePath;
        try {
            relativePath = Paths.get(rawCwd);
        } catch (InvalidPathException e) {
            log.warn("[TerminalWS] Ignoring invalid cwd query parameter: {}", rawCwd);
            return realWorkspace;
        }
        if (relativePath.isAbsolute()) {
            return realWorkspace;
        }

        Path requestedPath = workspaceRoot.resolve(relativePath).normalize();
        if (!requestedPath.startsWith(workspaceRoot)) {
            return realWorkspace;
        }

        try {
            Path realRequestedPath = requestedPath.toRealPath();
            if (!realRequestedPath.startsWith(realWorkspace) || !Files.isDirectory(realRequestedPath)) {
                return realWorkspace;
            }
            return realRequestedPath;
        } catch (IOException e) {
            log.warn("[TerminalWS] Falling back to workspace root for cwd={}: {}", rawCwd, e.getMessage());
            return realWorkspace;
        }
    }

    private Path prepareWorkspaceRoot(Path workspaceRoot) {
        try {
            Files.createDirectories(workspaceRoot);
            return workspaceRoot.toRealPath();
        } catch (IOException e) {
            log.warn(
                    "[TerminalWS] Could not prepare workspace root {}; using normalized path: {}",
                    workspaceRoot,
                    e.getMessage());
            return workspaceRoot;
        }
    }

    private static String[] resolveDefaultShell() {
        return resolveDefaultShell(System.getenv("SHELL"));
    }

    static String[] resolveDefaultShell(String shell) {
        if (shell == null || shell.isBlank()) {
            return new String[] { "/bin/sh" };
        }
        return new String[] { shell, "-l" };
    }
}
