package me.golemcore.bot.adapter.inbound.web.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalConnectionTest {

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

        TerminalConnection connection = TerminalConnection.open(
                new String[] { "/bin/sh" },
                120,
                30,
                objectMapper,
                frame -> {
                    synchronized (outbound) {
                        outbound.add(frame);
                        if (frame.contains("X19HQ19SRUFEWV9f")) {
                            readySignal.complete(null);
                        }
                    }
                });

        try {
            connection.handleFrame(wrap("{\"type\":\"input\",\"data\":\"ZWNobyBfX0dDX1JFQURZX18K\"}"));
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
        TerminalConnection connection = TerminalConnection.open(
                new String[] { "/bin/sh" },
                80,
                24,
                objectMapper,
                outbound::add);

        try {
            connection.handleFrame("{\"type\":\"nonsense\",\"foo\":\"bar\"}");
            connection.handleFrame("not-json");
            assertTrue(connection.isAlive(), "session should survive malformed frames");
        } finally {
            connection.close();
        }
    }

    @Test
    void shouldHandleResizeFrame() throws Exception {
        TerminalConnection connection = TerminalConnection.open(
                new String[] { "/bin/sh" },
                80,
                24,
                objectMapper,
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
        TerminalConnection connection = TerminalConnection.open(
                new String[] { "/bin/sh" },
                80,
                24,
                objectMapper,
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
            connection.handleFrame(wrap("{\"type\":\"input\",\"data\":\"ZXhpdCAwCg==\"}"));
            Map<String, Object> exit = exitFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals("exit", exit.get("type"));
            assertNotNull(exit.get("code"));
        } finally {
            connection.close();
        }
    }

    private String wrap(String json) {
        // Ensures the payload bytes decode cleanly; the test uses precomputed base64
        // for
        // "echo __GC_READY__\n" (ZWNobyBfX0dDX1JFQURZX18K) and "exit 0\n"
        // (ZXhpdCAwCg==).
        Base64.getDecoder().decode("ZWNobyBfX0dDX1JFQURZX18K");
        return json;
    }
}
