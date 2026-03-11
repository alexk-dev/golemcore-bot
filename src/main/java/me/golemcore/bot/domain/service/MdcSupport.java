package me.golemcore.bot.domain.service;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility helpers for scoped MDC propagation across scheduler and agent loop
 * execution boundaries.
 */
public final class MdcSupport {

    private MdcSupport() {
    }

    public static Map<String, String> capture() {
        Map<String, String> copy = MDC.getCopyOfContextMap();
        if (copy == null || copy.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(copy);
    }

    public static Scope withContext(Map<String, String> context) {
        Map<String, String> previous = capture();
        Map<String, String> merged = new LinkedHashMap<>(previous);
        if (context != null && !context.isEmpty()) {
            merged.putAll(context);
        }
        restore(merged);
        return new Scope(previous);
    }

    public static void restore(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(context);
    }

    public static final class Scope implements AutoCloseable {

        private final Map<String, String> previous;

        private Scope(Map<String, String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            restore(previous);
        }
    }
}
