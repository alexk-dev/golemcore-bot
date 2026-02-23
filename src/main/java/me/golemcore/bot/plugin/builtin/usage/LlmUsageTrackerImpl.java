package me.golemcore.bot.plugin.builtin.usage;

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

import me.golemcore.bot.domain.model.LlmUsage;
import me.golemcore.bot.domain.model.UsageMetric;
import me.golemcore.bot.domain.model.UsageStats;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.StoragePort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link LlmUsageTracker} with persistence to JSONL
 * files.
 *
 * <p>
 * Features:
 * <ul>
 * <li>In-memory aggregation with concurrent access support</li>
 * <li>Persists usage records to JSONL files in storage (one line per call)</li>
 * <li>Loads persisted data on startup (last 7 days)</li>
 * <li>Automatic eviction of old records via background thread (hourly)</li>
 * <li>Per-provider and per-model statistics breakdowns</li>
 * </ul>
 *
 * <p>
 * Usage data is stored in the {@code usage/} directory with files named
 * {@code usage_<timestamp>.jsonl}.
 *
 * <p>
 * Can be disabled via RuntimeConfig ({@code usage.enabled=false}).
 *
 * @since 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmUsageTrackerImpl implements UsageTrackingPort {

    private final StoragePort storagePort;
    private final RuntimeConfigService runtimeConfigService;
    private final ObjectMapper objectMapper;

    private static final String USAGE_DIR = "usage";
    private static final String UNKNOWN = "unknown";
    private static final String PROVIDER_TAG = "provider";
    private static final String MODEL_TAG = "model";
    private static final String LOG_PREFIX = "[Usage]";
    private static final String JSONL_EXTENSION = ".jsonl";
    private static final String NEWLINE = "\n";
    private static final String METRIC_REQUESTS_TOTAL = "llm.requests.total";
    private static final String METRIC_TOKENS_INPUT = "llm.tokens.input";
    private static final String METRIC_TOKENS_OUTPUT = "llm.tokens.output";
    private static final String METRIC_TOKENS_TOTAL = "llm.tokens.total";
    private static final String METRIC_LATENCY_AVG = "llm.latency.avg_ms";
    private static final String PATH_SEPARATOR = "/";

    private static final int RETENTION_DAYS = 30;
    private static final int EVICTION_INTERVAL_HOURS = 1;
    private static final int EXECUTOR_TERMINATION_TIMEOUT_SECONDS = 2;
    private static final long DEFAULT_AVERAGE_LATENCY = 0L;
    private static final TypeReference<List<LlmUsage>> USAGE_LIST_TYPE = new TypeReference<>() {
    };

    private static final Duration RETENTION_PERIOD = Duration.ofDays(RETENTION_DAYS);

    private final Map<String, List<LlmUsage>> usageByProvider = new ConcurrentHashMap<>();
    private final Map<String, List<LlmUsage>> usageByModel = new ConcurrentHashMap<>();

    private final ScheduledExecutorService evictionExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "usage-eviction");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void init() {
        loadPersistedUsage();
        // Evict old records every hour
        evictionExecutor.scheduleAtFixedRate(this::evictOldRecords,
                EVICTION_INTERVAL_HOURS, EVICTION_INTERVAL_HOURS, TimeUnit.HOURS);
    }

    @PreDestroy
    void destroy() {
        evictionExecutor.shutdownNow();
        try {
            evictionExecutor.awaitTermination(EXECUTOR_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void loadPersistedUsage() {
        if (!runtimeConfigService.isUsageEnabled()) {
            return;
        }
        Instant cutoff = Instant.now().minus(RETENTION_PERIOD);
        try {
            List<String> files = storagePort.listObjects(USAGE_DIR, "").join();
            if (files == null || files.isEmpty()) {
                log.debug("{} No persisted usage files found", LOG_PREFIX);
                return;
            }

            int loaded = 0;
            int skippedOld = 0;
            for (String file : files) {
                if (!file.endsWith(JSONL_EXTENSION) && !file.endsWith(".json")) {
                    continue;
                }
                try {
                    String content = storagePort.getText(USAGE_DIR, file).join();
                    if (content == null || content.isBlank()) {
                        continue;
                    }

                    List<LlmUsage> parsedUsages = parseUsageFileContent(file, content);
                    for (LlmUsage usage : parsedUsages) {
                        if (usage == null) {
                            continue;
                        }
                        // Only load records within retention window
                        if (usage.getTimestamp() != null && usage.getTimestamp().isBefore(cutoff)) {
                            skippedOld++;
                            continue;
                        }
                        indexUsage(usage);
                        loaded++;
                    }
                } catch (RuntimeException e) {
                    log.warn("{} Failed to read file {}: {}", LOG_PREFIX, file, e.getMessage());
                }
            }
            log.info("{} Loaded {} usage records from storage (skipped {} old records beyond {}d retention)",
                    LOG_PREFIX, loaded, skippedOld, RETENTION_PERIOD.toDays());
        } catch (RuntimeException e) {
            log.warn("{} Failed to load persisted usage", LOG_PREFIX, e);
        }
    }

    private List<LlmUsage> parseUsageFileContent(String file, String content) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }

        if (trimmed.startsWith("[")) {
            try {
                return objectMapper.readValue(trimmed, USAGE_LIST_TYPE);
            } catch (JsonProcessingException e) {
                log.debug("{} Failed to parse JSON array in {}: {}", LOG_PREFIX, file, e.getMessage());
            }
        }

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                ObjectMapper strictMapper = objectMapper.copy()
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                LlmUsage single = strictMapper.readValue(trimmed, LlmUsage.class);
                return List.of(single);
            } catch (JsonProcessingException ignored) { // NOSONAR — not a single JSON object, fall through to JSONL
            }
        }

        List<LlmUsage> usages = new ArrayList<>();
        for (String line : content.split(NEWLINE)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                usages.add(objectMapper.readValue(line, LlmUsage.class));
            } catch (JsonProcessingException e) {
                log.debug("{} Skipping malformed line in {}: {}", LOG_PREFIX, file, e.getMessage());
            }
        }
        return usages;
    }

    private void indexUsage(LlmUsage usage) {
        String provider = usage.getProviderId() != null ? usage.getProviderId() : UNKNOWN;
        String model = usage.getModel() != null ? usage.getModel() : UNKNOWN;

        usageByProvider.computeIfAbsent(provider, k -> new CopyOnWriteArrayList<>()).add(usage);
        usageByModel.computeIfAbsent(model, k -> new CopyOnWriteArrayList<>()).add(usage);
    }

    private void evictOldRecords() {
        Instant cutoff = Instant.now().minus(RETENTION_PERIOD);
        int evicted = 0;
        for (List<LlmUsage> list : usageByProvider.values()) {
            evicted += list.removeIf(u -> u.getTimestamp() != null && u.getTimestamp().isBefore(cutoff)) ? 1 : 0;
        }
        for (List<LlmUsage> list : usageByModel.values()) {
            list.removeIf(u -> u.getTimestamp() != null && u.getTimestamp().isBefore(cutoff));
        }
        if (evicted > 0) {
            log.debug("{} Evicted old records beyond {}d retention", LOG_PREFIX, RETENTION_PERIOD.toDays());
        }
    }

    @Override
    public void recordUsage(String providerId, String model, LlmUsage usage) {
        if (!runtimeConfigService.isUsageEnabled()) {
            return;
        }

        usage.setProviderId(providerId);
        usage.setModel(model);
        if (usage.getTimestamp() == null) {
            usage.setTimestamp(Instant.now());
        }

        indexUsage(usage);
        String safeProviderId = usage.getProviderId() != null ? usage.getProviderId() : UNKNOWN;
        persistUsage(safeProviderId, usage);

        log.debug("Recorded usage: provider={}, model={}, tokens={}, latency={}ms",
                safeProviderId, usage.getModel(), usage.getTotalTokens(),
                usage.getLatency() != null ? usage.getLatency().toMillis() : "N/A");
    }

    @Override
    public UsageStats getStats(String providerId, Duration period) {
        List<LlmUsage> usages = usageByProvider.getOrDefault(providerId, Collections.emptyList());
        return aggregateUsages(providerId, filterByPeriod(usages, period));
    }

    @Override
    public Map<String, UsageStats> getAllStats(Duration period) {
        Map<String, UsageStats> stats = new HashMap<>();
        for (Map.Entry<String, List<LlmUsage>> entry : usageByProvider.entrySet()) {
            stats.put(entry.getKey(), aggregateUsages(entry.getKey(), filterByPeriod(entry.getValue(), period)));
        }
        return stats;
    }

    @Override
    public Map<String, UsageStats> getStatsByModel(Duration period) {
        // Aggregate all usage grouped by provider/model
        List<LlmUsage> allUsages = new ArrayList<>();
        for (List<LlmUsage> providerUsages : usageByProvider.values()) {
            allUsages.addAll(providerUsages);
        }
        List<LlmUsage> filtered = filterByPeriod(allUsages, period);

        Map<String, List<LlmUsage>> grouped = filtered.stream()
                .collect(Collectors.groupingBy(u -> {
                    String provider = u.getProviderId() != null ? u.getProviderId() : UNKNOWN;
                    String model = u.getModel() != null ? u.getModel() : UNKNOWN;
                    return provider + PATH_SEPARATOR + model;
                }));

        Map<String, UsageStats> result = new HashMap<>();
        for (Map.Entry<String, List<LlmUsage>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), aggregateUsages(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    @Override
    public List<UsageMetric> exportMetrics() {
        List<UsageMetric> metrics = new ArrayList<>();

        for (Map.Entry<String, List<LlmUsage>> entry : usageByProvider.entrySet()) {
            String providerId = entry.getKey();
            UsageStats stats = aggregateUsages(providerId, entry.getValue());

            metrics.add(UsageMetric.of(METRIC_REQUESTS_TOTAL, stats.getTotalRequests(),
                    PROVIDER_TAG, providerId));
            metrics.add(UsageMetric.of(METRIC_TOKENS_INPUT, stats.getTotalInputTokens(),
                    PROVIDER_TAG, providerId));
            metrics.add(UsageMetric.of(METRIC_TOKENS_OUTPUT, stats.getTotalOutputTokens(),
                    PROVIDER_TAG, providerId));
            metrics.add(UsageMetric.of(METRIC_TOKENS_TOTAL, stats.getTotalTokens(),
                    PROVIDER_TAG, providerId));
            metrics.add(UsageMetric.of(METRIC_LATENCY_AVG, stats.getAvgLatency().toMillis(),
                    PROVIDER_TAG, providerId));
        }

        for (Map.Entry<String, List<LlmUsage>> entry : usageByModel.entrySet()) {
            String model = entry.getKey();
            long tokens = entry.getValue().stream().mapToLong(LlmUsage::getTotalTokens).sum();
            metrics.add(UsageMetric.of(METRIC_TOKENS_TOTAL, tokens, MODEL_TAG, model));
            metrics.add(UsageMetric.of(METRIC_REQUESTS_TOTAL, entry.getValue().size(), MODEL_TAG, model));
        }

        return metrics;
    }

    private List<LlmUsage> filterByPeriod(List<LlmUsage> usages, Duration period) {
        Instant cutoff = Instant.now().minus(period);
        return usages.stream()
                .filter(u -> u.getTimestamp() != null && u.getTimestamp().isAfter(cutoff))
                .toList();
    }

    private UsageStats aggregateUsages(String key, List<LlmUsage> usages) {
        if (usages.isEmpty()) {
            return UsageStats.empty(key);
        }

        long totalInput = usages.stream().mapToLong(LlmUsage::getInputTokens).sum();
        long totalOutput = usages.stream().mapToLong(LlmUsage::getOutputTokens).sum();

        long avgLatencyMs = (long) usages.stream()
                .filter(u -> u.getLatency() != null)
                .mapToLong(u -> u.getLatency().toMillis())
                .average()
                .orElse(DEFAULT_AVERAGE_LATENCY);

        Map<String, Long> requestsByModel = usages.stream()
                .filter(u -> u.getModel() != null)
                .collect(Collectors.groupingBy(LlmUsage::getModel, Collectors.counting()));

        Map<String, Long> tokensByModel = usages.stream()
                .filter(u -> u.getModel() != null)
                .collect(Collectors.groupingBy(LlmUsage::getModel,
                        Collectors.summingLong(LlmUsage::getTotalTokens)));

        String primaryModel = usages.stream()
                .filter(u -> u.getModel() != null)
                .collect(Collectors.groupingBy(LlmUsage::getModel, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        return UsageStats.builder()
                .providerId(key)
                .model(primaryModel)
                .totalRequests(usages.size())
                .totalInputTokens(totalInput)
                .totalOutputTokens(totalOutput)
                .totalTokens(totalInput + totalOutput)
                .avgLatency(Duration.ofMillis(avgLatencyMs))
                .requestsByModel(requestsByModel)
                .tokensByModel(tokensByModel)
                .build();
    }

    private void persistUsage(String providerId, LlmUsage usage) {
        try {
            String key = String.format("%s/%s.jsonl",
                    providerId,
                    java.time.LocalDate.now().toString());

            String json = objectMapper.writeValueAsString(usage) + NEWLINE;
            storagePort.appendText(USAGE_DIR, key, json);
        } catch (Exception e) {
            log.warn("Failed to persist usage record", e);
        }
    }
}
