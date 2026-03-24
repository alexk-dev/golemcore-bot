package me.golemcore.bot.domain.memory.orchestrator;

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
import me.golemcore.bot.domain.memory.diagnostics.MemoryDiagnosticsAssembler;
import me.golemcore.bot.domain.memory.model.MemoryAssemblyResult;
import me.golemcore.bot.domain.memory.model.MemoryContextRequest;
import me.golemcore.bot.domain.memory.retrieval.MemoryQueryFactory;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import me.golemcore.bot.domain.service.MemoryPromptPackService;
import me.golemcore.bot.domain.service.MemoryRetrievalService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Coordinates prompt-facing memory retrieval and query-time assembly.
 *
 * <p>
 * The class intentionally reads as a short orchestration flow: normalize query,
 * retrieve scored candidates, assemble a prompt pack, and publish prompt
 * diagnostics.
 */
@Service
@RequiredArgsConstructor
public class MemoryContextOrchestrator {

    private final RuntimeConfigService runtimeConfigService;
    private final MemoryQueryFactory memoryQueryFactory;
    private final MemoryRetrievalService memoryRetrievalService;
    private final MemoryPromptPackService memoryPromptPackService;
    private final MemoryDiagnosticsAssembler memoryDiagnosticsAssembler;

    /**
     * Build the prompt-facing memory pack for a turn.
     *
     * @param query
     *            incoming memory query, or {@code null} to use runtime defaults
     * @return assembled prompt pack with diagnostics
     */
    public MemoryPack buildMemoryPack(MemoryQuery query) {
        return assemblePromptMemory(MemoryContextRequest.builder().query(query).build()).getMemoryPack();
    }

    /**
     * Assemble prompt-facing memory from a high-level request object.
     *
     * @param request
     *            prompt assembly request
     * @return structured assembly result with diagnostics
     */
    public MemoryAssemblyResult assemblePromptMemory(MemoryContextRequest request) {
        if (!runtimeConfigService.isMemoryEnabled()) {
            MemoryPack disabledPack = MemoryPack.builder()
                    .items(List.of())
                    .sections(List.of())
                    .diagnostics(disabledPromptDiagnostics())
                    .renderedContext("")
                    .build();
            return MemoryAssemblyResult.builder()
                    .query(request != null ? request.getQuery() : null)
                    .memoryPack(disabledPack)
                    .diagnostics(disabledPack.getDiagnostics())
                    .build();
        }

        MemoryQuery normalized = memoryQueryFactory.createForPrompt(request);
        List<MemoryScoredItem> scoredItems = memoryRetrievalService.retrieve(normalized);
        MemoryPack structuredPack = safePromptPack(memoryPromptPackService.build(normalized, scoredItems));
        MemoryPack finalPack = MemoryPack.builder()
                .items(structuredPack.getItems())
                .sections(structuredPack.getSections())
                .disclosureMode(structuredPack.getDisclosureMode())
                .diagnostics(promptDiagnostics(normalized, scoredItems, structuredPack))
                .renderedContext(structuredPack.getRenderedContext() != null ? structuredPack.getRenderedContext() : "")
                .build();
        return MemoryAssemblyResult.builder()
                .query(normalized)
                .scoredItems(scoredItems)
                .memoryPack(finalPack)
                .diagnostics(finalPack.getDiagnostics())
                .build();
    }

    /**
     * Query stored memory items without rendering them into prompt text.
     *
     * @param query
     *            user or tool initiated query
     * @return retrieved items with null payloads removed
     */
    public List<MemoryItem> queryItems(MemoryQuery query) {
        MemoryQuery normalized = memoryQueryFactory.createForTool(query);
        List<MemoryScoredItem> scoredItems = memoryRetrievalService.retrieve(normalized);
        List<MemoryItem> items = new ArrayList<>();
        for (MemoryScoredItem scoredItem : scoredItems) {
            if (scoredItem.getItem() != null) {
                items.add(scoredItem.getItem());
            }
        }
        return items;
    }

    private MemoryPack safePromptPack(MemoryPack pack) {
        if (pack != null) {
            return pack;
        }
        return MemoryPack.builder()
                .items(List.of())
                .sections(List.of())
                .diagnostics(Map.of())
                .renderedContext("")
                .build();
    }

    private Map<String, Object> disabledPromptDiagnostics() {
        Map<String, Object> diagnostics = memoryDiagnosticsAssembler.buildDisabledPromptDiagnostics();
        if (diagnostics == null || diagnostics.isEmpty()) {
            return Map.of("enabled", false);
        }
        return diagnostics;
    }

    private Map<String, Object> promptDiagnostics(
            MemoryQuery query,
            List<MemoryScoredItem> scoredItems,
            MemoryPack structuredPack) {
        Map<String, Object> diagnostics = memoryDiagnosticsAssembler.buildPromptDiagnostics(query, scoredItems,
                structuredPack);
        if (diagnostics == null || diagnostics.isEmpty()) {
            return Map.of("enabled", true);
        }
        return diagnostics;
    }
}
