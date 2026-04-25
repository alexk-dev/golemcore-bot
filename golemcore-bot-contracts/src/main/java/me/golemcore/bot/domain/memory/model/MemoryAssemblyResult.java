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
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured result of prompt-facing memory assembly.
 *
 * <p>
 * The orchestrator returns both the final {@link MemoryPack} and the
 * intermediate artifacts that are useful for diagnostics tests and future
 * expansion.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoryAssemblyResult {

    /**
     * Normalized query used for retrieval and prompt assembly.
     */
    private MemoryQuery query;

    /**
     * Selected scored candidates returned by retrieval.
     */
    @Builder.Default
    private List<MemoryScoredItem> scoredItems = new ArrayList<>();

    /**
     * Final prompt-facing memory pack.
     */
    private MemoryPack memoryPack;

    /**
     * Final diagnostics map attached to the prompt-facing memory pack.
     */
    @Builder.Default
    private Map<String, Object> diagnostics = new LinkedHashMap<>();
}
