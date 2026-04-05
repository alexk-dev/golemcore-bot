package me.golemcore.bot.domain.selfevolving.tactic;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TacticSearchMetricsServiceTest {

    private TacticSearchMetricsService metricsService;

    @BeforeEach
    void setUp() {
        metricsService = new TacticSearchMetricsService(
                Clock.fixed(Instant.parse("2026-04-01T20:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldTrackFallbackStateAndReason() {
        metricsService.recordFallback("bm25", "runtime unavailable");

        TacticSearchMetricsService.Snapshot snapshot = metricsService.snapshot();

        assertEquals(1L, snapshot.fallbackCount());
        assertEquals("bm25", snapshot.activeMode());
        assertEquals("runtime unavailable", snapshot.lastReason());
        assertTrue(snapshot.degraded());
    }

    @Test
    void shouldTrackQueryAndIndexDegradationCounts() {
        metricsService.recordActiveMode("hybrid", null);
        metricsService.recordQueryFailure("embed request failed");
        metricsService.recordIndexFailure("index refresh failed");

        TacticSearchMetricsService.Snapshot snapshot = metricsService.snapshot();

        assertEquals(1L, snapshot.degradedQueryFailureCount());
        assertEquals(1L, snapshot.degradedIndexFailureCount());
        assertEquals("hybrid", snapshot.activeMode());
        assertEquals("index refresh failed", snapshot.lastReason());
    }
}
