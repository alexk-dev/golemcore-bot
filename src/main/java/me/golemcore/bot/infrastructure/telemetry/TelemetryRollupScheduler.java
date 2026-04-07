package me.golemcore.bot.infrastructure.telemetry;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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

    private static final String COMPOSITE_KEY_SEPARATOR = "\\|";

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
                flushModelUsage(rollup, pendingRollup, distinctId);
                flushPluginUsage(rollup, pendingRollup, distinctId);
            } catch (RuntimeException exception) {
                telemetryRollupStore.restoreReadyRollups(buildUnsentRollups(rollups, pendingRollup, rollupIndex + 1));
                throw exception;
            }
        }
    }

    private void flushModelUsage(TelemetryRollupStore.BackendRollup rollup,
            TelemetryRollupStore.BackendRollup pendingRollup, String distinctId) {
        for (Map.Entry<String, TelemetryRollupStore.ModelUsageSummary> entry : rollup.getModelUsage().entrySet()) {
            String[] parts = entry.getKey().split(COMPOSITE_KEY_SEPARATOR, 2);
            String modelId = parts[0];
            String tier = parts.length > 1 ? parts[1] : "balanced";
            TelemetryRollupStore.ModelUsageSummary summary = entry.getValue();

            Map<String, Object> properties = basePeriodProperties(rollup);
            properties.put("model_id", modelId);
            properties.put("tier", tier);
            properties.put("request_count", summary.getRequestCount());
            properties.put("input_tokens", summary.getInputTokens());
            properties.put("output_tokens", summary.getOutputTokens());
            properties.put("total_tokens", summary.getTotalTokens());

            telemetryEventPublisher.publishAnonymousEvent("model_usage", distinctId, properties);
            pendingRollup.getModelUsage().remove(entry.getKey());
        }
    }

    private void flushPluginUsage(TelemetryRollupStore.BackendRollup rollup,
            TelemetryRollupStore.BackendRollup pendingRollup, String distinctId) {
        for (Map.Entry<String, Long> entry : rollup.getPluginUsage().entrySet()) {
            String counterKey = entry.getKey();
            Long count = entry.getValue();

            Map<String, Object> properties = basePeriodProperties(rollup);
            parsePluginCounterKey(counterKey, properties);
            properties.put("count", count);

            telemetryEventPublisher.publishAnonymousEvent("plugin_usage", distinctId, properties);
            pendingRollup.getPluginUsage().remove(counterKey);
        }
    }

    private void parsePluginCounterKey(String counterKey, Map<String, Object> properties) {
        int firstColon = counterKey.indexOf(':');
        if (firstColon < 0) {
            properties.put("action", counterKey);
            properties.put("plugin_id", "unknown");
            return;
        }
        String action = counterKey.substring(0, firstColon);
        String remainder = counterKey.substring(firstColon + 1);
        properties.put("action", action);

        if ("action".equals(action)) {
            int secondColon = remainder.indexOf(':');
            if (secondColon >= 0) {
                properties.put("plugin_id", remainder.substring(0, secondColon));
                properties.put("route", remainder.substring(secondColon + 1));
            } else {
                properties.put("plugin_id", remainder);
            }
        } else if ("save".equals(action)) {
            properties.put("plugin_id", remainder);
        } else {
            properties.put("plugin_id", remainder);
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
        List<TelemetryRollupStore.BackendRollup> unsentRollups = new ArrayList<>();
        if (pendingRollup.hasData()) {
            unsentRollups.add(pendingRollup);
        }
        for (int index = remainingStartIndex; index < originalRollups.size(); index++) {
            unsentRollups.add(originalRollups.get(index).copy());
        }
        return unsentRollups;
    }

    private Map<String, Object> basePeriodProperties(TelemetryRollupStore.BackendRollup rollup) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("period_start", rollup.getPeriodStart().toString());
        properties.put("period_end", rollup.getPeriodEnd().toString());
        properties.put("bucket_minutes", rollup.getBucketMinutes());
        return properties;
    }
}
