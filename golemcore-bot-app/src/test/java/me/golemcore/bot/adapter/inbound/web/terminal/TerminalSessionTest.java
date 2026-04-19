package me.golemcore.bot.adapter.inbound.web.terminal;

import com.pty4j.PtyProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TerminalSessionTest {

    private static final String[] SH_COMMAND = new String[] { "/bin/sh" };
    private static final long WAIT_TIMEOUT_SECONDS = 10L;
    private static final String PWD_MARKER = "__GC_PWD_END__";
    private static final String PWD_COMMAND = "pwd; printf '\\137\\137GC_PWD_END\\137\\137\\n'\n";

    @Test
    void shouldCaptureOutputWrittenToPty() throws Exception {
        StringBuilder output = new StringBuilder();
        CompletableFuture<Void> outputReceived = new CompletableFuture<>();

        TerminalSession session = TerminalSession.start(
                SH_COMMAND,
                120,
                30,
                data -> {
                    synchronized (output) {
                        output.append(new String(data, StandardCharsets.UTF_8));
                        if (output.toString().contains("__GC_READY__")) {
                            outputReceived.complete(null);
                        }
                    }
                });

        try {
            session.writeInput("echo __GC_READY__\n".getBytes(StandardCharsets.UTF_8));
            outputReceived.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            String received;
            synchronized (output) {
                received = output.toString();
            }
            assertTrue(received.contains("__GC_READY__"), "expected output to contain marker, got: " + received);
        } finally {
            session.close();
        }
    }

    @Test
    void shouldAcceptResizeWithoutError() throws Exception {
        TerminalSession session = TerminalSession.start(SH_COMMAND, 80, 24, data -> {
        });
        try {
            session.resize(132, 40);
            assertTrue(session.isAlive(), "session should still be alive after resize");
        } finally {
            session.close();
        }
    }

    @Test
    void shouldReportNotAliveAfterClose() throws Exception {
        TerminalSession session = TerminalSession.start(SH_COMMAND, 80, 24, data -> {
        });
        session.close();
        assertFalse(session.isAlive(), "session should report not alive after close");
    }

    @Test
    void shouldIgnoreOperationsAfterClose() throws Exception {
        TerminalSession session = TerminalSession.start(SH_COMMAND, 80, 24, data -> {
        });
        session.close();

        session.writeInput("ignored\n".getBytes(StandardCharsets.UTF_8));
        session.resize(120, 40);
        session.close();

        assertFalse(session.isAlive(), "closed sessions should stay closed after redundant operations");
    }

    @Test
    void shouldStartInGivenWorkingDirectory(@TempDir Path tempDir) throws Exception {
        Path realDir = tempDir.toRealPath();
        CompletableFuture<Void> outputReceived = new CompletableFuture<>();
        StringBuilder captured = new StringBuilder();

        TerminalSession session = TerminalSession.start(
                SH_COMMAND,
                80,
                24,
                realDir,
                data -> {
                    synchronized (captured) {
                        captured.append(new String(data, StandardCharsets.UTF_8));
                        if (captured.toString().contains(PWD_MARKER)) {
                            outputReceived.complete(null);
                        }
                    }
                });

        try {
            session.writeInput(PWD_COMMAND.getBytes(StandardCharsets.UTF_8));
            outputReceived.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String received;
            synchronized (captured) {
                received = captured.toString();
            }
            assertTrue(
                    containsNormalizedPath(received, realDir),
                    "expected pwd output to contain " + realDir + ", got: " + received);
        } finally {
            session.close();
        }
    }

    @Test
    void shouldAcceptNullWorkingDirectoryAsDefault() throws IOException {
        TerminalSession session = TerminalSession.start(SH_COMMAND, 80, 24, null, data -> {
        });
        try {
            assertTrue(session.isAlive(), "session should start with null working dir");
        } finally {
            session.close();
        }
    }

    @Test
    void shouldReturnNullExitCodeWhileShellIsAlive() throws Exception {
        TerminalSession session = TerminalSession.start(SH_COMMAND, 80, 24, data -> {
        });
        try {
            assertNull(session.exitCode(), "alive shells should not report an exit code");
        } finally {
            session.close();
        }
    }

    @Test
    void shouldExposeExitCodeAfterShellTermination() throws Exception {
        CompletableFuture<Void> outputReceived = new CompletableFuture<>();
        AtomicReference<String> captured = new AtomicReference<>("");

        TerminalSession session = TerminalSession.start(
                SH_COMMAND,
                80,
                24,
                data -> {
                    String chunk = new String(data, StandardCharsets.UTF_8);
                    captured.updateAndGet(prev -> prev + chunk);
                    if (captured.get().contains("__GC_BYE__")) {
                        outputReceived.complete(null);
                    }
                });

        try {
            session.writeInput("echo __GC_BYE__ && exit 0\n".getBytes(StandardCharsets.UTF_8));
            outputReceived.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(session.waitFor(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "shell should exit within timeout");
            assertFalse(session.isAlive(), "session should observe the process exit before close()");
            assertNotNull(session.exitCode());
        } finally {
            session.close();
        }
    }

    @Test
    void shouldSkipZeroLengthReadsWhenPumpingOutput() {
        PtyProcess process = mock(PtyProcess.class);
        when(process.getInputStream()).thenReturn(new InputStream() {
            private int reads;

            @Override
            public int read() {
                return -1;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                reads += 1;
                if (reads == 1) {
                    return 0;
                }
                if (reads == 2) {
                    buffer[offset] = 'o';
                    buffer[offset + 1] = 'k';
                    return 2;
                }
                return -1;
            }
        });
        List<byte[]> chunks = new ArrayList<>();

        TerminalSession.pumpOutput(process, chunks::add);

        assertEquals(1, chunks.size());
        assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), chunks.get(0));
    }

    @Test
    void shouldNotPumpOutputWhenThreadIsAlreadyInterrupted() {
        PtyProcess process = mock(PtyProcess.class);
        when(process.getInputStream()).thenReturn(InputStream.nullInputStream());
        List<byte[]> chunks = new ArrayList<>();

        Thread.currentThread().interrupt();
        try {
            TerminalSession.pumpOutput(process, chunks::add);
        } finally {
            Thread.interrupted();
        }

        assertTrue(chunks.isEmpty(), "interrupted readers should exit before reading pty output");
    }

    private boolean containsNormalizedPath(String output, Path expectedPath) {
        String normalizedOutput = normalizeMacTempPath(output);
        String normalizedExpected = normalizeMacTempPath(expectedPath.toString());
        return normalizedOutput.contains(normalizedExpected);
    }

    private String normalizeMacTempPath(String value) {
        return value.replace("/private/var/", "/var/");
    }
}
