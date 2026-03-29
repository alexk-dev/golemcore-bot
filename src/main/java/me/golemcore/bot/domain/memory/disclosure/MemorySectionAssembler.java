package me.golemcore.bot.domain.memory.disclosure;

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

import me.golemcore.bot.domain.memory.model.MemoryDisclosureMode;
import me.golemcore.bot.domain.memory.model.MemoryDisclosurePlan;
import me.golemcore.bot.domain.memory.model.MemoryPackSection;
import me.golemcore.bot.domain.memory.model.MemoryPackSectionType;
import me.golemcore.bot.domain.memory.model.MemoryPromptStyle;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles structured memory sections from a disclosure plan.
 */
@Service
public class MemorySectionAssembler {

    /**
     * Assemble ordered prompt sections for the supplied disclosure plan.
     *
     * @param plan
     *            disclosure plan
     * @return ordered prompt sections
     */
    public List<MemoryPackSection> assemble(MemoryDisclosurePlan plan) {
        List<MemoryPackSection> sections = new ArrayList<>();
        Map<MemoryItem.Layer, List<MemoryScoredItem>> grouped = groupByLayer(plan);

        for (MemoryPackSectionType sectionType : plan.getSectionTypes()) {
            MemoryPackSection section = buildSection(plan, grouped, sectionType);
            if (section != null && !section.getLines().isEmpty()) {
                sections.add(section);
            }
        }
        return sections;
    }

    private MemoryPackSection buildSection(
            MemoryDisclosurePlan plan,
            Map<MemoryItem.Layer, List<MemoryScoredItem>> grouped,
            MemoryPackSectionType sectionType) {
        return switch (sectionType) {
        case INDEX -> buildIndexSection(grouped);
        case WORKING_MEMORY -> buildLayerSection(plan, grouped.get(MemoryItem.Layer.WORKING), MemoryItem.Layer.WORKING);
        case EPISODIC_MEMORY ->
            buildLayerSection(plan, grouped.get(MemoryItem.Layer.EPISODIC), MemoryItem.Layer.EPISODIC);
        case SEMANTIC_MEMORY ->
            buildLayerSection(plan, grouped.get(MemoryItem.Layer.SEMANTIC), MemoryItem.Layer.SEMANTIC);
        case PROCEDURAL_MEMORY -> buildLayerSection(plan, grouped.get(MemoryItem.Layer.PROCEDURAL),
                MemoryItem.Layer.PROCEDURAL);
        case DETAIL_SNIPPETS -> buildDetailSection(plan);
        case FOLLOWUP_HINTS -> buildHintsSection(plan);
        };
    }

    private Map<MemoryItem.Layer, List<MemoryScoredItem>> groupByLayer(MemoryDisclosurePlan plan) {
        Map<MemoryItem.Layer, List<MemoryScoredItem>> grouped = new EnumMap<>(MemoryItem.Layer.class);
        for (MemoryScoredItem selected : plan.getSelectionResult().getSelectedCandidates()) {
            if (selected == null || selected.getItem() == null) {
                continue;
            }
            MemoryItem.Layer layer = selected.getItem().getLayer() != null
                    ? selected.getItem().getLayer()
                    : MemoryItem.Layer.EPISODIC;
            grouped.computeIfAbsent(layer, key -> new ArrayList<>()).add(selected);
        }
        return grouped;
    }

    private MemoryPackSection buildIndexSection(Map<MemoryItem.Layer, List<MemoryScoredItem>> grouped) {
        List<String> lines = new ArrayList<>();
        appendIndexLine("Working memory available", lines, grouped.get(MemoryItem.Layer.WORKING));
        appendIndexLine("Recent episodes available", lines, grouped.get(MemoryItem.Layer.EPISODIC));
        appendIndexLine("Relevant facts available", lines, grouped.get(MemoryItem.Layer.SEMANTIC));
        appendIndexLine("Applicable procedures available", lines, grouped.get(MemoryItem.Layer.PROCEDURAL));
        return MemoryPackSection.builder()
                .type(MemoryPackSectionType.INDEX)
                .title("Memory Index")
                .lines(lines)
                .build();
    }

    private void appendIndexLine(String key, List<String> lines, List<MemoryScoredItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        lines.add(key + ": " + items.size());
    }

