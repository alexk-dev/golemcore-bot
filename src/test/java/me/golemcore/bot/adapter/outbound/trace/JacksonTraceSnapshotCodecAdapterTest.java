package me.golemcore.bot.adapter.outbound.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JacksonTraceSnapshotCodecAdapterTest {

    @Test
    void shouldEncodeAndDecodeJsonPayloads() {
        JacksonTraceSnapshotCodecAdapter adapter = new JacksonTraceSnapshotCodecAdapter(new ObjectMapper());

        assertArrayEquals(new byte[0], adapter.encodeJson(null));
        assertEquals("{\"value\":42}", new String(adapter.encodeJson(Map.of("value", 42)), StandardCharsets.UTF_8));
        assertEquals("ok", adapter.decodeJson("{\"value\":\"ok\"}", Payload.class).value());
    }

    @Test
    void shouldFallbackToStringEncodingAndWrapDecodeFailures() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsBytes(any())).thenThrow(new JsonProcessingException("encode failed") {
        });
        when(objectMapper.readValue("bad", Payload.class)).thenThrow(new JsonProcessingException("decode failed") {
        });
        JacksonTraceSnapshotCodecAdapter adapter = new JacksonTraceSnapshotCodecAdapter(objectMapper);

        assertArrayEquals("fallback".getBytes(StandardCharsets.UTF_8), adapter.encodeJson("fallback"));
        assertThrows(IllegalArgumentException.class, () -> adapter.decodeJson("bad", Payload.class));
    }

    private record Payload(String value) {
    }
}
