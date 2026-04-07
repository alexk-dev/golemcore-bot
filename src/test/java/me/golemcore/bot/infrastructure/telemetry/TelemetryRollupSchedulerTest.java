package me.golemcore.bot.infrastructure.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryRollupSchedulerTest {

    private RuntimeConfigService runtimeConfigService;
    private StoragePort storagePort;
    private TelemetryEventPublisher publisher;
    private MutableClock clock;
    private TelemetryRollupStore store;
    private TelemetryRollupScheduler scheduler;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        storagePort = mock(StoragePort.class);
        publisher = mock(TelemetryEventPublisher.class);
        clock = new MutableClock(Instant.parse("2026-04-06T10:05:00Z"));

        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);
        when(storagePort.exists("dashboard", "telemetry/backend-instance-id.txt"))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(storagePort.putTextAtomic(eq("dashboard"), eq("telemetry/backend-instance-id.txt"),
                org.mockito.ArgumentMatchers.anyString(), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(null));

        store = new TelemetryRollupStore(runtimeConfigService, storagePort, new ObjectMapper(), clock);
        scheduler = new TelemetryRollupScheduler(store, publisher);
    }

    @Test
    void shouldFlushHourlyRollupsOnceAndResetReadyBuckets() {
        store.recordModelUsage("gpt-5", "smart", 100, 25, 125);
        store.recordPluginAction("plugin-browser", "reload");

        clock.advanceSeconds(3600);
        scheduler.flushReadyRollupsNow();

        verify(publisher).publishAnonymousEvent(eq("model_usage_rollup"),
                eq("backend:" + store.getAnonymousInstanceId()),
                anyMap());
        verify(publisher).publishAnonymousEvent(eq("tier_usage_rollup"),
                eq("backend:" + store.getAnonymousInstanceId()),
                anyMap());
        verify(publisher).publishAnonymousEvent(eq("plugin_usage_rollup"),
                eq("backend:" + store.getAnonymousInstanceId()),
                anyMap());

        clearInvocations(publisher);
        scheduler.flushReadyRollupsNow();

        verify(publisher, never()).publishAnonymousEvent(eq("model_usage_rollup"),
                eq("backend:" + store.getAnonymousInstanceId()), anyMap());
        verify(publisher, never()).publishAnonymousEvent(eq("tier_usage_rollup"),
                eq("backend:" + store.getAnonymousInstanceId()), anyMap());
        verify(publisher, never()).publishAnonymousEvent(eq("plugin_usage_rollup"),
                eq("backend:" + store.getAnonymousInstanceId()), anyMap());
    }

    @Test
    void shouldRestoreOnlyUnsentEventTypesWhenPublishFailsMidRollup() {
        AtomicBoolean failTierUpload = new AtomicBoolean(true);
        doAnswer(invocation -> {
            String eventName = invocation.getArgument(0);
            if ("tier_usage_rollup".equals(eventName) && failTierUpload.getAndSet(false)) {
                throw new IllegalStateException("posthog unavailable");
            }
            return null;
        }).when(publisher).publishAnonymousEvent(anyString(), anyString(), anyMap());

        store.recordModelUsage("gpt-5", "smart", 100, 25, 125);
        store.recordPluginAction("plugin-browser", "reload");

        clock.advanceSeconds(3600);

        assertThrows(IllegalStateException.class, () -> scheduler.flushReadyRollupsNow());

        scheduler.flushReadyRollupsNow();

        verify(publisher).publishAnonymousEvent(eq("model_usage_rollup"),
                eq("backend:" + store.getAnonymousInstanceId()),
                anyMap());
        verify(publisher, org.mockito.Mockito.times(2)).publishAnonymousEvent(eq("tier_usage_rollup"),
                eq("backend:" + store.getAnonymousInstanceId()),
                anyMap());
        verify(publisher).publishAnonymousEvent(eq("plugin_usage_rollup"),
                eq("backend:" + store.getAnonymousInstanceId()),
                anyMap());

        clearInvocations(publisher);
        scheduler.flushReadyRollupsNow();

        verify(publisher, never()).publishAnonymousEvent(eq("model_usage_rollup"),
                eq("backend:" + store.getAnonymousInstanceId()), anyMap());
        verify(publisher, never()).publishAnonymousEvent(eq("tier_usage_rollup"),
                eq("backend:" + store.getAnonymousInstanceId()), anyMap());
        verify(publisher, never()).publishAnonymousEvent(eq("plugin_usage_rollup"),
                eq("backend:" + store.getAnonymousInstanceId()), anyMap());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
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
