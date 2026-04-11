package me.golemcore.bot.infrastructure.telemetry;

import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
class TelemetryRollupSchedulerTest {

    private RuntimeConfigService runtimeConfigService;
    private TelemetryEventPublisher publisher;
    private MutableClock clock;
    private TelemetryRollupStore store;
    private TelemetryRollupScheduler scheduler;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        publisher = mock(TelemetryEventPublisher.class);
        clock = new MutableClock(Instant.parse("2026-04-06T10:05:00Z"));

        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);

        store = new TelemetryRollupStore(runtimeConfigService, clock);
        scheduler = new TelemetryRollupScheduler(store, publisher);
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldFlushFlatModelAndPluginEventsAndResetReadyBuckets() {
        store.recordModelUsage("gpt-5", "smart", 100, 25, 125);
        store.recordPluginAction("plugin-browser", "reload");

        clock.advanceSeconds(3600);
        scheduler.flushReadyRollupsNow();

        ArgumentCaptor<Map<String, Object>> modelCaptor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishEvent(eq("model_usage"), modelCaptor.capture());
        Map<String, Object> modelParams = modelCaptor.getValue();
        assertEquals("gpt-5", modelParams.get("model_name"));
        assertEquals("smart", modelParams.get("tier"));
        assertEquals("llm", modelParams.get("feature_area"));
        assertEquals(1L, modelParams.get("request_count"));
        assertEquals(100L, modelParams.get("input_tokens"));

        ArgumentCaptor<Map<String, Object>> pluginCaptor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishEvent(eq("plugin_usage"), pluginCaptor.capture());
        Map<String, Object> pluginParams = pluginCaptor.getValue();
        assertEquals("action", pluginParams.get("action_name"));
        assertEquals("plugin-browser", pluginParams.get("plugin_id"));
        assertEquals("reload", pluginParams.get("action_route"));
        assertEquals("plugins", pluginParams.get("feature_area"));

        clearInvocations(publisher);
        scheduler.flushReadyRollupsNow();

        verify(publisher, never()).publishEvent(eq("model_usage"), anyMap());
        verify(publisher, never()).publishEvent(eq("plugin_usage"), anyMap());
    }

    @Test
    void shouldRestoreUnsentEventsWhenPublishFailsMidRollup() {
        AtomicInteger pluginCallCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            String eventName = invocation.getArgument(0);
            if ("plugin_usage".equals(eventName) && pluginCallCount.getAndIncrement() == 0) {
                throw new IllegalStateException("ga4 unavailable");
            }
            return null;
        }).when(publisher).publishEvent(anyString(), anyMap());

        store.recordModelUsage("gpt-5", "smart", 100, 25, 125);
        store.recordPluginAction("plugin-browser", "reload");

        clock.advanceSeconds(3600);

        assertThrows(IllegalStateException.class, () -> scheduler.flushReadyRollupsNow());

        scheduler.flushReadyRollupsNow();

        verify(publisher).publishEvent(eq("model_usage"), anyMap());
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(eq("plugin_usage"), anyMap());

        clearInvocations(publisher);
        scheduler.flushReadyRollupsNow();

        verify(publisher, never()).publishEvent(eq("model_usage"), anyMap());
        verify(publisher, never()).publishEvent(eq("plugin_usage"), anyMap());
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
