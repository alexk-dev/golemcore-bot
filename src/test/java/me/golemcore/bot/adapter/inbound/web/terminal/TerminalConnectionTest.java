package me.golemcore.bot.adapter.inbound.web.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalConnectionTest {

    private static final String[] FAKE_COMMAND = new String[] { "test-shell" };
    private static final long WAIT_TIMEOUT_SECONDS = 10L;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldEmitBase64OutputWhenPtyProducesData() throws Exception {
        List<String> outbound = new ArrayList<>();
        CompletableFuture<Void> readySignal = new CompletableFuture<>();

        TerminalConnection connection = openFakeConnection(
                120,
                30,
                frame -> {
                    synchronized (outbound) {
                        outbound.add(frame);
                        if (frame.contains("\"type\":\"output\"")) {
                            readySignal.complete(null);
                        }
                    }
                });

        try {
            connection.handleFrame(inputFrame("echo __GC_READY__\n"));
            readySignal.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            String combined;
            synchronized (outbound) {
                combined = String.join("", outbound);
            }
            assertTrue(combined.contains("\"type\":\"output\""), "expected an output frame");
        } finally {
            connection.close();
        }
    }

    @Test
    void shouldIgnoreUnknownIncomingFrameTypes() throws Exception {
        List<String> outbound = new ArrayList<>();
        TerminalConnection connection = openFakeConnection(
                80,
                24,
                outbound::add);

        try {
            connection.handleFrame("{\"type\":\"nonsense\",\"foo\":\"bar\"}");
            connection.handleFrame("not-json");
            connection.handleFrame("null");
            assertTrue(connection.isAlive(), "session should survive malformed frames");
        } finally {
            connection.close();
        }
    }

    @Test
    void shouldHandleResizeFrame() throws Exception {
        TerminalConnection connection = openFakeConnection(
                80,
                24,
                frame -> {
                });
        try {
            connection.handleFrame("{\"type\":\"resize\",\"cols\":132,\"rows\":40}");
            assertTrue(connection.isAlive());
        } finally {
            connection.close();
        }
    }

    @Test
    void shouldEmitExitFrameWhenShellExits() throws Exception {
        CompletableFuture<Map<String, Object>> exitFuture = new CompletableFuture<>();
        TerminalConnection connection = openFakeConnection(
                80,
                24,
                frame -> {
                    if (frame.contains("\"type\":\"exit\"") && !exitFuture.isDone()) {
                        try {
                            Map<String, Object> parsed = objectMapper.readValue(frame, Map.class);
                            exitFuture.complete(parsed);
                        } catch (Exception ignored) {
                            // ignore parse errors while waiting
                        }
                    }
                });

        try {
            connection.handleFrame(inputFrame("exit 0\n"));
            Map<String, Object> exit = exitFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals("exit", exit.get("type"));
            assertNotNull(exit.get("code"));
        } finally {
            connection.close();
        }
    }

    private TerminalConnection openFakeConnection(
            int cols,
            int rows,
            Consumer<String> outboundSink) throws IOException {
        return TerminalConnection.open(
                FAKE_COMMAND,
                cols,
                rows,
                null,
                objectMapper,
                outboundSink,
                (command, requestedCols, requestedRows, workingDirectory, outputConsumer) -> {
                    FakeTerminalSession session = new FakeTerminalSession();
                    session.setOutputConsumer(outputConsumer);
                    return session;
                });
    }

    private String inputFrame(String input) {
        return "{\"type\":\"input\",\"data\":\""
                + Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8))
                + "\"}";
    }

    private static final class FakeTerminalSession implements TerminalConnection.SessionHandle {
        private final CountDownLatch exitLatch = new CountDownLatch(1);
        private Consumer<byte[]> outputConsumer = data -> {
        };
        private volatile boolean alive = true;
        private volatile int processExitCode;

        void setOutputConsumer(Consumer<byte[]> outputConsumer) {
            this.outputConsumer = outputConsumer;
        }

        @Override
        public void writeInput(byte[] data) {
            String input = new String(data, StandardCharsets.UTF_8);
            if (input.contains("__GC_READY__")) {
                outputConsumer.accept("__GC_READY__\n".getBytes(StandardCharsets.UTF_8));
            }
            if (input.contains("exit 0")) {
                processExitCode = 0;
                alive = false;
                exitLatch.countDown();
            }
        }

        @Override
        public void resize(int cols, int rows) {
            // Protocol-level resize handling is covered by connection.isAlive().
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public Integer exitCode() {
            return alive ? null : processExitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exitLatch.await(timeout, unit);
        }

        @Override
        public void close() {
            alive = false;
            exitLatch.countDown();
        }
    }
}
