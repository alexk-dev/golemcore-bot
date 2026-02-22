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

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import me.golemcore.bot.domain.model.TurnMemoryEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for managing structured Memory V2 across conversations.
 */
@Service
@RequiredArgsConstructor
public class MemoryService implements MemoryComponent {

    private final RuntimeConfigService runtimeConfigService;
    private final MemoryWriteService memoryWriteService;
    private final MemoryRetrievalService memoryRetrievalService;
    private final MemoryPromptPackService memoryPromptPackService;

    @Override
    public String getComponentType() {
        return "memory";
    }

    @Override
    public MemoryPack buildMemoryPack(MemoryQuery query) {
        if (!runtimeConfigService.isMemoryEnabled()) {
            return MemoryPack.builder()
                    .items(List.of())
                    .diagnostics(Map.of("enabled", false))
                    .renderedContext("")
                    .build();
        }

        MemoryQuery normalized = query != null ? query
                : MemoryQuery.builder()
                        .softPromptBudgetTokens(runtimeConfigService.getMemorySoftPromptBudgetTokens())
                        .maxPromptBudgetTokens(runtimeConfigService.getMemoryMaxPromptBudgetTokens())
                        .workingTopK(runtimeConfigService.getMemoryWorkingTopK())
                        .episodicTopK(runtimeConfigService.getMemoryEpisodicTopK())
                        .semanticTopK(runtimeConfigService.getMemorySemanticTopK())
                        .proceduralTopK(runtimeConfigService.getMemoryProceduralTopK())
                        .build();

        List<MemoryScoredItem> scoredItems = memoryRetrievalService.retrieve(normalized);
        MemoryPack structuredPack = memoryPromptPackService.build(normalized, scoredItems);

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        if (structuredPack.getDiagnostics() != null) {
            diagnostics.putAll(structuredPack.getDiagnostics());
        }
        diagnostics.put("structuredCandidates", scoredItems.size());

        return MemoryPack.builder()
                .items(structuredPack.getItems())
                .diagnostics(diagnostics)
                .renderedContext(structuredPack.getRenderedContext() != null ? structuredPack.getRenderedContext() : "")
                .build();
    }

    @Override
    public void persistTurnMemory(TurnMemoryEvent event) {
        memoryWriteService.persistTurnMemory(event);
    }

    @Override
    public List<MemoryItem> queryItems(MemoryQuery query) {
        List<MemoryScoredItem> scoredItems = memoryRetrievalService.retrieve(query);
        List<MemoryItem> items = new ArrayList<>();
        for (MemoryScoredItem scoredItem : scoredItems) {
            if (scoredItem.getItem() != null) {
                items.add(scoredItem.getItem());
            }
        }
        return items;
    }

    @Override
    public void upsertSemanticItem(MemoryItem item) {
        memoryWriteService.upsertSemanticItem(item);
    }

    @Override
    public void upsertProceduralItem(MemoryItem item) {
        memoryWriteService.upsertProceduralItem(item);
    }
}
