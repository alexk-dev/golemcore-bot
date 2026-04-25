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
import me.golemcore.bot.domain.model.MemoryQuery;

import java.util.List;

/**
 * Normalized retrieval plan produced before candidates are loaded from storage.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoryRetrievalPlan {

    /**
     * Normalized query used by downstream retrieval stages.
     */
    private MemoryQuery query;

    /**
     * Ordered scope chain to search, including the global fallback scope.
     */
    private List<String> requestedScopes;

    /**
     * Primary requested scope, used only for diagnostics and metrics labels.
     */
    private String requestedScope;

    /**
     * Number of episodic days to scan during candidate collection.
     */
    private int episodicLookbackDays;
}
