package me.golemcore.bot.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelemetryRollupStoreTest {

    private RuntimeConfigService runtimeConfigService;
    private StoragePort storagePort;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        storagePort = mock(StoragePort.class);
        clock = new MutableClock(Instant.parse("2026-04-06T10:05:00Z"));
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);
        when(storagePort.exists(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(false));
        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void shouldReusePersistedAnonymousInstanceIdWhenAvailable() {
        when(storagePort.exists(eq("dashboard"), eq("telemetry/backend-instance-id.txt")))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(storagePort.getText(eq("dashboard"), eq("telemetry/backend-instance-id.txt")))
                .thenReturn(CompletableFuture.completedFuture("existing-id\n"));

        TelemetryRollupStore store = new TelemetryRollupStore(runtimeConfigService, storagePort, new ObjectMapper(),
                clock);

        assertEquals("existing-id", store.getAnonymousInstanceId());
    }

    @Test
    void shouldNormalizeUsageAndRotateCompletedBuckets() {
        TelemetryRollupStore store = new TelemetryRollupStore(runtimeConfigService, storagePort, new ObjectMapper(),
                clock);

        store.recordModelUsage(" ", " ", -10, 20, -30);
        store.recordPluginInstall("browser");

        clock.advanceSeconds(3600);
        List<TelemetryRollupStore.BackendRollup> readyRollups = store.collectReadyRollups();

        assertEquals(1, readyRollups.size());
        TelemetryRollupStore.BackendRollup rollup = readyRollups.getFirst();
        assertEquals(1L, rollup.getTierUsage().get("balanced"));
        assertEquals(1L, rollup.getPluginUsage().get("install:browser"));
        assertTrue(rollup.getModelUsage().containsKey("unknown-model"));

        TelemetryRollupStore.ModelUsageSummary summary = rollup.getModelUsage().get("unknown-model");
        assertEquals(1L, summary.getRequestCount());
        assertEquals(0L, summary.getInputTokens());
        assertEquals(20L, summary.getOutputTokens());
        assertEquals(0L, summary.getTotalTokens());
    }

    @Test
    void shouldRestoreOnlyNonEmptyDefensiveCopies() {
        TelemetryRollupStore store = new TelemetryRollupStore(runtimeConfigService, storagePort, new ObjectMapper(),
                clock);
        TelemetryRollupStore.BackendRollup emptyRollup = new TelemetryRollupStore.BackendRollup(
                Instant.parse("2026-04-06T09:00:00Z"),
                Instant.parse("2026-04-06T10:00:00Z"),
                60,
                new java.util.LinkedHashMap<>(),
                new java.util.LinkedHashMap<>(),
                new java.util.LinkedHashMap<>());
        TelemetryRollupStore.ModelUsageSummary usageSummary = new TelemetryRollupStore.ModelUsageSummary();
        usageSummary.setRequestCount(2L);
        usageSummary.setInputTokens(10L);
        usageSummary.setOutputTokens(5L);
        usageSummary.setTotalTokens(15L);
        TelemetryRollupStore.BackendRollup populatedRollup = new TelemetryRollupStore.BackendRollup(
                Instant.parse("2026-04-06T10:00:00Z"),
                Instant.parse("2026-04-06T11:00:00Z"),
                60,
                new java.util.LinkedHashMap<>(Map.of("gpt-5", usageSummary)),
                new java.util.LinkedHashMap<>(Map.of("smart", 2L)),
                new java.util.LinkedHashMap<>());

        store.restoreReadyRollups(List.of(emptyRollup, populatedRollup));

        usageSummary.setRequestCount(99L);
        List<TelemetryRollupStore.BackendRollup> restored = store.collectReadyRollups();

        assertEquals(1, restored.size());
        assertNotEquals(99L, restored.getFirst().getModelUsage().get("gpt-5").getRequestCount());
        assertEquals(2L, restored.getFirst().getTierUsage().get("smart"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }
    }
}
