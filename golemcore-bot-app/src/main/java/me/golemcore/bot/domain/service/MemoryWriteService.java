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

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.memory.orchestrator.MemoryLifecycleOrchestrator;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.TurnMemoryEvent;
import org.springframework.stereotype.Service;

/**
 * Compatibility bridge for legacy callers that still depend on the historical
 * memory write service entry points.
 */
@Service
@RequiredArgsConstructor
public class MemoryWriteService {

    private final MemoryLifecycleOrchestrator memoryLifecycleOrchestrator;

    /**
     * Persist finalized turn memory through the lifecycle orchestrator.
     *
     * @param event
     *            turn event to persist
     */
    public void persistTurnMemory(TurnMemoryEvent event) {
        memoryLifecycleOrchestrator.persistTurnMemory(event);
    }

    /**
     * Upsert a semantic item through the lifecycle orchestrator.
     *
     * @param item
     *            semantic item to upsert
     */
    public void upsertSemanticItem(MemoryItem item) {
        memoryLifecycleOrchestrator.upsertSemanticItem(item);
    }

    /**
     * Upsert a procedural item through the lifecycle orchestrator.
     *
     * @param item
     *            procedural item to upsert
     */
    public void upsertProceduralItem(MemoryItem item) {
        memoryLifecycleOrchestrator.upsertProceduralItem(item);
    }
}
