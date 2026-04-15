package me.golemcore.bot.adapter.inbound.web.terminal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Bridges a {@link TerminalSession} with a text-frame protocol suitable for
 * WebSocket transport. Incoming frames are JSON objects tagged with a {@code
 * type} discriminator ({@code input}, {@code resize}, {@code close}); outgoing
 * frames use the same shape ({@code output}, {@code exit}, {@code error}).
 * Payload bytes are base64-encoded on both sides so that binary PTY output can
 * travel through text frames unchanged.
 */
@Slf4j
public final class TerminalConnection {

    private static final String TYPE_INPUT = "input";
    private static final String TYPE_RESIZE = "resize";
    private static final String TYPE_CLOSE = "close";
    private static final String TYPE_OUTPUT = "output";
    private static final String TYPE_EXIT = "exit";
    private static final String TYPE_ERROR = "error";
    private static final long EXIT_POLL_SECONDS = 5L;

    /**
     * Single-shot outcome from an exit-wait poll. Implementations must return
     * {@link #TIMEOUT} when the process is still alive and an instance from
     * {@link #exited(int)} when the process has terminated.
     */
    public static final class ExitWaitOutcome {
        public static final ExitWaitOutcome TIMEOUT = new ExitWaitOutcome(false, 0);

        private final boolean terminated;
        private final int exitCode;

        private ExitWaitOutcome(boolean terminated, int exitCode) {
            this.terminated = terminated;
            this.exitCode = exitCode;
        }

        public static ExitWaitOutcome exited(int exitCode) {
            return new ExitWaitOutcome(true, exitCode);
        }

        public boolean isTerminated() {
            return terminated;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    /**
     * Poll-style seam that lets the exit-watcher loop sleep in short chunks rather
     * than relying on a single large {@code waitFor(..., DAYS)} call.
     */
    @FunctionalInterface
    public interface ExitWaitStrategy {
        ExitWaitOutcome awaitExit() throws InterruptedException;
    }

    private final ObjectMapper objectMapper;
    private final Consumer<String> outboundSink;
    private final TerminalSession session;
    private final ExitWatcher exitWatcher;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private TerminalConnection(
            ObjectMapper objectMapper,
            Consumer<String> outboundSink,
            TerminalSession session) {
        this.objectMapper = objectMapper;
        this.outboundSink = outboundSink;
        this.session = session;
        this.exitWatcher = new ExitWatcher(defaultStrategy(session), this::emitExit);
    }

    public static TerminalConnection open(
            String[] command,
            int cols,
            int rows,
            ObjectMapper objectMapper,
            Consumer<String> outboundSink) throws IOException {
        TerminalConnection[] connectionRef = new TerminalConnection[1];
        TerminalSession session = TerminalSession.start(
                command,
                cols,
                rows,
                bytes -> {
                    TerminalConnection self = connectionRef[0];
                    if (self != null) {
                        self.emitOutput(bytes);
                    }
                });
        TerminalConnection connection = new TerminalConnection(objectMapper, outboundSink, session);
        connectionRef[0] = connection;
        connection.exitWatcher.start();
        return connection;
    }

    private static ExitWaitStrategy defaultStrategy(TerminalSession session) {
        return () -> {
            if (session.waitFor(EXIT_POLL_SECONDS, TimeUnit.SECONDS)) {
                Integer code = session.exitCode();
                return code == null
                        ? ExitWaitOutcome.TIMEOUT
                        : ExitWaitOutcome.exited(code);
            }
            return ExitWaitOutcome.TIMEOUT;
        };
    }

    public void handleFrame(String frame) {
        if (closed.get() || frame == null || frame.isBlank()) {
            return;
        }
        Map<String, Object> parsed = tryParse(frame);
        if (parsed == null) {
            return;
        }
        Object typeValue = parsed.get("type");
        if (!(typeValue instanceof String type)) {
            return;
        }
        switch (type) {
        case TYPE_INPUT -> handleInput(parsed);
        case TYPE_RESIZE -> handleResize(parsed);
        case TYPE_CLOSE -> close();
        default -> log.debug("[Terminal] Ignoring unknown frame type: {}", type);
        }
    }

    public boolean isAlive() {
        return !closed.get() && session.isAlive();
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        exitWatcher.stop();
        session.close();
    }

    private void handleInput(Map<String, Object> parsed) {
        Object data = parsed.get("data");
        if (!(data instanceof String encoded)) {
            return;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            session.writeInput(decoded);
        } catch (IllegalArgumentException e) {
            emitError("invalid base64 in input frame");
        } catch (IOException e) {
            emitError("failed to write to pty: " + e.getMessage());
        }
    }

    private void handleResize(Map<String, Object> parsed) {
        Integer cols = asInt(parsed.get("cols"));
        Integer rows = asInt(parsed.get("rows"));
        if (cols == null || rows == null || cols <= 0 || rows <= 0) {
            return;
        }
        session.resize(cols, rows);
    }

    private void emitOutput(byte[] bytes) {
        if (closed.get()) {
            return;
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", TYPE_OUTPUT);
        frame.put("data", Base64.getEncoder().encodeToString(bytes));
        emit(frame);
    }

    private void emitError(String message) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", TYPE_ERROR);
        frame.put("message", message);
        emit(frame);
    }

    private void emitExit(int code) {
        if (closed.get()) {
            return;
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", TYPE_EXIT);
        frame.put("code", code);
        emit(frame);
    }

    private void emit(Map<String, Object> frame) {
        try {
            outboundSink.accept(objectMapper.writeValueAsString(frame));
        } catch (JsonProcessingException e) {
            log.warn("[Terminal] Failed to serialize outbound frame: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("[Terminal] Outbound sink threw: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tryParse(String frame) {
        try {
            return objectMapper.readValue(frame, Map.class);
        } catch (IOException e) {
            log.debug("[Terminal] Ignoring malformed frame: {}", e.getMessage());
            return null;
        }
    }

    private Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }
}
