package me.golemcore.bot.domain.resilience;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.port.outbound.TraceSnapshotCodecPort;

import java.nio.charset.StandardCharsets;

/**
 * Jackson-backed fake codec — isolated to tests so domain code stays
 * Jackson-free.
 */
public final class FakeCodec implements TraceSnapshotCodecPort {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Override
    public byte[] encodeJson(Object payload) {
        return payload == null ? new byte[0] : String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
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
