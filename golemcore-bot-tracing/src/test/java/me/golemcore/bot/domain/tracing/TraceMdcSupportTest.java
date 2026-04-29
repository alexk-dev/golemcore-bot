package me.golemcore.bot.domain.tracing;

import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.trace.TraceContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceMdcSupportTest {

    @Test
    void shouldReturnEmptyContextWhenMessageOrMetadataIsMissing() {
        assertTrue(TraceMdcSupport.buildMdcContext((Message) null).isEmpty());
        assertTrue(TraceMdcSupport.buildMdcContext(Message.builder().metadata(null).build()).isEmpty());
    }

    @Test
    void shouldBuildMdcContextFromMessageTraceMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        TraceContextSupport.writeTraceMetadata(metadata,
                TraceContext.builder().traceId("trace-1").spanId("span-1").parentSpanId("parent-1").build(),
                "telegram.message");
        Message message = Message.builder().metadata(metadata).build();

        Map<String, String> context = TraceMdcSupport.buildMdcContext(message);

        assertEquals("trace-1", context.get("trace"));
        assertEquals("span-1", context.get("span"));
        assertEquals("telegram.message", context.get(ContextAttributes.TRACE_NAME));
    }

    @Test
    void shouldSkipBlankTraceFieldsAndKeepExplicitTraceName() {
        Map<String, Object> metadata = Map.of(ContextAttributes.TRACE_NAME, "webhook.wake");
        TraceContext traceContext = TraceContext.builder().traceId(" ").spanId("").build();

        Map<String, String> context = TraceMdcSupport.buildMdcContext(traceContext, metadata);

        assertEquals(1, context.size());
        assertEquals("webhook.wake", context.get(ContextAttributes.TRACE_NAME));
    }

    @Test
    void shouldBuildContextWhenOnlyTraceContextIsAvailable() {
        TraceContext traceContext = TraceContext.builder().traceId("trace-2").spanId("span-2").build();

        Map<String, String> context = TraceMdcSupport.buildMdcContext(traceContext, null);

        assertEquals(Map.of("trace", "trace-2", "span", "span-2"), context);
    }
}
