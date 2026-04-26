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

import me.golemcore.bot.domain.memory.model.MemoryDisclosureInput;
import me.golemcore.bot.domain.memory.model.MemoryDisclosureMode;
import me.golemcore.bot.domain.memory.model.MemoryPackSectionType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps disclosure settings to the ordered sections that should appear in the prompt-facing memory pack.
 */
@Service
public class MemoryDisclosurePolicy {

    /**
     * Determine which sections should be assembled for the supplied input.
     *
     * @param input
     *            disclosure input
     *
     * @return ordered section list
     */
    public List<MemoryPackSectionType> determineSections(MemoryDisclosureInput input) {
        List<MemoryPackSectionType> sections = new ArrayList<>();
        MemoryDisclosureMode mode = input.getDisclosureMode();
        if (mode == MemoryDisclosureMode.INDEX) {
            sections.add(MemoryPackSectionType.INDEX);
            if (input.isDisclosureHintsEnabled()) {
                sections.add(MemoryPackSectionType.FOLLOWUP_HINTS);
            }
            return sections;
        }

        sections.add(MemoryPackSectionType.WORKING_MEMORY);
        sections.add(MemoryPackSectionType.EPISODIC_MEMORY);
        sections.add(MemoryPackSectionType.SEMANTIC_MEMORY);
        sections.add(MemoryPackSectionType.PROCEDURAL_MEMORY);

        if (mode == MemoryDisclosureMode.SELECTIVE_DETAIL) {
            sections.add(MemoryPackSectionType.DETAIL_SNIPPETS);
        }

        if (input.isDisclosureHintsEnabled() && mode != MemoryDisclosureMode.FULL_PACK) {
            sections.add(MemoryPackSectionType.FOLLOWUP_HINTS);
        }
        return sections;
    }
}
