package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.trace.TraceContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared MDC helpers for trace correlation.
 */
public final class TraceMdcSupport {

    private TraceMdcSupport() {
    }

    public static Map<String, String> buildMdcContext(Message message) {
        if (message == null || message.getMetadata() == null) {
            return Map.of();
        }
        return buildMdcContext(TraceContextSupport.readTraceContext(message.getMetadata()), message.getMetadata());
    }

    public static Map<String, String> buildMdcContext(TraceContext traceContext, Map<String, Object> source) {
        Map<String, String> context = new LinkedHashMap<>();
        if (traceContext != null) {
            putString(context, "trace", traceContext.getTraceId());
            putString(context, "span", traceContext.getSpanId());
        }
        putString(context, ContextAttributes.TRACE_NAME, TraceContextSupport.readTraceName(source));
        return context;
    }

    private static void putString(Map<String, String> target, String key, String value) {
        if (!StringValueSupport.isBlank(key) && !StringValueSupport.isBlank(value)) {
            target.put(key, value);
        }
    }
}
