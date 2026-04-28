package me.golemcore.bot.domain.memory.diagnostics;

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

import me.golemcore.bot.domain.memory.model.MemoryPackSection;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import me.golemcore.bot.domain.runtimeconfig.MemoryRuntimeConfigView;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds diagnostics maps for prompt-facing memory assembly.
 * <p>
 * The assembler centralizes verbosity handling so orchestrators can publish stable diagnostics without embedding
 * formatting policy into retrieval or pack assembly services.
 */
@Service
public class MemoryDiagnosticsAssembler {

    private final MemoryRuntimeConfigView runtimeConfigService;

    public MemoryDiagnosticsAssembler(MemoryRuntimeConfigView runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    /**
     * Build diagnostics for a prompt-facing memory pack.
     *
     * @param query
     *            normalized query used for retrieval
     * @param scoredItems
     *            structured candidates returned by retrieval
     * @param pack
     *            assembled prompt pack
     *
     * @return diagnostics map honoring configured verbosity
     */
    public Map<String, Object> buildPromptDiagnostics(MemoryQuery query, List<MemoryScoredItem> scoredItems,
            MemoryPack pack) {
        String verbosity = normalizeVerbosity(runtimeConfigService.getMemoryDiagnosticsVerbosity());
        if ("off".equals(verbosity)) {
            return Map.of("enabled", true);
        }

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("enabled", true);

        if (pack != null && pack.getDiagnostics() != null && !pack.getDiagnostics().isEmpty()) {
            diagnostics.putAll(pack.getDiagnostics());
        }
        if (pack != null && pack.getDisclosureMode() != null && !pack.getDisclosureMode().isBlank()) {
            diagnostics.putIfAbsent("disclosureMode", pack.getDisclosureMode());
        }
        diagnostics.put("structuredCandidates", scoredItems != null ? scoredItems.size() : 0);

        if ("detailed".equals(verbosity)) {
            appendDetailedPromptDiagnostics(diagnostics, query, pack);
        }

        return diagnostics;
    }

    /**
     * Build the minimal disabled-memory diagnostics payload.
     *
     * @return diagnostics map for disabled memory
     */
    public Map<String, Object> buildDisabledPromptDiagnostics() {
        return Map.of("enabled", false);
    }

    private void appendDetailedPromptDiagnostics(Map<String, Object> diagnostics, MemoryQuery query, MemoryPack pack) {
        if (query != null) {
            diagnostics.put("queryScope", query.getScope());
            diagnostics.put("scopeChain", query.getScopeChain() != null ? query.getScopeChain() : List.of());
            diagnostics.put("softPromptBudgetTokens", query.getSoftPromptBudgetTokens());
            diagnostics.put("maxPromptBudgetTokens", query.getMaxPromptBudgetTokens());
            diagnostics.put("workingTopK", query.getWorkingTopK());
            diagnostics.put("episodicTopK", query.getEpisodicTopK());
            diagnostics.put("semanticTopK", query.getSemanticTopK());
            diagnostics.put("proceduralTopK", query.getProceduralTopK());
        }

        if (pack != null && pack.getSections() != null && !pack.getSections().isEmpty()) {
            diagnostics.put("sectionTypes", pack.getSections().stream().map(MemoryPackSection::getType).toList());
        }
    }

    private String normalizeVerbosity(String verbosity) {
        if (verbosity == null || verbosity.isBlank()) {
            return "basic";
        }
        return verbosity.trim().toLowerCase(Locale.ROOT);
    }
}
