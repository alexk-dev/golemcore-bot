package me.golemcore.bot.telemetry;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
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
        for (int rollupIndex = 0; rollupIndex < rollups.size(); rollupIndex++) {
            TelemetryRollupStore.BackendRollup rollup = rollups.get(rollupIndex);
            TelemetryRollupStore.BackendRollup pendingRollup = rollup.copy();
            try {
                if (!rollup.getModelUsage().isEmpty()) {
                    telemetryEventPublisher.publishAnonymousEvent("model_usage_rollup", distinctId,
                            buildProperties(rollup, "models", rollup.getModelUsage()));
                    pendingRollup.getModelUsage().clear();
                }
                if (!rollup.getTierUsage().isEmpty()) {
                    telemetryEventPublisher.publishAnonymousEvent("tier_usage_rollup", distinctId,
                            buildProperties(rollup, "tiers", rollup.getTierUsage()));
                    pendingRollup.getTierUsage().clear();
                }
                if (!rollup.getPluginUsage().isEmpty()) {
                    telemetryEventPublisher.publishAnonymousEvent("plugin_usage_rollup", distinctId,
                            buildProperties(rollup, "plugin_counters", rollup.getPluginUsage()));
                    pendingRollup.getPluginUsage().clear();
                }
            } catch (RuntimeException exception) {
                telemetryRollupStore.restoreReadyRollups(buildUnsentRollups(rollups, pendingRollup, rollupIndex + 1));
                throw exception;
            }
        }
    }

    private void flushReadyRollupsSafely() {
        try {
            flushReadyRollupsNow();
        } catch (Exception exception) {
            log.warn("Telemetry rollup flush failed: {}", exception.getMessage()); // NOSONAR
        }
    }

    private List<TelemetryRollupStore.BackendRollup> buildUnsentRollups(
            List<TelemetryRollupStore.BackendRollup> originalRollups,
            TelemetryRollupStore.BackendRollup pendingRollup,
            int remainingStartIndex) {
        List<TelemetryRollupStore.BackendRollup> unsentRollups = new java.util.ArrayList<>();
        if (pendingRollup.hasData()) {
            unsentRollups.add(pendingRollup);
        }
        for (int index = remainingStartIndex; index < originalRollups.size(); index++) {
            unsentRollups.add(originalRollups.get(index).copy());
        }
        return unsentRollups;
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
