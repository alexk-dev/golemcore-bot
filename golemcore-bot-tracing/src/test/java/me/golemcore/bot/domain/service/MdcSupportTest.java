package me.golemcore.bot.domain.service;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdcSupportTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void captureReturnsEmptyMapWhenMdcIsEmpty() {
        assertTrue(MdcSupport.capture().isEmpty());
    }

    @Test
    void captureReturnsDefensiveCopyOfCurrentMdc() {
        MDC.put("trace", "trace-copy");

        Map<String, String> captured = MdcSupport.capture();
        MDC.put("trace", "trace-mutated");

        assertEquals("trace-copy", captured.get("trace"));
        assertEquals("trace-mutated", MDC.get("trace"));
    }

    @Test
    void withContextMergesContextAndRestoresPreviousValues() {
        MDC.put("trace", "trace-before");
        MDC.put("span", "span-before");

        try (MdcSupport.Scope ignored = MdcSupport.withContext(Map.of("span", "span-current", "run", "run-1"))) {
            assertEquals("trace-before", MDC.get("trace"));
            assertEquals("span-current", MDC.get("span"));
            assertEquals("run-1", MDC.get("run"));
        }

        assertEquals("trace-before", MDC.get("trace"));
        assertEquals("span-before", MDC.get("span"));
        assertNull(MDC.get("run"));
    }

    @Test
    void restoreClearsMdcWhenContextIsNullOrEmpty() {
        MDC.put("trace", "trace-clear");

        MdcSupport.restore(null);

        assertNull(MDC.get("trace"));

        MDC.put("span", "span-clear");

        MdcSupport.restore(Map.of());

        assertNull(MDC.get("span"));
    }

    @Test
    void runWithContextRestoresPreviousValuesAfterFailure() {
        MDC.put("trace", "trace-before");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> MdcSupport.runWithContext(Map.of("trace", "trace-current"), () -> {
                    assertEquals("trace-current", MDC.get("trace"));
                    throw new IllegalStateException("boom");
                }));

        assertEquals("boom", exception.getMessage());
        assertEquals("trace-before", MDC.get("trace"));
    }

    @Test
    void supplyAsyncWithMdcCapturesSubmitterMdcAndAppliesInWorkerThread()
            throws ExecutionException, InterruptedException {
        MDC.put("trace", "trace-abc");
        MDC.put("span", "span-xyz");

        AtomicReference<String> observedTrace = new AtomicReference<>();
        AtomicReference<String> observedSpan = new AtomicReference<>();
        AtomicReference<Long> workerThreadId = new AtomicReference<>();

        CompletableFuture<String> future = MdcSupport.supplyAsyncWithMdc(() -> {
            observedTrace.set(MDC.get("trace"));
            observedSpan.set(MDC.get("span"));
            workerThreadId.set(Thread.currentThread().getId());
            return "ok";
        });

        assertEquals("ok", future.get());
        assertEquals("trace-abc", observedTrace.get());
        assertEquals("span-xyz", observedSpan.get());
        assertNotEquals(Thread.currentThread().getId(), workerThreadId.get(),
                "worker must execute on a background thread");
    }

    @Test
    void supplyAsyncWithMdcDoesNotLeakMdcIntoWorkerThreadAfterCompletion()
            throws ExecutionException, InterruptedException {
        MDC.put("trace", "trace-leak-check");

        CompletableFuture<Map<String, String>> first = MdcSupport.supplyAsyncWithMdc(() -> {
            Map<String, String> snapshot = new HashMap<>();
            snapshot.put("trace", MDC.get("trace"));
            return snapshot;
        });
        first.get();

        MDC.clear();

        CompletableFuture<String> second = MdcSupport.supplyAsyncWithMdc(() -> MDC.get("trace"));
        assertNull(second.get(), "subsequent submission with empty MDC must see null");
    }
}