    private MemoryPackSection buildLayerSection(
            MemoryDisclosurePlan plan,
            List<MemoryScoredItem> candidates,
            MemoryItem.Layer layer) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        int maxItems = sectionItemLimit(plan.getPromptStyle(), plan.getDisclosureMode());
        int maxLen = sectionLineLength(plan.getPromptStyle(), plan.getDisclosureMode());
        List<String> lines = new ArrayList<>();
        int count = 0;
        for (MemoryScoredItem candidate : candidates) {
            if (count >= maxItems && plan.getDisclosureMode() != MemoryDisclosureMode.FULL_PACK) {
                break;
            }
            MemoryItem item = candidate.getItem();
            if (item == null) {
                continue;
            }
            lines.add(formatLine(item, maxLen));
            count++;
        }

        return MemoryPackSection.builder()
                .type(sectionTypeFor(layer))
                .title(sectionTitleFor(layer, plan.getDisclosureMode()))
                .lines(lines)
                .build();
    }

    private MemoryPackSection buildDetailSection(MemoryDisclosurePlan plan) {
        List<String> lines = new ArrayList<>();
        for (MemoryScoredItem candidate : plan.getSelectionResult().getSelectedCandidates()) {
            if (candidate == null || candidate.getItem() == null || candidate.getScore() < plan.getDetailMinScore()) {
                continue;
            }
            MemoryItem item = candidate.getItem();
            String detail = "[" + itemType(item) + "] " + itemTitle(item) + truncate(normalize(item.getContent()), 280);
            lines.add(detail);
        }
        return MemoryPackSection.builder()
                .type(MemoryPackSectionType.DETAIL_SNIPPETS)
                .title("Detail Snippets")
                .lines(lines)
                .build();
    }

    private MemoryPackSection buildHintsSection(MemoryDisclosurePlan plan) {
        if (!plan.isToolExpansionEnabled()) {
            return null;
        }
        List<String> lines = List.of(
                "Use memory_expand_section for deeper layer context and memory_read for full item details.");
        return MemoryPackSection.builder()
                .type(MemoryPackSectionType.FOLLOWUP_HINTS)
                .title("Follow-up Hints")
                .lines(lines)
                .build();
    }

    private MemoryPackSectionType sectionTypeFor(MemoryItem.Layer layer) {
        return switch (layer) {
        case WORKING -> MemoryPackSectionType.WORKING_MEMORY;
        case EPISODIC -> MemoryPackSectionType.EPISODIC_MEMORY;
        case SEMANTIC -> MemoryPackSectionType.SEMANTIC_MEMORY;
        case PROCEDURAL -> MemoryPackSectionType.PROCEDURAL_MEMORY;
        };
    }

    private String sectionTitleFor(MemoryItem.Layer layer, MemoryDisclosureMode disclosureMode) {
        if (disclosureMode == MemoryDisclosureMode.FULL_PACK) {
            return switch (layer) {
            case WORKING -> "Working Memory";
            case EPISODIC -> "Episodic Memory";
            case SEMANTIC -> "Semantic Memory";
            case PROCEDURAL -> "Procedural Memory";
            };
        }
        return switch (layer) {
        case WORKING -> "Working Snapshot";
        case EPISODIC -> "Recent Episodes";
        case SEMANTIC -> "Relevant Facts";
        case PROCEDURAL -> "Applicable Procedures";
        };
    }

    private int sectionItemLimit(MemoryPromptStyle promptStyle, MemoryDisclosureMode disclosureMode) {
        if (disclosureMode == MemoryDisclosureMode.FULL_PACK) {
            return Integer.MAX_VALUE;
        }
        return switch (promptStyle) {
        case COMPACT -> 2;
        case BALANCED -> 3;
        case RICH -> 5;
        };
    }

    private int sectionLineLength(MemoryPromptStyle promptStyle, MemoryDisclosureMode disclosureMode) {
        if (disclosureMode == MemoryDisclosureMode.FULL_PACK) {
            return 420;
        }
        return switch (promptStyle) {
        case COMPACT -> 90;
        case BALANCED -> 160;
        case RICH -> 240;
        };
    }

    private String formatLine(MemoryItem item, int maxLen) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(itemType(item)).append("] ");
        if (item.getTitle() != null && !item.getTitle().isBlank()) {
            sb.append(item.getTitle().trim()).append(": ");
        }
        sb.append(truncate(normalize(item.getContent()), maxLen));
        return sb.toString().trim();
    }

    private String itemType(MemoryItem item) {
        return item.getType() != null ? item.getType().name() : "ITEM";
    }

    private String itemTitle(MemoryItem item) {
        if (item.getTitle() == null || item.getTitle().isBlank()) {
            return "";
        }
        return item.getTitle().trim() + ": ";
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLen - 3)) + "...";
    }
}
