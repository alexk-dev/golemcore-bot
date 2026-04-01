package me.golemcore.bot.domain.service;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory counters and gauges describing tactic-search fallback and
 * degradation state.
 */
@Service
public class TacticSearchMetricsService {

    private static final String DEFAULT_MODE = "bm25";

    private final Clock clock;
    private final AtomicLong fallbackCount = new AtomicLong();
    private final AtomicLong degradedQueryFailureCount = new AtomicLong();
    private final AtomicLong degradedIndexFailureCount = new AtomicLong();
    private final AtomicReference<String> activeMode = new AtomicReference<>(DEFAULT_MODE);
    private final AtomicReference<String> lastReason = new AtomicReference<>();
    private final AtomicReference<Boolean> degraded = new AtomicReference<>(false);
    private final AtomicReference<Instant> updatedAt = new AtomicReference<>();

    public TacticSearchMetricsService(Clock clock) {
        this.clock = clock;
        this.updatedAt.set(clock.instant());
    }

    public void recordActiveMode(String mode, String reason) {
        activeMode.set(normalizeMode(mode));
        lastReason.set(normalizeReason(reason));
        degraded.set(normalizeReason(reason) != null);
        updatedAt.set(clock.instant());
    }

    public void recordFallback(String mode, String reason) {
        fallbackCount.incrementAndGet();
        activeMode.set(normalizeMode(mode));
        lastReason.set(normalizeReason(reason));
        degraded.set(true);
        updatedAt.set(clock.instant());
    }

    public void recordQueryFailure(String reason) {
        degradedQueryFailureCount.incrementAndGet();
        lastReason.set(normalizeReason(reason));
        degraded.set(true);
        updatedAt.set(clock.instant());
    }

    public void recordIndexFailure(String reason) {
        degradedIndexFailureCount.incrementAndGet();
        lastReason.set(normalizeReason(reason));
        degraded.set(true);
        updatedAt.set(clock.instant());
    }

    public Snapshot snapshot() {
        return new Snapshot(
                fallbackCount.get(),
                degradedQueryFailureCount.get(),
                degradedIndexFailureCount.get(),
                activeMode.get(),
                lastReason.get(),
                degraded.get(),
                updatedAt.get());
    }

    public record Snapshot(
            long fallbackCount,
            long degradedQueryFailureCount,
            long degradedIndexFailureCount,
            String activeMode,
            String lastReason,
            boolean degraded,
            Instant updatedAt) {
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return DEFAULT_MODE;
        }
        return mode.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.trim();
    }
}
