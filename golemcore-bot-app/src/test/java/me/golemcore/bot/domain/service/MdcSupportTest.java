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

class MdcSupportTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
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
