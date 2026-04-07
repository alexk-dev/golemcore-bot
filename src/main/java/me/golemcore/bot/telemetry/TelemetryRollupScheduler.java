package me.golemcore.bot.telemetry;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class TelemetryRollupScheduler {

    private final TelemetryRollupStore telemetryRollupStore;
    private final TelemetryEventPublisher telemetryEventPublisher;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "telemetry-rollups");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    void start() {
        executor.scheduleAtFixedRate(this::flushReadyRollupsSafely, 1, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    public void flushReadyRollupsNow() {
        String distinctId = "backend:" + telemetryRollupStore.getAnonymousInstanceId();
        List<TelemetryRollupStore.BackendRollup> rollups = telemetryRollupStore.collectReadyRollups();
        for (TelemetryRollupStore.BackendRollup rollup : rollups) {
            if (!rollup.getModelUsage().isEmpty()) {
                telemetryEventPublisher.publishAnonymousEvent("model_usage_rollup", distinctId,
                        buildProperties(rollup, "models", rollup.getModelUsage()));
            }
            if (!rollup.getTierUsage().isEmpty()) {
                telemetryEventPublisher.publishAnonymousEvent("tier_usage_rollup", distinctId,
                        buildProperties(rollup, "tiers", rollup.getTierUsage()));
            }
            if (!rollup.getPluginUsage().isEmpty()) {
                telemetryEventPublisher.publishAnonymousEvent("plugin_usage_rollup", distinctId,
                        buildProperties(rollup, "plugin_counters", rollup.getPluginUsage()));
            }
        }
    }

    private void flushReadyRollupsSafely() {
        try {
            flushReadyRollupsNow();
        } catch (Exception ignored) {
            // Keep the scheduler resilient to transient telemetry delivery failures.
        }
    }

    private Map<String, Object> buildProperties(TelemetryRollupStore.BackendRollup rollup, String key, Object value) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("period_start", rollup.getPeriodStart().toString());
        properties.put("period_end", rollup.getPeriodEnd().toString());
        properties.put("bucket_minutes", rollup.getBucketMinutes());
        properties.put(key, value);
        return properties;
    }
}
