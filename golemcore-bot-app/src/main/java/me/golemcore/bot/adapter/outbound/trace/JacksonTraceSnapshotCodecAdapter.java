package me.golemcore.bot.adapter.outbound.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import me.golemcore.bot.port.outbound.TraceSnapshotCodecPort;
import org.springframework.stereotype.Component;

@Component
public class JacksonTraceSnapshotCodecAdapter implements TraceSnapshotCodecPort {

    private final ObjectMapper objectMapper;

    public JacksonTraceSnapshotCodecAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public byte[] encodeJson(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception exception) {
            return String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public <T> T decodeJson(String payload, Class<T> targetType) {
        try {
            return objectMapper.readValue(payload, targetType);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to decode JSON payload", exception);
        }
    }
}
