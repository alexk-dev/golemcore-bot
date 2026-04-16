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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void shouldEmitOutputProducedDuringSessionStartup() throws Exception {
        List<String> outbound = new ArrayList<>();
        TerminalConnection connection = TerminalConnection.open(
                FAKE_COMMAND,
                120,
                30,
                null,
                objectMapper,
                outbound::add,
                (command, requestedCols, requestedRows, workingDirectory, outputConsumer) -> {
                    outputConsumer.accept("startup banner\n".getBytes(StandardCharsets.UTF_8));
                    return new FakeTerminalSession();
                });

        try {
            assertEquals(1, outbound.size(), "startup output should not be dropped before connectionRef is assigned");
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = objectMapper.readValue(outbound.get(0), Map.class);
            assertEquals("output", frame.get("type"));
            String decoded = new String(
                    Base64.getDecoder().decode((String) frame.get("data")),
                    StandardCharsets.UTF_8);
            assertEquals("startup banner\n", decoded);
        } finally {
            connection.close();
        }
    }

    @Test
    void shouldEmitOutputProducedAfterSessionStartup() throws Exception {
        List<String> outbound = new ArrayList<>();
        AtomicReference<Consumer<byte[]>> outputConsumerRef = new AtomicReference<>();
        TerminalConnection connection = TerminalConnection.open(
                FAKE_COMMAND,
                120,
                30,
                null,
                objectMapper,
                outbound::add,
                (command, requestedCols, requestedRows, workingDirectory, outputConsumer) -> {
                    outputConsumerRef.set(outputConsumer);
                    return new FakeTerminalSession();
                });

        try {
            outputConsumerRef.get().accept("ready\n".getBytes(StandardCharsets.UTF_8));

            assertEquals(1, outbound.size(), "post-startup output should be emitted immediately");
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = objectMapper.readValue(outbound.get(0), Map.class);
            assertEquals("output", frame.get("type"));
            String decoded = new String(
                    Base64.getDecoder().decode((String) frame.get("data")),
                    StandardCharsets.UTF_8);
            assertEquals("ready\n", decoded);
        } finally {
            connection.close();
        }
    }

    @Test
    void shouldMapDefaultExitStrategyOutcomes() throws Exception {
        TerminalConnection.ExitWaitOutcome timeout = TerminalConnection
                .defaultStrategy(new ScriptedWaitSession(false, null)).awaitExit();
        TerminalConnection.ExitWaitOutcome missingCode = TerminalConnection
                .defaultStrategy(new ScriptedWaitSession(true, null)).awaitExit();
        TerminalConnection.ExitWaitOutcome exited = TerminalConnection.defaultStrategy(new ScriptedWaitSession(true, 7))
                .awaitExit();

        assertFalse(timeout.isTerminated(), "waitFor=false should be treated as a timeout");
        assertFalse(missingCode.isTerminated(), "a terminated process without an exit code should be retried");
        assertTrue(exited.isTerminated(), "a terminated process with an exit code should emit exit");
        assertEquals(7, exited.exitCode());
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
    void shouldIgnoreEmptyAndNonStringTypedFrames() throws Exception {
        FakeTerminalSession session = new FakeTerminalSession();
        TerminalConnection connection = openFakeConnection(
                80,
                24,
                session,
                frame -> {
                });

        try {
            connection.handleFrame(null);
            connection.handleFrame("");
            connection.handleFrame("   ");
            connection.handleFrame("{\"type\":7}");

            assertTrue(connection.isAlive(), "ignored frames should not close the connection");
            assertEquals(0, session.writeInvocations());
        } finally {
            connection.close();
        }
    }

    @Test
    void shouldCloseWhenCloseFrameIsReceived() throws Exception {
        TerminalConnection connection = openFakeConnection(
                80,
                24,
                frame -> {
                });

        connection.handleFrame("{\"type\":\"close\"}");

        assertFalse(connection.isAlive(), "close frames should close the session");
    }

    @Test
    void shouldIgnoreFramesAfterClose() throws Exception {
        FakeTerminalSession session = new FakeTerminalSession();
        TerminalConnection connection = openFakeConnection(
                80,
                24,
                session,
                frame -> {
                });
        connection.close();

        connection.handleFrame(inputFrame("ignored\n"));

        assertEquals(0, session.writeInvocations(), "closed connections must ignore incoming frames");
    }

    @Test
    void shouldIgnoreInputFramesWithoutStringData() throws Exception {
        FakeTerminalSession session = new FakeTerminalSession();
        TerminalConnection connection = openFakeConnection(
                80,
                24,
                session,
                frame -> {
                });

        try {
            connection.handleFrame("{\"type\":\"input\",\"data\":123}");

            assertEquals(0, session.writeInvocations(), "non-string input data must not be written to the pty");
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
    void shouldIgnoreInvalidResizeFrames() throws Exception {
        FakeTerminalSession session = new FakeTerminalSession();
        TerminalConnection connection = openFakeConnection(
                80,
                24,
                session,
                frame -> {
                });

        try {
            connection.handleFrame("{\"type\":\"resize\",\"rows\":24}");
            connection.handleFrame("{\"type\":\"resize\",\"cols\":80}");
            connection.handleFrame("{\"type\":\"resize\",\"cols\":0,\"rows\":24}");
            connection.handleFrame("{\"type\":\"resize\",\"cols\":80,\"rows\":0}");

            assertEquals(0, session.resizeInvocations(), "invalid resize frames must not reach the pty");
        } finally {
            connection.close();
        }
    }

    @Test
    void shouldReportNotAliveWhenUnderlyingSessionStops() throws Exception {
        FakeTerminalSession session = new FakeTerminalSession();
        TerminalConnection connection = openFakeConnection(
                80,
                24,
                session,
                frame -> {
                });

        try {
            session.markExited(0);

            assertFalse(connection.isAlive(), "connection liveness should include the pty liveness");
        } finally {
            connection.close();
        }
    }

    @Test
    void shouldDropLateOutputAndExitFramesAfterClose() throws Exception {
        List<String> outbound = new ArrayList<>();
        AtomicReference<Consumer<byte[]>> outputConsumerRef = new AtomicReference<>();
        TerminalConnection connection = TerminalConnection.open(
                FAKE_COMMAND,
                80,
                24,
                null,
                objectMapper,
                outbound::add,
                (command, requestedCols, requestedRows, workingDirectory, outputConsumer) -> {
                    outputConsumerRef.set(outputConsumer);
                    return new FakeTerminalSession();
                });

        connection.close();
        outputConsumerRef.get().accept("late\n".getBytes(StandardCharsets.UTF_8));
        connection.emitExit(99);

        assertTrue(outbound.isEmpty(), "closed connections must not emit late output or exit frames");
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
        return openFakeConnection(cols, rows, new FakeTerminalSession(), outboundSink);
    }

    private TerminalConnection openFakeConnection(
            int cols,
            int rows,
            FakeTerminalSession session,
            Consumer<String> outboundSink) throws IOException {
        return TerminalConnection.open(
                FAKE_COMMAND,
                cols,
                rows,
                null,
                objectMapper,
                outboundSink,
                (command, requestedCols, requestedRows, workingDirectory, outputConsumer) -> {
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
        private int writeInvocationCount;
        private int resizeInvocationCount;

        void setOutputConsumer(Consumer<byte[]> outputConsumer) {
            this.outputConsumer = outputConsumer;
        }

        void markExited(int exitCode) {
            processExitCode = exitCode;
            alive = false;
            exitLatch.countDown();
        }

        int writeInvocations() {
            return writeInvocationCount;
        }

        int resizeInvocations() {
            return resizeInvocationCount;
        }

        @Override
        public void writeInput(byte[] data) {
            writeInvocationCount += 1;
            String input = new String(data, StandardCharsets.UTF_8);
            if (input.contains("__GC_READY__")) {
                outputConsumer.accept("__GC_READY__\n".getBytes(StandardCharsets.UTF_8));
            }
            if (input.contains("exit 0")) {
                markExited(0);
            }
        }

        @Override
        public void resize(int cols, int rows) {
            resizeInvocationCount += 1;
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

    private static final class ScriptedWaitSession implements TerminalConnection.SessionHandle {
        private final boolean waitResult;
        private final Integer scriptedExitCode;

        private ScriptedWaitSession(boolean waitResult, Integer exitCode) {
            this.waitResult = waitResult;
            this.scriptedExitCode = exitCode;
        }

        @Override
        public void writeInput(byte[] data) {
        }

        @Override
        public void resize(int cols, int rows) {
        }

        @Override
        public boolean isAlive() {
            return scriptedExitCode == null;
        }

        @Override
        public Integer exitCode() {
            return scriptedExitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return waitResult;
        }

        @Override
        public void close() {
        }
    }
}
