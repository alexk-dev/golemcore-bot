package me.golemcore.bot.domain.tracing;

import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceContextSupportTest {

    @Test
    void shouldCreateRootMetadataWhenMissing() {
        Map<String, Object> metadata = TraceContextSupport.ensureRootMetadata(null, TraceSpanKind.INGRESS,
                "web.message");

        assertNotNull(metadata.get(ContextAttributes.TRACE_ID));
        assertNotNull(metadata.get(ContextAttributes.TRACE_SPAN_ID));
        assertEquals("INGRESS", metadata.get(ContextAttributes.TRACE_ROOT_KIND));
        assertEquals("web.message", metadata.get(ContextAttributes.TRACE_NAME));
    }

    @Test
    void shouldPreserveExistingTraceContextWhenEnsuringRootMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.TRACE_ID, "trace-1");
        metadata.put(ContextAttributes.TRACE_SPAN_ID, "span-1");
        metadata.put(ContextAttributes.TRACE_PARENT_SPAN_ID, "parent-1");
        metadata.put(ContextAttributes.TRACE_ROOT_KIND, "TOOL");

        Map<String, Object> enriched = TraceContextSupport.ensureRootMetadata(metadata, TraceSpanKind.INGRESS,
                "tool.execute");

        assertEquals("trace-1", enriched.get(ContextAttributes.TRACE_ID));
        assertEquals("span-1", enriched.get(ContextAttributes.TRACE_SPAN_ID));
        assertEquals("parent-1", enriched.get(ContextAttributes.TRACE_PARENT_SPAN_ID));
        assertEquals("TOOL", enriched.get(ContextAttributes.TRACE_ROOT_KIND));
        assertEquals("tool.execute", enriched.get(ContextAttributes.TRACE_NAME));
    }

    @Test
    void shouldReadWriteAndCopyTraceMetadata() {
        Map<String, Object> source = new LinkedHashMap<>();
        TraceContext traceContext = TraceContext.builder().traceId("trace-2").spanId("span-2").parentSpanId("parent-2")
                .rootKind("LLM").build();

        TraceContextSupport.writeTraceMetadata(source, traceContext, "llm.chat");
        TraceContext restored = TraceContextSupport.readTraceContext(source);
        Map<String, Object> target = new LinkedHashMap<>();
        TraceContextSupport.copyTraceMetadata(source, target);

        assertEquals("trace-2", restored.getTraceId());
        assertEquals("span-2", restored.getSpanId());
        assertEquals("parent-2", restored.getParentSpanId());
        assertEquals("LLM", restored.getRootKind());
        assertEquals("llm.chat", TraceContextSupport.readTraceName(target));
        assertEquals("trace-2", target.get(ContextAttributes.TRACE_ID));
        assertEquals("span-2", target.get(ContextAttributes.TRACE_SPAN_ID));
    }

    @Test
    void shouldIgnoreBlankValuesWhenReadingOrWritingTraceMetadata() {
        Map<String, Object> blankSource = Map.of(ContextAttributes.TRACE_ID, "   ", ContextAttributes.TRACE_SPAN_ID,
                "");
        Map<String, Object> target = new LinkedHashMap<>();

        assertNull(TraceContextSupport.readTraceContext(blankSource));

        TraceContextSupport.writeTraceMetadata(target, null, "ignored");
        TraceContextSupport.copyTraceMetadata(null, target);
        TraceContextSupport.copyTraceMetadata(blankSource, target);

        assertTrue(target.isEmpty());
        assertNull(TraceContextSupport.readTraceName(blankSource));
    }

    @Test
    void shouldCreateRootContextWithoutRootKindWhenMissing() {
        TraceContext context = TraceContextSupport.createRootContext(null);

        assertNotNull(context.getTraceId());
        assertNotNull(context.getSpanId());
        assertNull(context.getParentSpanId());
        assertNull(context.getRootKind());
        assertFalse(context.getTraceId().isBlank());
        assertFalse(context.getSpanId().isBlank());
    }
}
