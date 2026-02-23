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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds prompt-ready memory context from scored memory items using token
 * budgets.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryPromptPackService {

    public MemoryPack build(MemoryQuery query, List<MemoryScoredItem> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return MemoryPack.builder()
                    .items(List.of())
                    .diagnostics(Map.of("candidateCount", 0, "selectedCount", 0))
                    .renderedContext("")
                    .build();
        }

        int softBudget = normalizeBudget(query != null ? query.getSoftPromptBudgetTokens() : null, 1800);
        int maxBudget = normalizeBudget(query != null ? query.getMaxPromptBudgetTokens() : null, 3500);
        if (maxBudget < softBudget) {
            maxBudget = softBudget;
        }

        List<MemoryItem> selected = new ArrayList<>();
        int usedTokens = 0;
        int droppedByBudget = 0;

        for (MemoryScoredItem candidate : candidates) {
            MemoryItem item = candidate.getItem();
            if (item == null) {
                continue;
            }

            int itemTokens = estimateTokens(item);
            if (usedTokens + itemTokens <= softBudget) {
                selected.add(item);
                usedTokens += itemTokens;
                continue;
            }

            boolean allowBeyondSoft = candidate.getScore() >= 0.85;
            if (allowBeyondSoft && usedTokens + itemTokens <= maxBudget) {
                selected.add(item);
                usedTokens += itemTokens;
                continue;
            }

            droppedByBudget++;
        }

        String rendered = render(selected);
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("candidateCount", candidates.size());
        diagnostics.put("selectedCount", selected.size());
        diagnostics.put("droppedByBudget", droppedByBudget);
        diagnostics.put("estimatedTokens", usedTokens);
        diagnostics.put("softBudgetTokens", softBudget);
        diagnostics.put("maxBudgetTokens", maxBudget);

        return MemoryPack.builder()
                .items(selected)
                .diagnostics(diagnostics)
                .renderedContext(rendered)
                .build();
    }

    private String render(List<MemoryItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }

        Map<MemoryItem.Layer, List<MemoryItem>> grouped = new EnumMap<>(MemoryItem.Layer.class);
        for (MemoryItem item : items) {
            MemoryItem.Layer layer = item.getLayer() != null ? item.getLayer() : MemoryItem.Layer.EPISODIC;
            grouped.computeIfAbsent(layer, k -> new ArrayList<>()).add(item);
        }

        StringBuilder sb = new StringBuilder();
        appendLayer(sb, grouped, MemoryItem.Layer.WORKING, "## Working Memory");
        appendLayer(sb, grouped, MemoryItem.Layer.EPISODIC, "## Episodic Memory");
        appendLayer(sb, grouped, MemoryItem.Layer.SEMANTIC, "## Semantic Memory");
        appendLayer(sb, grouped, MemoryItem.Layer.PROCEDURAL, "## Procedural Memory");
        return sb.toString().trim();
    }

    private void appendLayer(StringBuilder sb, Map<MemoryItem.Layer, List<MemoryItem>> grouped, MemoryItem.Layer layer,
            String heading) {
        List<MemoryItem> layerItems = grouped.get(layer);
        if (layerItems == null || layerItems.isEmpty()) {
            return;
        }

        sb.append(heading).append("\n");
        for (MemoryItem item : layerItems) {
            sb.append("- [")
                    .append(item.getType() != null ? item.getType() : "ITEM")
                    .append("] ");
            if (item.getTitle() != null && !item.getTitle().isBlank()) {
                sb.append(item.getTitle()).append(": ");
            }
            sb.append(truncate(item.getContent(), 420)).append("\n");
        }
        sb.append("\n");
    }

    private int estimateTokens(MemoryItem item) {
        int chars = 0;
        if (item.getTitle() != null) {
            chars += item.getTitle().length();
        }
        if (item.getContent() != null) {
            chars += item.getContent().length();
        }
        if (item.getTags() != null) {
            for (String tag : item.getTags()) {
                chars += tag != null ? tag.length() + 1 : 0;
            }
        }
        return Math.max(20, (chars / 4) + 8);
    }

    private int normalizeBudget(Integer value, int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return value;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen - 3)) + "...";
    }
}
