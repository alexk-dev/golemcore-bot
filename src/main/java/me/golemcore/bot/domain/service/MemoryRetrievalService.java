package me.golemcore.bot.domain.service;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Retrieves and ranks memory items for prompt injection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryRetrievalService {

    private static final String EPISODIC_PREFIX = "items/episodic/";
    private static final String SEMANTIC_FILE = "items/semantic.jsonl";
    private static final String PROCEDURAL_FILE = "items/procedural.jsonl";
    private static final int DEFAULT_EPISODIC_LOOKBACK_DAYS = 14;
    private static final int MAX_EPISODIC_LOOKBACK_DAYS = 90;

    private final StoragePort storagePort;
    private final BotProperties properties;
    private final RuntimeConfigService runtimeConfigService;
    private final ObjectMapper objectMapper;

    public List<MemoryScoredItem> retrieve(MemoryQuery query) {
        if (!runtimeConfigService.isMemoryEnabled()) {
            return List.of();
        }

        MemoryQuery normalizedQuery = normalizeQuery(query);
        List<MemoryItem> candidates = new ArrayList<>();

        candidates.addAll(loadRecentEpisodic(resolveEpisodicLookbackDays()));
        candidates.addAll(loadJsonl(SEMANTIC_FILE));
        candidates.addAll(loadJsonl(PROCEDURAL_FILE));

        List<MemoryItem> filtered = filterCandidates(candidates);
        List<MemoryScoredItem> scored = new ArrayList<>();
        for (MemoryItem item : filtered) {
            double score = score(normalizedQuery, item);
            scored.add(MemoryScoredItem.builder()
                    .item(item)
                    .score(score)
                    .build());
        }

        scored.sort(Comparator
                .comparingDouble(MemoryScoredItem::getScore)
                .reversed()
                .thenComparing((MemoryScoredItem scoredItem) -> resolveTimestamp(scoredItem.getItem()),
                        Comparator.nullsLast(Comparator.reverseOrder())));

        List<MemoryScoredItem> topByLayer = applyLayerTopK(scored, normalizedQuery);
        return deduplicate(topByLayer);
    }

    private MemoryQuery normalizeQuery(MemoryQuery query) {
        MemoryQuery source = query != null ? query : new MemoryQuery();
        return MemoryQuery.builder()
                .queryText(source.getQueryText())
                .activeSkill(source.getActiveSkill())
                .softPromptBudgetTokens(normalizePositive(source.getSoftPromptBudgetTokens(),
                        runtimeConfigService.getMemorySoftPromptBudgetTokens()))
                .maxPromptBudgetTokens(normalizePositive(source.getMaxPromptBudgetTokens(),
                        runtimeConfigService.getMemoryMaxPromptBudgetTokens()))
                .workingTopK(normalizeTopK(source.getWorkingTopK(), runtimeConfigService.getMemoryWorkingTopK()))
                .episodicTopK(normalizeTopK(source.getEpisodicTopK(), runtimeConfigService.getMemoryEpisodicTopK()))
                .semanticTopK(normalizeTopK(source.getSemanticTopK(), runtimeConfigService.getMemorySemanticTopK()))
                .proceduralTopK(
                        normalizeTopK(source.getProceduralTopK(), runtimeConfigService.getMemoryProceduralTopK()))
                .build();
    }

    private List<MemoryItem> loadRecentEpisodic(int days) {
        List<MemoryItem> items = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            String date = LocalDate.now(ZoneId.systemDefault()).minusDays(i).toString();
            String path = EPISODIC_PREFIX + date + ".jsonl";
            items.addAll(loadJsonl(path));
        }
        return items;
    }

    private int resolveEpisodicLookbackDays() {
        if (runtimeConfigService.isMemoryDecayEnabled()) {
            int decayDays = runtimeConfigService.getMemoryDecayDays();
            return clampDays(decayDays);
        }
        return DEFAULT_EPISODIC_LOOKBACK_DAYS;
    }

    private List<MemoryItem> loadJsonl(String path) {
        List<MemoryItem> items = new ArrayList<>();
        try {
            String content = storagePort.getText(getMemoryDirectory(), path).join();
            if (content == null || content.isBlank()) {
                return items;
            }
            String[] lines = content.split("\\R");
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    MemoryItem item = objectMapper.readValue(line, MemoryItem.class);
                    items.add(item);
                } catch (Exception e) {
                    log.trace("[MemoryRetrieval] Skipping invalid line in {}: {}", path, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.trace("[MemoryRetrieval] Failed to load {}: {}", path, e.getMessage());
        }
        return items;
    }

    private List<MemoryItem> filterCandidates(List<MemoryItem> items) {
        List<MemoryItem> result = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return result;
        }

        Instant decayThreshold = Instant.now().minus(runtimeConfigService.getMemoryDecayDays(), ChronoUnit.DAYS);
        for (MemoryItem item : items) {
            if (item == null) {
                continue;
            }
            if (item.getContent() == null || item.getContent().isBlank()) {
                continue;
            }

            if (item.getTtlDays() != null && item.getCreatedAt() != null) {
                Instant ttlExpiry = item.getCreatedAt().plus(item.getTtlDays(), ChronoUnit.DAYS);
                if (Instant.now().isAfter(ttlExpiry)) {
                    continue;
                }
            }

            if (runtimeConfigService.isMemoryDecayEnabled()) {
                Instant timestamp = resolveTimestamp(item);
                if (timestamp != null && timestamp.isBefore(decayThreshold)) {
                    continue;
                }
            }

            result.add(item);
        }

        return result;
    }

    private List<MemoryScoredItem> applyLayerTopK(List<MemoryScoredItem> scored, MemoryQuery query) {
        Map<MemoryItem.Layer, Integer> limits = new LinkedHashMap<>();
        limits.put(MemoryItem.Layer.WORKING, normalizeTopK(query.getWorkingTopK(), 0));
        limits.put(MemoryItem.Layer.EPISODIC, normalizeTopK(query.getEpisodicTopK(), 0));
        limits.put(MemoryItem.Layer.SEMANTIC, normalizeTopK(query.getSemanticTopK(), 0));
        limits.put(MemoryItem.Layer.PROCEDURAL, normalizeTopK(query.getProceduralTopK(), 0));

        Map<MemoryItem.Layer, Integer> counters = new HashMap<>();
        List<MemoryScoredItem> selected = new ArrayList<>();

        for (MemoryScoredItem candidate : scored) {
            MemoryItem item = candidate.getItem();
            MemoryItem.Layer layer = item.getLayer() != null ? item.getLayer() : MemoryItem.Layer.EPISODIC;
            int limit = limits.getOrDefault(layer, 4);
            int current = counters.getOrDefault(layer, 0);
            if (current >= limit) {
                continue;
            }

            selected.add(candidate);
            counters.put(layer, current + 1);
        }

        return selected;
    }

    private List<MemoryScoredItem> deduplicate(List<MemoryScoredItem> scored) {
        List<MemoryScoredItem> dedup = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (MemoryScoredItem candidate : scored) {
            MemoryItem item = candidate.getItem();
            if (item == null) {
                continue;
            }
            String key = item.getFingerprint();
            if (key == null || key.isBlank()) {
                key = item.getId();
            }
            if (key == null || key.isBlank()) {
                key = "row-" + dedup.size();
            }

            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);
            dedup.add(candidate);
        }
        return dedup;
    }

    private double score(MemoryQuery query, MemoryItem item) {
        double relevance = lexicalRelevance(query.getQueryText(), item);
        double recency = recencyScore(item);
        double salience = clamp(defaultDouble(item.getSalience(), 0.50));
        double confidence = clamp(defaultDouble(item.getConfidence(), 0.55));

        double typeBoost = typeBoost(item.getType());
        double skillBoost = skillBoost(query.getActiveSkill(), item.getTags());

        return (relevance * 0.40)
                + (recency * 0.20)
                + (salience * 0.20)
                + (confidence * 0.20)
                + typeBoost
                + skillBoost;
    }

    private double lexicalRelevance(String queryText, MemoryItem item) {
        if (queryText == null || queryText.isBlank()) {
            return 0.20;
        }
        Set<String> queryTokens = tokenize(queryText);
        if (queryTokens.isEmpty()) {
            return 0.20;
        }

        String searchable = buildSearchableText(item);
        Set<String> contentTokens = tokenize(searchable);
        if (contentTokens.isEmpty()) {
            return 0.0;
        }

        int matches = 0;
        for (String token : queryTokens) {
            if (contentTokens.contains(token)) {
                matches++;
            }
        }
        return clamp((double) matches / (double) queryTokens.size());
    }

    private String buildSearchableText(MemoryItem item) {
        StringBuilder sb = new StringBuilder();
        if (item.getTitle() != null) {
            sb.append(item.getTitle()).append(' ');
        }
        if (item.getContent() != null) {
            sb.append(item.getContent()).append(' ');
        }
        if (item.getTags() != null) {
            for (String tag : item.getTags()) {
                sb.append(tag).append(' ');
            }
        }
        return sb.toString();
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String[] raw = text.toLowerCase(Locale.ROOT).split("[^a-zа-я0-9_./#-]+");
        for (String token : raw) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private double recencyScore(MemoryItem item) {
        Instant timestamp = resolveTimestamp(item);
        if (timestamp == null) {
            return 0.30;
        }
        long days = ChronoUnit.DAYS.between(timestamp, Instant.now());
        if (days <= 0) {
            return 1.0;
        }
        if (days >= 30) {
            return 0.0;
        }
        return clamp(1.0 - (days / 30.0));
    }

    private Instant resolveTimestamp(MemoryItem item) {
        if (item == null) {
            return null;
        }
        if (item.getUpdatedAt() != null) {
            return item.getUpdatedAt();
        }
        return item.getCreatedAt();
    }

    private double typeBoost(MemoryItem.Type type) {
        if (type == null) {
            return 0.0;
        }
        return switch (type) {
        case FAILURE, FIX, CONSTRAINT -> 0.12;
        case DECISION, PROJECT_FACT, PREFERENCE -> 0.08;
        case TASK_STATE, COMMAND_RESULT -> 0.05;
        };
    }

    private double skillBoost(String activeSkill, List<String> tags) {
        if (activeSkill == null || activeSkill.isBlank() || tags == null || tags.isEmpty()) {
            return 0.0;
        }
        String normalized = activeSkill.toLowerCase(Locale.ROOT);
        for (String tag : tags) {
            if (normalized.equals(tag)) {
                return 0.10;
            }
        }
        return 0.0;
    }

    private double clamp(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private double defaultDouble(Double value, double fallback) {
        return value != null ? value : fallback;
    }

    private int normalizePositive(Integer value, int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return value;
    }

    private int normalizeTopK(Integer value, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(0, value);
    }

    private int clampDays(int days) {
        if (days < 1) {
            return 1;
        }
        return Math.min(days, MAX_EPISODIC_LOOKBACK_DAYS);
    }

    private String getMemoryDirectory() {
        String configured = properties.getMemory().getDirectory();
        if (configured == null || configured.isBlank()) {
            return "memory";
        }
        return configured;
    }
}
