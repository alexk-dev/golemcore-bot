package me.golemcore.bot.domain.memory;

import me.golemcore.bot.domain.runtimeconfig.MemoryRuntimeConfigView;

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
import me.golemcore.bot.domain.memory.retrieval.MemoryCandidateCollector;
import me.golemcore.bot.domain.memory.retrieval.MemoryCandidateReranker;
import me.golemcore.bot.domain.memory.retrieval.MemoryCandidateScorer;
import me.golemcore.bot.domain.memory.retrieval.MemoryCandidateSelector;
import me.golemcore.bot.domain.memory.retrieval.MemoryRetrievalPlanner;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin compatibility facade for the structured retrieval pipeline.
 */
@Service
public class MemoryRetrievalService {

    private final MemoryRuntimeConfigView runtimeConfigService;
    private final MemoryRetrievalPlanner memoryRetrievalPlanner;
    private final MemoryCandidateCollector memoryCandidateCollector;
    private final MemoryCandidateScorer memoryCandidateScorer;
    private final MemoryCandidateReranker memoryCandidateReranker;
    private final MemoryCandidateSelector memoryCandidateSelector;

    public MemoryRetrievalService(MemoryRuntimeConfigView runtimeConfigService,
            MemoryRetrievalPlanner memoryRetrievalPlanner, MemoryCandidateCollector memoryCandidateCollector,
            MemoryCandidateScorer memoryCandidateScorer, MemoryCandidateReranker memoryCandidateReranker,
            MemoryCandidateSelector memoryCandidateSelector) {
        this.runtimeConfigService = runtimeConfigService;
        this.memoryRetrievalPlanner = memoryRetrievalPlanner;
        this.memoryCandidateCollector = memoryCandidateCollector;
        this.memoryCandidateScorer = memoryCandidateScorer;
        this.memoryCandidateReranker = memoryCandidateReranker;
        this.memoryCandidateSelector = memoryCandidateSelector;
    }

    /**
     * Retrieve, score, and select memory candidates for prompt assembly.
     *
     * @param query
     *            raw query
     *
     * @return scored and selected candidates
     */
    public List<MemoryScoredItem> retrieve(MemoryQuery query) {
        if (!runtimeConfigService.isMemoryEnabled()) {
            return List.of();
        }

        MemoryRetrievalPlan plan = memoryRetrievalPlanner.plan(query);
        List<me.golemcore.bot.domain.model.MemoryItem> candidates = memoryCandidateCollector.collect(plan);
        List<MemoryScoredItem> scored = memoryCandidateScorer.score(plan, candidates);
        List<MemoryScoredItem> reranked = memoryCandidateReranker.rerank(plan, scored);
        return memoryCandidateSelector.select(plan, reranked);
    }
}
