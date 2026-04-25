package me.golemcore.bot.domain.memory.retrieval;

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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.memory.model.MemoryRetrievalPlan;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import me.golemcore.bot.domain.service.MemoryScopeSupport;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies layer caps, scope ordering, and deduplication to scored candidates.
 */
@Service
@Slf4j
public class MemoryCandidateSelector {

    /**
     * Select the prompt-eligible candidates from the scored list.
     *
     * @param plan
     *            normalized retrieval plan
     * @param scored
     *            scored candidates in descending relevance order
     * @return selected candidates after scope-aware top-k and deduplication
     */
    public List<MemoryScoredItem> select(MemoryRetrievalPlan plan, List<MemoryScoredItem> scored) {
        List<MemoryScoredItem> topByLayer = applyLayerTopK(scored, plan);
        List<MemoryScoredItem> deduplicated = deduplicate(topByLayer);
        logScopeMetrics(plan.getRequestedScope(), scored.size(), deduplicated);
        return deduplicated;
    }

    private List<MemoryScoredItem> applyLayerTopK(List<MemoryScoredItem> scored, MemoryRetrievalPlan plan) {
        Map<MemoryItem.Layer, Integer> limits = new LinkedHashMap<>();
        limits.put(MemoryItem.Layer.WORKING, normalizeTopK(plan.getQuery().getWorkingTopK(), 0));
        limits.put(MemoryItem.Layer.EPISODIC, normalizeTopK(plan.getQuery().getEpisodicTopK(), 0));
        limits.put(MemoryItem.Layer.SEMANTIC, normalizeTopK(plan.getQuery().getSemanticTopK(), 0));
        limits.put(MemoryItem.Layer.PROCEDURAL, normalizeTopK(plan.getQuery().getProceduralTopK(), 0));

        Map<MemoryItem.Layer, Integer> counters = new HashMap<>();
        List<MemoryScoredItem> selected = new ArrayList<>();

        if (!plan.getRequestedScopes().isEmpty()) {
            for (String scope : plan.getRequestedScopes()) {
                String normalizedScope = MemoryScopeSupport.normalizeScopeOrGlobal(scope);
                for (MemoryScoredItem candidate : scored) {
                    MemoryItem item = candidate.getItem();
                    String itemScope = normalizeItemScope(item);
                    if (!normalizedScope.equals(itemScope)) {
                        continue;
                    }
                    trySelectWithLayerLimit(candidate, limits, counters, selected);
                }
            }
            return selected;
        }

        for (MemoryScoredItem candidate : scored) {
            trySelectWithLayerLimit(candidate, limits, counters, selected);
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

    private void logScopeMetrics(String requestedScope, int filteredCount, List<MemoryScoredItem> selectedItems) {
        int sessionSelected = 0;
        int globalSelected = 0;
        for (MemoryScoredItem selectedItem : selectedItems) {
            MemoryItem item = selectedItem.getItem();
            if (item == null) {
                continue;
            }
            String scope = normalizeItemScope(item);
            if (MemoryScopeSupport.isSessionScope(scope)) {
                sessionSelected++;
            } else if (MemoryScopeSupport.GLOBAL_SCOPE.equals(scope)) {
                globalSelected++;
            }
        }
        int filteredOut = Math.max(0, filteredCount - selectedItems.size());
        log.info("[SessionMetrics] metric=memory.scope.session.selected.count value={} requestedScope={}",
                sessionSelected, requestedScope);
        log.info("[SessionMetrics] metric=memory.scope.global.selected.count value={} requestedScope={}",
                globalSelected, requestedScope);
        log.info("[SessionMetrics] metric=memory.scope.filtered.count value={} requestedScope={}",
                filteredOut, requestedScope);
    }

    private void trySelectWithLayerLimit(
            MemoryScoredItem candidate,
            Map<MemoryItem.Layer, Integer> limits,
            Map<MemoryItem.Layer, Integer> counters,
            List<MemoryScoredItem> selected) {
        if (candidate == null || candidate.getItem() == null) {
            return;
        }

        MemoryItem item = candidate.getItem();
        MemoryItem.Layer layer = item.getLayer() != null ? item.getLayer() : MemoryItem.Layer.EPISODIC;
        int limit = limits.getOrDefault(layer, 4);
        int current = counters.getOrDefault(layer, 0);
        if (current >= limit) {
            return;
        }

        selected.add(candidate);
        counters.put(layer, current + 1);
    }

    private String normalizeItemScope(MemoryItem item) {
        if (item == null) {
            return MemoryScopeSupport.GLOBAL_SCOPE;
        }
        String normalized = MemoryScopeSupport.normalizeScopeOrGlobal(item.getScope());
        item.setScope(normalized);
        return normalized;
    }

    private int normalizeTopK(Integer value, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(0, value);
    }
}
