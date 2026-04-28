package me.golemcore.bot.domain.memory;

import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;

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

import me.golemcore.bot.domain.memory.disclosure.MemoryDisclosurePlanner;
import me.golemcore.bot.domain.memory.disclosure.MemoryPackRenderer;
import me.golemcore.bot.domain.memory.disclosure.MemorySectionAssembler;
import me.golemcore.bot.domain.memory.model.MemoryDisclosureInput;
import me.golemcore.bot.domain.memory.model.MemoryDisclosureMode;
import me.golemcore.bot.domain.memory.model.MemoryDisclosurePlan;
import me.golemcore.bot.domain.memory.model.MemoryPackSection;
import me.golemcore.bot.domain.memory.model.MemoryPromptStyle;
import me.golemcore.bot.domain.memory.model.MemorySelectionResult;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds prompt-ready memory packs using budget selection followed by progressive disclosure.
 */
@Service
public class MemoryPromptPackService {

    private final RuntimeConfigService runtimeConfigService;
    private final MemoryDisclosurePlanner memoryDisclosurePlanner;
    private final MemorySectionAssembler memorySectionAssembler;
    private final MemoryPackRenderer memoryPackRenderer;

    public MemoryPromptPackService(RuntimeConfigService runtimeConfigService,
            MemoryDisclosurePlanner memoryDisclosurePlanner, MemorySectionAssembler memorySectionAssembler,
            MemoryPackRenderer memoryPackRenderer) {
        this.runtimeConfigService = runtimeConfigService;
        this.memoryDisclosurePlanner = memoryDisclosurePlanner;
        this.memorySectionAssembler = memorySectionAssembler;
        this.memoryPackRenderer = memoryPackRenderer;
    }

    public MemoryPack build(MemoryQuery query, List<MemoryScoredItem> candidates) {
        MemoryDisclosureMode disclosureMode = MemoryDisclosureMode
                .fromConfig(runtimeConfigService.getMemoryDisclosureMode());
        MemoryPromptStyle promptStyle = MemoryPromptStyle.fromConfig(runtimeConfigService.getMemoryPromptStyle());

        if (candidates == null || candidates.isEmpty()) {
            return MemoryPack.builder().items(List.of()).sections(List.of())
                    .disclosureMode(disclosureMode.getConfigValue()).diagnostics(Map.of("candidateCount", 0,
                            "selectedCount", 0, "disclosureMode", disclosureMode.getConfigValue()))
                    .renderedContext("").build();
        }

        MemorySelectionResult selectionResult = selectCandidates(query, candidates);
        MemoryDisclosureInput disclosureInput = MemoryDisclosureInput.builder().selectionResult(selectionResult)
                .disclosureMode(disclosureMode).promptStyle(promptStyle)
                .toolExpansionEnabled(runtimeConfigService.isMemoryToolExpansionEnabled())
                .disclosureHintsEnabled(runtimeConfigService.isMemoryDisclosureHintsEnabled())
                .detailMinScore(runtimeConfigService.getMemoryDetailMinScore()).build();

        MemoryDisclosurePlan disclosurePlan = memoryDisclosurePlanner.plan(disclosureInput);
        List<MemoryPackSection> sections = memorySectionAssembler.assemble(disclosurePlan);
        String rendered = memoryPackRenderer.render(sections, disclosurePlan.getPromptStyle());

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("candidateCount", selectionResult.getCandidateCount());
        diagnostics.put("selectedCount", selectionResult.getSelectedCount());
        diagnostics.put("droppedByBudget", selectionResult.getDroppedByBudget());
        diagnostics.put("estimatedTokens", selectionResult.getEstimatedTokens());
        diagnostics.put("softBudgetTokens", selectionResult.getSoftBudgetTokens());
        diagnostics.put("maxBudgetTokens", selectionResult.getMaxBudgetTokens());
        diagnostics.put("disclosureMode", disclosureMode.getConfigValue());
        diagnostics.put("promptStyle", promptStyle.getConfigValue());
        diagnostics.put("sectionCount", sections.size());

        List<MemoryItem> selectedItems = selectionResult.getSelectedCandidates().stream().map(MemoryScoredItem::getItem)
                .filter(java.util.Objects::nonNull).toList();

        return MemoryPack.builder().items(selectedItems).sections(sections)
                .disclosureMode(disclosureMode.getConfigValue()).diagnostics(diagnostics).renderedContext(rendered)
                .build();
    }

    private MemorySelectionResult selectCandidates(MemoryQuery query, List<MemoryScoredItem> candidates) {
        int softBudget = normalizeBudget(query != null ? query.getSoftPromptBudgetTokens() : null, 1800);
        int maxBudget = normalizeBudget(query != null ? query.getMaxPromptBudgetTokens() : null, 3500);
        if (maxBudget < softBudget) {
            maxBudget = softBudget;
        }

        List<MemoryScoredItem> selected = new ArrayList<>();
        int usedTokens = 0;
        int droppedByBudget = 0;

        for (MemoryScoredItem candidate : candidates) {
            MemoryItem item = candidate.getItem();
            if (item == null) {
                continue;
            }

            int itemTokens = estimateTokens(item);
            if (usedTokens + itemTokens <= softBudget) {
                selected.add(candidate);
                usedTokens += itemTokens;
                continue;
            }

            boolean allowBeyondSoft = candidate.getScore() >= 0.85;
            if (allowBeyondSoft && usedTokens + itemTokens <= maxBudget) {
                selected.add(candidate);
                usedTokens += itemTokens;
                continue;
            }

            droppedByBudget++;
        }

        return MemorySelectionResult.builder().selectedCandidates(selected).candidateCount(candidates.size())
                .selectedCount(selected.size()).droppedByBudget(droppedByBudget).estimatedTokens(usedTokens)
                .softBudgetTokens(softBudget).maxBudgetTokens(maxBudget).build();
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
}
