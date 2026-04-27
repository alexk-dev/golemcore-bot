package me.golemcore.bot.domain.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceSnapshotCompressionServiceTest {

    @Test
    void shouldCompressAndRestoreSnapshotPayload() {
        TraceSnapshotCompressionService service = new TraceSnapshotCompressionService();
        byte[] payload = "{\"messages\":[{\"role\":\"user\",\"content\":\"hello tracing\"}]}"
                .getBytes(StandardCharsets.UTF_8);

        byte[] compressed = service.compress(payload);
        byte[] restored = service.decompress("zstd", compressed);

        assertTrue(compressed.length > 0);
        assertArrayEquals(payload, restored);
    }

    @Test
    void shouldReturnEmptyArraysForNullOrEmptyPayloads() {
        TraceSnapshotCompressionService service = new TraceSnapshotCompressionService();

        assertEquals(0, service.compress(null).length);
        assertEquals(0, service.compress(new byte[0]).length);
        assertEquals(0, service.decompress("zstd", null).length);
        assertEquals(0, service.decompress("zstd", new byte[0]).length);
    }

    @Test
    void shouldRejectUnsupportedSnapshotEncoding() {
        TraceSnapshotCompressionService service = new TraceSnapshotCompressionService();
        byte[] compressed = service.compress("payload".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.decompress("gzip", compressed));

        assertTrue(error.getMessage().contains("Unsupported trace snapshot encoding"));
    }
}
