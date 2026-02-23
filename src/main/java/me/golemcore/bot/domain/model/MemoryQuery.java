package me.golemcore.bot.domain.model;

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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Retrieval parameters for building a memory pack in prompt context.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoryQuery {

    private String queryText;
    private String activeSkill;

    @Builder.Default
    private String scope = "global";

    @Builder.Default
    private Integer softPromptBudgetTokens = 1800;

    @Builder.Default
    private Integer maxPromptBudgetTokens = 3500;

    @Builder.Default
    private Integer workingTopK = 6;

    @Builder.Default
    private Integer episodicTopK = 8;

    @Builder.Default
    private Integer semanticTopK = 6;

    @Builder.Default
    private Integer proceduralTopK = 4;
}
