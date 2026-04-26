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

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.memory.model.MemoryDisclosureInput;
import me.golemcore.bot.domain.memory.model.MemoryDisclosurePlan;
import org.springframework.stereotype.Service;

/**
 * Builds the concrete disclosure plan used by section assembly and rendering.
 */
@Service
@RequiredArgsConstructor
public class MemoryDisclosurePlanner {

    private final MemoryDisclosurePolicy memoryDisclosurePolicy;

    /**
     * Plan the progressive-disclosure output for a selected memory set.
     *
     * @param input
     *            disclosure input
     *
     * @return disclosure plan
     */
    public MemoryDisclosurePlan plan(MemoryDisclosureInput input) {
        return MemoryDisclosurePlan.builder().selectionResult(input.getSelectionResult())
                .disclosureMode(input.getDisclosureMode()).promptStyle(input.getPromptStyle())
                .toolExpansionEnabled(input.isToolExpansionEnabled())
                .disclosureHintsEnabled(input.isDisclosureHintsEnabled()).detailMinScore(input.getDetailMinScore())
                .sectionTypes(memoryDisclosurePolicy.determineSections(input)).build();
    }
}
