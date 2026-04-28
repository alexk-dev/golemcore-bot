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

import me.golemcore.bot.domain.memory.model.MemoryRetrievalPlan;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.memory.MemoryScopeSupport;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Produces normalized retrieval plans from incoming memory queries.
 */
@Service
public class MemoryRetrievalPlanner {

    private static final int MAX_EPISODIC_LOOKBACK_DAYS = 90;

    private final RuntimeConfigService runtimeConfigService;

    public MemoryRetrievalPlanner(RuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    /**
     * Build a retrieval plan with normalized scope, budgets, and lookback.
     *
     * @param query
     *            raw incoming query, or {@code null} to use runtime defaults
     *
     * @return normalized retrieval plan
     */
    public MemoryRetrievalPlan plan(MemoryQuery query) {
        MemoryQuery normalizedQuery = normalizeQuery(query);
        List<String> requestedScopes = resolveRequestedScopes(normalizedQuery);
        String requestedScope = requestedScopes.isEmpty() ? MemoryScopeSupport.GLOBAL_SCOPE : requestedScopes.get(0);
        return MemoryRetrievalPlan.builder().query(normalizedQuery).requestedScopes(requestedScopes)
                .requestedScope(requestedScope)
                .episodicLookbackDays(clampDays(runtimeConfigService.getMemoryRetrievalLookbackDays())).build();
    }

    private MemoryQuery normalizeQuery(MemoryQuery query) {
        MemoryQuery source = query != null ? query : new MemoryQuery();
        String normalizedScope = MemoryScopeSupport.normalizeScopeOrGlobal(source.getScope());
        return MemoryQuery.builder().queryText(source.getQueryText()).activeSkill(source.getActiveSkill())
                .scope(normalizedScope).scopeChain(normalizeScopeChain(source.getScopeChain(), normalizedScope))
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

    private List<String> resolveRequestedScopes(MemoryQuery query) {
        List<String> scopes = normalizeScopeChain(query.getScopeChain(), query.getScope());
        if (scopes.isEmpty()) {
            return List.of(MemoryScopeSupport.GLOBAL_SCOPE);
        }
        return scopes;
    }

    private List<String> normalizeScopeChain(List<String> rawScopes, String fallbackScope) {
        Set<String> normalized = new LinkedHashSet<>();
        if (rawScopes != null) {
            for (String scope : rawScopes) {
                String normalizedScope = MemoryScopeSupport.normalizeScopeOrGlobal(scope);
                if (!MemoryScopeSupport.GLOBAL_SCOPE.equals(normalizedScope)) {
                    normalized.add(normalizedScope);
                }
            }
        }

        String normalizedFallback = MemoryScopeSupport.normalizeScopeOrGlobal(fallbackScope);
        if (!MemoryScopeSupport.GLOBAL_SCOPE.equals(normalizedFallback)) {
            normalized.add(normalizedFallback);
        }
        normalized.add(MemoryScopeSupport.GLOBAL_SCOPE);

        return new ArrayList<>(normalized);
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
}
