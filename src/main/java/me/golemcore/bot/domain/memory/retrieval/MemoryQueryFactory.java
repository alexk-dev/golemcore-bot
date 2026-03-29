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

import me.golemcore.bot.domain.memory.model.MemoryContextRequest;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.service.MemoryScopeSupport;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Creates high-level memory queries for prompt and tool entry points.
 *
 * <p>
 * The factory applies runtime defaults early so the calling orchestrators can
 * read as a short sequence of steps without repeating top-k and budget fallback
 * logic.
 */
@Service
public class MemoryQueryFactory {

    private final RuntimeConfigService runtimeConfigService;

    public MemoryQueryFactory(RuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    /**
     * Create a prompt-facing memory query from a high-level context request.
     *
     * @param request
     *            prompt assembly request
     * @return normalized prompt query
     */
    public MemoryQuery createForPrompt(MemoryContextRequest request) {
        MemoryQuery source = request != null ? request.getQuery() : null;
        return normalize(source);
    }

    /**
     * Create a tool-facing query using the same defaults as prompt retrieval.
     *
     * @param query
     *            incoming tool query
     * @return normalized tool query
     */
    public MemoryQuery createForTool(MemoryQuery query) {
        return normalize(query);
    }

    /**
     * Normalize an incoming query by applying runtime defaults and scope cleanup.
     *
     * @param query
     *            incoming query, possibly {@code null}
     * @return normalized query
     */
    public MemoryQuery normalize(MemoryQuery query) {
        MemoryQuery source = query != null ? query : new MemoryQuery();
        return MemoryQuery.builder()
                .queryText(source.getQueryText())
                .activeSkill(source.getActiveSkill())
                .scope(MemoryScopeSupport.normalizeScopeOrGlobal(source.getScope()))
                .scopeChain(normalizeScopeChain(source.getScopeChain()))
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

    private List<String> normalizeScopeChain(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String scope : source) {
            if (scope == null || scope.isBlank()) {
                continue;
            }
            normalized.add(MemoryScopeSupport.normalizeScopeOrGlobal(scope));
        }
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
}
