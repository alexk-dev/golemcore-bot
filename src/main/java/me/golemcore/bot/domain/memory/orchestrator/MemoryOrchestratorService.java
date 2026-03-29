package me.golemcore.bot.domain.memory.orchestrator;

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
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.TurnMemoryEvent;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * High-level memory orchestration entry point.
 *
 * <p>
 * This service exposes human-readable methods for memory assembly and
 * persistence while delegating the actual work to specialized orchestrators.
 */
@Service
@RequiredArgsConstructor
public class MemoryOrchestratorService {

    private final MemoryContextOrchestrator memoryContextOrchestrator;
    private final MemoryLifecycleOrchestrator memoryLifecycleOrchestrator;

    /**
     * Build a prompt-ready memory pack.
     *
     * @param query
     *            turn query or {@code null} to use runtime defaults
     * @return assembled prompt pack
     */
    public MemoryPack buildMemoryPack(MemoryQuery query) {
        return memoryContextOrchestrator.buildMemoryPack(query);
    }

    /**
     * Query memory items without rendering prompt text.
     *
     * @param query
     *            query parameters
     * @return matching memory items
     */
    public List<MemoryItem> queryItems(MemoryQuery query) {
        return memoryContextOrchestrator.queryItems(query);
    }

    /**
     * Persist structured turn memory.
     *
     * @param event
     *            turn event to persist
     */
    public void persistTurnMemory(TurnMemoryEvent event) {
        memoryLifecycleOrchestrator.persistTurnMemory(event);
    }

    /**
     * Upsert a semantic memory item.
     *
     * @param item
     *            semantic item to upsert
     */
    public void upsertSemanticItem(MemoryItem item) {
        memoryLifecycleOrchestrator.upsertSemanticItem(item);
    }

    /**
     * Upsert a procedural memory item.
     *
     * @param item
     *            procedural item to upsert
     */
    public void upsertProceduralItem(MemoryItem item) {
        memoryLifecycleOrchestrator.upsertProceduralItem(item);
    }
}
