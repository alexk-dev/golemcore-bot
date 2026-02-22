package me.golemcore.bot.domain.component;

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

import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.TurnMemoryEvent;

import java.util.List;

/**
 * Component providing access to structured Memory V2 storage and retrieval.
 */
public interface MemoryComponent extends Component {

    @Override
    default String getComponentType() {
        return "memory";
    }

    /**
     * Build a scored memory pack for prompt injection.
     */
    default MemoryPack buildMemoryPack(MemoryQuery query) {
        return MemoryPack.builder()
                .items(List.of())
                .diagnostics(java.util.Map.of())
                .renderedContext("")
                .build();
    }

    /**
     * Persist structured turn data for Memory V2 stores.
     */
    default void persistTurnMemory(TurnMemoryEvent event) {
    }

    /**
     * Query structured memory items.
     */
    default List<MemoryItem> queryItems(MemoryQuery query) {
        return List.of();
    }

    /**
     * Upsert a semantic memory item.
     */
    default void upsertSemanticItem(MemoryItem item) {
    }

    /**
     * Upsert a procedural memory item.
     */
    default void upsertProceduralItem(MemoryItem item) {
    }
}
