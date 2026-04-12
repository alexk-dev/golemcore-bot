package me.golemcore.bot.domain.selfevolving.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import me.golemcore.bot.port.outbound.TraceSnapshotCodecPort;

public class FakeTraceSnapshotCodecPort implements TraceSnapshotCodecPort {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Override
    public byte[] encodeJson(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        return String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
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
