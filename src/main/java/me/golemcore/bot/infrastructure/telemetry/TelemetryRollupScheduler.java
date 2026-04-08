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
        List<TelemetryRollupStore.BackendRollup> rollups = telemetryRollupStore.collectReadyRollups();
        for (int rollupIndex = 0; rollupIndex < rollups.size(); rollupIndex++) {
            TelemetryRollupStore.BackendRollup rollup = rollups.get(rollupIndex);
            TelemetryRollupStore.BackendRollup pendingRollup = rollup.copy();
            try {
                flushModelUsage(rollup, pendingRollup);
                flushPluginUsage(rollup, pendingRollup);
            } catch (RuntimeException exception) {
                telemetryRollupStore.restoreReadyRollups(buildUnsentRollups(rollups, pendingRollup, rollupIndex + 1));
                throw exception;
            }
        }
    }

    private void flushModelUsage(TelemetryRollupStore.BackendRollup rollup,
            TelemetryRollupStore.BackendRollup pendingRollup) {
        for (Map.Entry<String, TelemetryRollupStore.ModelUsageSummary> entry : rollup.getModelUsage().entrySet()) {
            String[] parts = entry.getKey().split(COMPOSITE_KEY_SEPARATOR, 2);
            String modelId = parts[0];
            String tier = parts.length > 1 ? parts[1] : "balanced";
            TelemetryRollupStore.ModelUsageSummary summary = entry.getValue();

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model_name", modelId);
            params.put("tier", tier);
            params.put("feature_area", "llm");
            params.put("request_count", summary.getRequestCount());
            params.put("input_tokens", summary.getInputTokens());
            params.put("output_tokens", summary.getOutputTokens());
            params.put("total_tokens", summary.getTotalTokens());

            telemetryEventPublisher.publishEvent("model_usage", params);
            pendingRollup.getModelUsage().remove(entry.getKey());
        }
    }

    private void flushPluginUsage(TelemetryRollupStore.BackendRollup rollup,
            TelemetryRollupStore.BackendRollup pendingRollup) {
        for (Map.Entry<String, Long> entry : rollup.getPluginUsage().entrySet()) {
            String counterKey = entry.getKey();
            Long count = entry.getValue();

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("feature_area", "plugins");
            parsePluginCounterKey(counterKey, params);
            params.put("count", count);

            telemetryEventPublisher.publishEvent("plugin_usage", params);
            pendingRollup.getPluginUsage().remove(counterKey);
        }
    }

    private void parsePluginCounterKey(String counterKey, Map<String, Object> params) {
        int firstColon = counterKey.indexOf(':');
        if (firstColon < 0) {
            params.put("action_name", counterKey);
            params.put("plugin_id", "unknown");
            return;
        }
        String action = counterKey.substring(0, firstColon);
        String remainder = counterKey.substring(firstColon + 1);
        params.put("action_name", action);

        if ("action".equals(action)) {
            int secondColon = remainder.indexOf(':');
            if (secondColon >= 0) {
                params.put("plugin_id", remainder.substring(0, secondColon));
                params.put("action_route", remainder.substring(secondColon + 1));
            } else {
                params.put("plugin_id", remainder);
            }
        } else {
            params.put("plugin_id", remainder);
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
}
