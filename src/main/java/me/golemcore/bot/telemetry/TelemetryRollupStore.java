package me.golemcore.bot.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.StoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TelemetryRollupStore {

    private static final Logger log = LoggerFactory.getLogger(TelemetryRollupStore.class);
    private static final String STORAGE_DIRECTORY = "dashboard";
    private static final String INSTANCE_ID_PATH = "telemetry/backend-instance-id.txt";

    private final RuntimeConfigService runtimeConfigService;
    private final StoragePort storagePort;
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String anonymousInstanceId;

    private BackendRollup currentBucket;
    private final List<BackendRollup> readyBuckets = new ArrayList<>();

    public TelemetryRollupStore(RuntimeConfigService runtimeConfigService, StoragePort storagePort,
            ObjectMapper objectMapper,
            Clock clock) {
        this.runtimeConfigService = runtimeConfigService;
        this.storagePort = storagePort;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.anonymousInstanceId = loadOrCreateAnonymousInstanceId();
        this.currentBucket = createEmptyBucket(clock.instant());
    }

    public synchronized String getAnonymousInstanceId() {
        return anonymousInstanceId;
    }

    public synchronized void recordModelUsage(String modelId, String tier, int inputTokens, int outputTokens,
            int totalTokens) {
        if (!runtimeConfigService.isTelemetryEnabled()) {
            return;
        }
        rotateBuckets(clock.instant());

        String normalizedModelId = normalizeKey(modelId, "unknown-model");
        String normalizedTier = normalizeKey(tier, "balanced");
        ModelUsageSummary summary = currentBucket.getModelUsage()
                .computeIfAbsent(normalizedModelId, ignored -> new ModelUsageSummary());
        summary.setRequestCount(summary.getRequestCount() + 1);
        summary.setInputTokens(summary.getInputTokens() + Math.max(0, inputTokens));
        summary.setOutputTokens(summary.getOutputTokens() + Math.max(0, outputTokens));
        summary.setTotalTokens(summary.getTotalTokens() + Math.max(0, totalTokens));
        currentBucket.getTierUsage().merge(normalizedTier, 1L, Long::sum);
    }

    public synchronized void recordPluginInstall(String pluginId) {
        recordPluginCounter("install", pluginId);
    }

    public synchronized void recordPluginUninstall(String pluginId) {
        recordPluginCounter("uninstall", pluginId);
    }

    public synchronized void recordPluginAction(String routeKey, String actionId) {
        recordPluginCounter("action", normalizeKey(routeKey, "unknown") + ":" + normalizeKey(actionId, "unknown"));
    }

    public synchronized void recordPluginSettingsSave(String routeKey) {
        recordPluginCounter("save", routeKey);
    }

    public synchronized List<BackendRollup> collectReadyRollups() {
        rotateBuckets(clock.instant());
        List<BackendRollup> rollups = new ArrayList<>(readyBuckets);
        readyBuckets.clear();
        return rollups;
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
        return new BackendRollup(start, end, 60, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private String loadOrCreateAnonymousInstanceId() {
        try {
            boolean exists = storagePort.exists(STORAGE_DIRECTORY, INSTANCE_ID_PATH).join();
            if (exists) {
                String stored = storagePort.getText(STORAGE_DIRECTORY, INSTANCE_ID_PATH).join();
                if (stored != null && !stored.isBlank()) {
                    return stored.trim();
                }
            }
            String generated = UUID.randomUUID().toString();
            storagePort.putTextAtomic(STORAGE_DIRECTORY, INSTANCE_ID_PATH, generated, true).join();
            return generated;
        } catch (Exception exception) {
            log.warn("Failed to load telemetry backend instance id: {}", exception.getMessage());
            return UUID.randomUUID().toString();
        }
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
        private Map<String, Long> tierUsage;
        private Map<String, Long> pluginUsage;

        boolean hasData() {
            return !modelUsage.isEmpty() || !tierUsage.isEmpty() || !pluginUsage.isEmpty();
        }

        BackendRollup copy() {
            Map<String, ModelUsageSummary> copiedModelUsage = new LinkedHashMap<>();
            modelUsage.forEach((key, value) -> copiedModelUsage.put(key, value.copy()));
            return new BackendRollup(
                    periodStart,
                    periodEnd,
                    bucketMinutes,
                    copiedModelUsage,
                    new LinkedHashMap<>(tierUsage),
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
