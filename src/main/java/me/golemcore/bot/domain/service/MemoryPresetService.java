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

import me.golemcore.bot.domain.model.MemoryPreset;
import me.golemcore.bot.domain.model.RuntimeConfig;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Catalog of memory presets for different interaction profiles.
 */
@Service
public class MemoryPresetService {

    private final List<MemoryPreset> presets = List.of(
            createPreset(
                    "coding_fast",
                    "Coding Fast",
                    "Fast and lightweight for short coding sessions: fewer episodic recalls, stronger focus on practical patterns.",
                    true,
                    1400,
                    2600,
                    5,
                    5,
                    5,
                    6,
                    true,
                    0.78,
                    true,
                    21,
                    14,
                    true),
            createPreset(
                    "coding_balanced",
                    "Coding Balanced",
                    "Recommended default for most developers.",
                    true,
                    1800,
                    3500,
                    6,
                    6,
                    7,
                    6,
                    true,
                    0.80,
                    true,
                    30,
                    21,
                    true),
            createPreset(
                    "coding_deep",
                    "Coding Deep Autonomous",
                    "For long autonomous coding tracks: deeper recall and higher budget without aggressive context bloat.",
                    true,
                    2400,
                    4200,
                    8,
                    6,
                    9,
                    8,
                    true,
                    0.78,
                    true,
                    60,
                    30,
                    true),
            createPreset(
                    "general_chat",
                    "General Chat",
                    "Minimal memory footprint for everyday conversation to keep replies compact.",
                    true,
                    1000,
                    1800,
                    4,
                    6,
                    5,
                    1,
                    true,
                    0.85,
                    true,
                    14,
                    14,
                    false),
            createPreset(
                    "research_analyst",
                    "Research Analyst",
                    "Semantic-heavy profile where facts and conclusions are prioritized over raw episodes.",
                    true,
                    2000,
                    3600,
                    6,
                    5,
                    10,
                    2,
                    true,
                    0.78,
                    true,
                    90,
                    21,
                    false),
            createPreset(
                    "ops_support",
                    "Ops / Support",
                    "Incident-focused profile: higher procedural recall and code-aware extraction for logs, errors, and fixes.",
                    true,
                    1500,
                    2800,
                    6,
                    5,
                    7,
                    8,
                    true,
                    0.80,
                    true,
                    45,
                    21,
                    true),
            createPreset(
                    "disabled",
                    "Memory Disabled",
                    "Memory is fully disabled for privacy-sensitive tasks and debugging without memory context.",
                    false,
                    1800,
                    3500,
                    6,
                    6,
                    7,
                    6,
                    true,
                    0.80,
                    true,
                    30,
                    21,
                    true));

    public List<MemoryPreset> getPresets() {
        return presets.stream()
                .map(this::copyPreset)
                .toList();
    }

    public Optional<MemoryPreset> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        return presets.stream()
                .filter(preset -> preset.getId().equals(normalized))
                .findFirst()
                .map(this::copyPreset);
    }

    private MemoryPreset createPreset(
            String id,
            String label,
            String comment,
            boolean enabled,
            int softBudget,
            int maxBudget,
            int workingTopK,
            int episodicTopK,
            int semanticTopK,
            int proceduralTopK,
            boolean promotionEnabled,
            double promotionMinConfidence,
            boolean decayEnabled,
            int decayDays,
            int retrievalLookbackDays,
            boolean codeAwareExtractionEnabled) {
        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder()
                .enabled(enabled)
                .softPromptBudgetTokens(softBudget)
                .maxPromptBudgetTokens(maxBudget)
                .workingTopK(workingTopK)
                .episodicTopK(episodicTopK)
                .semanticTopK(semanticTopK)
                .proceduralTopK(proceduralTopK)
                .promotionEnabled(promotionEnabled)
                .promotionMinConfidence(promotionMinConfidence)
                .decayEnabled(decayEnabled)
                .decayDays(decayDays)
                .retrievalLookbackDays(retrievalLookbackDays)
                .codeAwareExtractionEnabled(codeAwareExtractionEnabled)
                .build();

        return MemoryPreset.builder()
                .id(id)
                .label(label)
                .comment(comment)
                .memory(memoryConfig)
                .build();
    }

    private MemoryPreset copyPreset(MemoryPreset preset) {
        RuntimeConfig.MemoryConfig source = preset.getMemory();
        RuntimeConfig.MemoryConfig memoryCopy = RuntimeConfig.MemoryConfig.builder()
                .enabled(source.getEnabled())
                .softPromptBudgetTokens(source.getSoftPromptBudgetTokens())
                .maxPromptBudgetTokens(source.getMaxPromptBudgetTokens())
                .workingTopK(source.getWorkingTopK())
                .episodicTopK(source.getEpisodicTopK())
                .semanticTopK(source.getSemanticTopK())
                .proceduralTopK(source.getProceduralTopK())
                .promotionEnabled(source.getPromotionEnabled())
                .promotionMinConfidence(source.getPromotionMinConfidence())
                .decayEnabled(source.getDecayEnabled())
                .decayDays(source.getDecayDays())
                .retrievalLookbackDays(source.getRetrievalLookbackDays())
                .codeAwareExtractionEnabled(source.getCodeAwareExtractionEnabled())
                .build();

        return MemoryPreset.builder()
                .id(preset.getId())
                .label(preset.getLabel())
                .comment(preset.getComment())
                .memory(memoryCopy)
                .build();
    }
}
