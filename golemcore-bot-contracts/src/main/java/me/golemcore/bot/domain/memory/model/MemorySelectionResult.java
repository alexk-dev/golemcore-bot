package me.golemcore.bot.domain.memory.model;

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
import me.golemcore.bot.domain.model.MemoryScoredItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of budget-aware candidate selection before disclosure planning.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemorySelectionResult {

    @Builder.Default
    private List<MemoryScoredItem> selectedCandidates = new ArrayList<>();

    private int candidateCount;
    private int selectedCount;
    private int droppedByBudget;
    private int estimatedTokens;
    private int softBudgetTokens;
    private int maxBudgetTokens;
}
