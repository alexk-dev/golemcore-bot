package me.golemcore.bot.domain.resilience;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.port.outbound.TraceSnapshotCodecPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResilienceObservabilitySupportTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-04-22T04:00:00Z");

    private Logger log;
    private TraceService traceService;
    private RuntimeConfigService runtimeConfigService;
    private Clock clock;
    private AgentContext context;

    @BeforeEach
    void setUp() {
        log = mock(Logger.class);
        traceService = mock(TraceService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("session-1")
                .chatId("chat-1")
                .build();
        context = AgentContext.builder()
                .session(session)
                .build();
        context.setTraceContext(TraceContext.builder()
                .traceId("trace-1")
                .spanId("span-1")
                .build());
    }

    @Test
    void shouldEmitContextMetricWithSanitizedAttributesAndTraceEvent() {
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", "person@example.com");
        attributes.put("token", "abc123");
        attributes.put("count", 3);

        ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock, context,
                "follow_through.classifier.invoked", attributes);

        verify(log).info(eq("[SessionMetrics] metric={} value=1 sessionId={} traceId={} attrs={}"),
                eq("follow_through.classifier.invoked"),
                eq("session-1"),
                eq("trace-1"),
                argThat(value -> {
                    if (!(value instanceof Map<?, ?> map)) {
                        return false;
                    }
                    return "[REDACTED]".equals(map.get("email"))
                            && "[REDACTED]".equals(map.get("token"))
                            && Integer.valueOf(3).equals(map.get("count"));
                }));
        verify(traceService).appendEvent(eq(context.getSession()), eq(context.getTraceContext()),
                eq("follow_through.classifier.invoked"), eq(FIXED_INSTANT),
                argThat(value -> {
                    if (!(value instanceof Map<?, ?> map)) {
                        return false;
                    }
                    return "[REDACTED]".equals(map.get("email"))
                            && "[REDACTED]".equals(map.get("token"));
                }));
    }

    @Test
    void shouldEmitMessageMetricWithMetadataValues() {
        Message message = Message.builder()
                .channelType("telegram")
                .chatId("chat-1")
                .metadata(Map.of(
                        ContextAttributes.MESSAGE_INTERNAL_KIND, "synthetic",
                        ContextAttributes.TURN_QUEUE_KIND, "follow_through"))
                .build();

        ResilienceObservabilitySupport.emitMessageMetric(log, "follow_through.synthetic.sent", message,
                Map.of("email", "person@example.com"));

        verify(log).info(
                eq("[SessionMetrics] metric={} value=1 channel={} chatId={} internalKind={} queueKind={} attrs={}"),
                eq("follow_through.synthetic.sent"),
                eq("telegram"),
                eq("chat-1"),
                eq("synthetic"),
                eq("follow_through"),
                argThat(value -> value instanceof Map<?, ?> map && "[REDACTED]".equals(map.get("email"))));
    }

    @Test
    void shouldCaptureSampledPayloadUsingSanitizedSnapshotPayload() {
        stubTracingConfig(1.0d);
        CapturingCodec codec = new CapturingCodec();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("email", "user@example.com");
        payload.put("authorization", "Bearer secret-token");
        payload.put("items", List.of("person@example.com", SampleEnum.FIRST));
        payload.put("details", Map.of("token", "abc123", "safe", Boolean.TRUE));

        ResilienceObservabilitySupport.captureSampledPayload(log, traceService, codec, runtimeConfigService, clock,
                context, "sample-key", "classifier.request", payload);

        assertNotNull(codec.capturedPayload);
        assertTrue(codec.capturedPayload instanceof Map<?, ?>);
        Map<?, ?> captured = (Map<?, ?>) codec.capturedPayload;
        assertEquals("[REDACTED]", captured.get("email"));
        assertEquals("[REDACTED]", captured.get("authorization"));
        assertTrue(captured.get("items") instanceof List<?>);
        List<?> items = (List<?>) captured.get("items");
        assertEquals("[REDACTED]", items.get(0));
        assertEquals("first", items.get(1));
        assertTrue(captured.get("details") instanceof Map<?, ?>);
        Map<?, ?> details = (Map<?, ?>) captured.get("details");
        assertEquals("[REDACTED]", details.get("token"));
        assertEquals(Boolean.TRUE, details.get("safe"));
        verify(traceService).captureSnapshot(eq(context.getSession()), eq(context.getTraceContext()), any(),
                eq("classifier.request"), eq("application/json"), any(byte[].class));
    }

    @Test
    void shouldSkipPayloadCaptureWhenSamplingBucketMissesRequestedRate() {
        stubTracingConfig(0.3d);
        CapturingCodec codec = new CapturingCodec();

        ResilienceObservabilitySupport.captureSampledPayload(log, traceService, codec, runtimeConfigService, clock,
                context, "sample-key", "classifier.request", Map.of("token", "abc123"));

        verify(traceService, never()).captureSnapshot(any(), any(), any(), any(), any(), any());
        assertNull(codec.capturedPayload);
    }

    @Test
    void shouldCapturePayloadWhenSamplingRateIsFull() {
        stubTracingConfig(1.0d);
        CapturingCodec codec = new CapturingCodec();

        ResilienceObservabilitySupport.captureSampledPayload(log, traceService, codec, runtimeConfigService, clock,
                context, "sample-key", "classifier.request", Map.of("safe", "value"));

        assertNotNull(codec.capturedPayload);
    }

    @Test
    void shouldReturnFalseWhenObservationKeyIsBlank() {
        assertFalse(ResilienceObservabilitySupport.markObservedOnce(context, " "));
    }

    @Test
    void shouldSanitizeUnknownObjectTypesAndLongPayloads() {
        stubTracingConfig(1.0d);
        CapturingCodec codec = new CapturingCodec();
        String longValue = "x".repeat(2205);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("custom", new Object() {
            @Override
            public String toString() {
                return "person@example.com " + longValue;
            }
        });

        ResilienceObservabilitySupport.captureSampledPayload(log, traceService, codec, runtimeConfigService, clock,
                context, "sample-key", "classifier.request", payload);

        Map<?, ?> captured = (Map<?, ?>) codec.capturedPayload;
        assertTrue(captured.get("custom") instanceof String);
        String custom = (String) captured.get("custom");
        assertTrue(custom.contains("[REDACTED]"));
        assertTrue(custom.endsWith("..."));
    }

    @Test
    void shouldSkipPayloadCaptureWhenSamplingIsDisabled() {
        stubTracingConfig(0.0d);
        CapturingCodec codec = new CapturingCodec();

        ResilienceObservabilitySupport.captureSampledPayload(log, traceService, codec, runtimeConfigService, clock,
                context, "sample-key", "classifier.request", Map.of("token", "abc123"));

        verify(traceService, never()).captureSnapshot(any(), any(), any(), any(), any(), any());
        assertEquals(null, codec.capturedPayload);
    }

    @Test
    void shouldMarkObservationOnlyOncePerAttribute() {
        assertTrue(ResilienceObservabilitySupport.markObservedOnce(context, "observed.flag"));
        assertFalse(ResilienceObservabilitySupport.markObservedOnce(context, "observed.flag"));
    }

    private void stubTracingConfig(double sampleRate) {
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(false);
        when(runtimeConfigService.isSelfEvolvingTracePayloadOverrideEnabled()).thenReturn(false);
        when(runtimeConfigService.isPayloadSnapshotsEnabled()).thenReturn(true);
        when(runtimeConfigService.getSessionTraceBudgetMb()).thenReturn(128);
        when(runtimeConfigService.getTraceMaxSnapshotSizeKb()).thenReturn(256);
        when(runtimeConfigService.getTraceMaxSnapshotsPerSpan()).thenReturn(10);
        when(runtimeConfigService.getTraceMaxTracesPerSession()).thenReturn(100);
        when(runtimeConfigService.isTraceInboundPayloadCaptureEnabled()).thenReturn(false);
        when(runtimeConfigService.isTraceOutboundPayloadCaptureEnabled()).thenReturn(false);
        when(runtimeConfigService.isTraceToolPayloadCaptureEnabled()).thenReturn(false);
        when(runtimeConfigService.isTraceLlmPayloadCaptureEnabled()).thenReturn(false);
        when(runtimeConfigService.getTraceResiliencePayloadSampleRate()).thenReturn(sampleRate);
    }

    private enum SampleEnum {
        FIRST
    }

    private static final class CapturingCodec implements TraceSnapshotCodecPort {

        private Object capturedPayload;

        @Override
        public byte[] encodeJson(Object payload) {
            this.capturedPayload = payload;
            return String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> T decodeJson(String payload, Class<T> targetType) {
            throw new UnsupportedOperationException("decodeJson is not used in this test");
        }
    }
}
