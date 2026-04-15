package me.golemcore.bot.adapter.inbound.web.terminal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalSessionTest {

    private static final String[] SH_COMMAND = new String[] { "/bin/sh" };
    private static final long WAIT_TIMEOUT_SECONDS = 10L;

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
            assertNotNull(session.exitCode());
        } finally {
            session.close();
        }
    }
}
