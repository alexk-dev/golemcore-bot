package me.golemcore.bot.infrastructure.telemetry;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.TelemetryRollupPort;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class TelemetryRollupStore implements TelemetryRollupPort {

    private final RuntimeConfigService runtimeConfigService;
    private final Clock clock;

    private BackendRollup currentBucket;
    private final List<BackendRollup> readyBuckets = new ArrayList<>();

    public TelemetryRollupStore(RuntimeConfigService runtimeConfigService, Clock clock) {
        this.runtimeConfigService = runtimeConfigService;
        this.clock = clock;
        this.currentBucket = createEmptyBucket(clock.instant());
    }

    @Override
    public synchronized void recordModelUsage(String modelId, String tier, int inputTokens, int outputTokens,
            int totalTokens) {
        if (!runtimeConfigService.isTelemetryEnabled()) {
            return;
        }
        rotateBuckets(clock.instant());

        String normalizedModelId = normalizeKey(modelId, "unknown-model");
        String normalizedTier = normalizeKey(tier, "balanced");
        String compositeKey = normalizedModelId + "|" + normalizedTier;
        ModelUsageSummary summary = currentBucket.getModelUsage()
                .computeIfAbsent(compositeKey, ignored -> new ModelUsageSummary());
        summary.setRequestCount(summary.getRequestCount() + 1);
        summary.setInputTokens(summary.getInputTokens() + Math.max(0, inputTokens));
        summary.setOutputTokens(summary.getOutputTokens() + Math.max(0, outputTokens));
        summary.setTotalTokens(summary.getTotalTokens() + Math.max(0, totalTokens));
    }

    @Override
    public synchronized void recordPluginInstall(String pluginId) {
        recordPluginCounter("install", pluginId);
    }

    @Override
    public synchronized void recordPluginUninstall(String pluginId) {
        recordPluginCounter("uninstall", pluginId);
    }

    @Override
    public synchronized void recordPluginAction(String routeKey, String actionId) {
        recordPluginCounter("action", normalizeKey(routeKey, "unknown") + ":" + normalizeKey(actionId, "unknown"));
    }

    @Override
    public synchronized void recordPluginSettingsSave(String routeKey) {
        recordPluginCounter("save", routeKey);
    }

    public synchronized List<BackendRollup> collectReadyRollups() {
        rotateBuckets(clock.instant());
        List<BackendRollup> rollups = new ArrayList<>(readyBuckets);
        readyBuckets.clear();
        return rollups;
    }

    public synchronized void restoreReadyRollups(List<BackendRollup> rollups) {
        if (rollups == null || rollups.isEmpty()) {
            return;
        }
        List<BackendRollup> restored = new ArrayList<>();
        for (BackendRollup rollup : rollups) {
            if (rollup != null && rollup.hasData()) {
                restored.add(rollup.copy());
            }
        }
        if (restored.isEmpty()) {
            return;
        }
        readyBuckets.addAll(0, restored);
    }

    private void recordPluginCounter(String kind, String key) {
        if (!runtimeConfigService.isTelemetryEnabled()) {
            return;
        }
        rotateBuckets(clock.instant());
        String normalizedCounterKey = kind + ":" + normalizeKey(key, "unknown");
        currentBucket.getPluginUsage().merge(normalizedCounterKey, 1L, Long::sum);
    }

    private void rotateBuckets(Instant reference) {
        Instant currentBucketEnd = currentBucket.getPeriodEnd();
        while (!reference.isBefore(currentBucketEnd)) {
            if (currentBucket.hasData()) {
                readyBuckets.add(currentBucket.copy());
            }
            currentBucket = createEmptyBucket(currentBucketEnd);
            currentBucketEnd = currentBucket.getPeriodEnd();
        }
    }

    private BackendRollup createEmptyBucket(Instant reference) {
        Instant start = reference.truncatedTo(ChronoUnit.HOURS);
        Instant end = start.plus(1, ChronoUnit.HOURS);
        return new BackendRollup(start, end, 60, new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private String normalizeKey(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    @Data
    @AllArgsConstructor
    public static class BackendRollup {
        private Instant periodStart;
        private Instant periodEnd;
        private int bucketMinutes;
        private Map<String, ModelUsageSummary> modelUsage;
        private Map<String, Long> pluginUsage;

        boolean hasData() {
            return !modelUsage.isEmpty() || !pluginUsage.isEmpty();
        }

        BackendRollup copy() {
            Map<String, ModelUsageSummary> copiedModelUsage = new LinkedHashMap<>();
            modelUsage.forEach((key, value) -> copiedModelUsage.put(key, value.copy()));
            return new BackendRollup(
                    periodStart,
                    periodEnd,
                    bucketMinutes,
                    copiedModelUsage,
                    new LinkedHashMap<>(pluginUsage));
        }
    }

    @Data
    public static class ModelUsageSummary {
        private long requestCount;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;

        ModelUsageSummary copy() {
            ModelUsageSummary copy = new ModelUsageSummary();
            copy.setRequestCount(requestCount);
            copy.setInputTokens(inputTokens);
            copy.setOutputTokens(outputTokens);
            copy.setTotalTokens(totalTokens);
            return copy;
        }
    }
}
