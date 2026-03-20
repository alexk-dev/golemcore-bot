package me.golemcore.bot.domain.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
}
