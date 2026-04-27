package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Utilities for storing and restoring canonical trace metadata on messages and context attributes.
 */
public final class TraceContextSupport {

    private TraceContextSupport() {
    }

    public static Map<String, Object> ensureRootMetadata(Map<String, Object> metadata, TraceSpanKind rootKind,
            String traceName) {
        Map<String, Object> target = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
        TraceContext traceContext = readTraceContext(target);
        if (traceContext == null) {
            traceContext = createRootContext(rootKind);
        }
        writeTraceMetadata(target, traceContext, traceName);
        return target;
    }

    public static TraceContext createRootContext(TraceSpanKind rootKind) {
        return TraceContext.builder().traceId(UUID.randomUUID().toString()).spanId(UUID.randomUUID().toString())
                .parentSpanId(null).rootKind(rootKind != null ? rootKind.name() : null).build();
    }

    public static TraceContext readTraceContext(Map<String, Object> source) {
        String traceId = readString(source, ContextAttributes.TRACE_ID);
        String spanId = readString(source, ContextAttributes.TRACE_SPAN_ID);
        if (StringValueSupport.isBlank(traceId) || StringValueSupport.isBlank(spanId)) {
            return null;
        }
        return TraceContext.builder().traceId(traceId).spanId(spanId)
                .parentSpanId(readString(source, ContextAttributes.TRACE_PARENT_SPAN_ID))
                .rootKind(readString(source, ContextAttributes.TRACE_ROOT_KIND)).build();
    }

    public static void writeTraceMetadata(Map<String, Object> target, TraceContext traceContext, String traceName) {
        if (target == null || traceContext == null) {
            return;
        }
        putString(target, ContextAttributes.TRACE_ID, traceContext.getTraceId());
        putString(target, ContextAttributes.TRACE_SPAN_ID, traceContext.getSpanId());
        putString(target, ContextAttributes.TRACE_PARENT_SPAN_ID, traceContext.getParentSpanId());
        putString(target, ContextAttributes.TRACE_ROOT_KIND, traceContext.getRootKind());
        putString(target, ContextAttributes.TRACE_NAME, traceName);
    }

    public static String readTraceName(Map<String, Object> source) {
        return readString(source, ContextAttributes.TRACE_NAME);
    }

    public static void copyTraceMetadata(Map<String, Object> source, Map<String, Object> target) {
        if (source == null || target == null) {
            return;
        }
        copyString(source, target, ContextAttributes.TRACE_ID);
        copyString(source, target, ContextAttributes.TRACE_SPAN_ID);
        copyString(source, target, ContextAttributes.TRACE_PARENT_SPAN_ID);
        copyString(source, target, ContextAttributes.TRACE_ROOT_KIND);
        copyString(source, target, ContextAttributes.TRACE_NAME);
    }

    private static void copyString(Map<String, Object> source, Map<String, Object> target, String key) {
        putString(target, key, readString(source, key));
    }

    private static void putString(Map<String, Object> target, String key, String value) {
        if (!StringValueSupport.isBlank(key) && !StringValueSupport.isBlank(value)) {
            target.put(key, value);
        }
    }

    private static String readString(Map<String, Object> source, String key) {
        if (source == null || StringValueSupport.isBlank(key)) {
            return null;
        }
        Object value = source.get(key);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        return null;
    }
}
