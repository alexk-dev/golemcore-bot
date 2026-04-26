package me.golemcore.bot.domain.service;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Utility helpers for scoped MDC propagation across scheduler and agent loop execution boundaries.
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

    public static void runWithContext(Map<String, String> context, Runnable runnable) {
        try (Scope ignored = withContext(context)) {
            runnable.run();
        }
    }

    /**
     * Submits {@code supplier} to the default async pool while propagating the caller's MDC into the worker thread. The
     * captured MDC is restored around the supplier invocation so log events emitted from inside carry the same
     * trace/span fields the caller had.
     */
    public static <T> CompletableFuture<T> supplyAsyncWithMdc(Supplier<T> supplier) {
        Map<String, String> captured = capture();
        return CompletableFuture.supplyAsync(() -> {
            Map<String, String> previous = capture();
            try {
                restore(captured);
                return supplier.get();
            } finally {
                restore(previous);
            }
        });
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
