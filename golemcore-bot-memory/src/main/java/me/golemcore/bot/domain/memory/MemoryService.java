package me.golemcore.bot.domain.memory;

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

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.memory.orchestrator.MemoryOrchestratorService;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.TurnMemoryEvent;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Compatibility bridge that preserves the existing {@link MemoryComponent} contract while delegating memory work to the
 * new orchestration layer.
 */
@Service
public class MemoryService implements MemoryComponent {

    private final MemoryOrchestratorService memoryOrchestratorService;

    public MemoryService(MemoryOrchestratorService memoryOrchestratorService) {
        this.memoryOrchestratorService = memoryOrchestratorService;
    }

    @Override
    public String getComponentType() {
        return "memory";
    }

    @Override
    public MemoryPack buildMemoryPack(MemoryQuery query) {
        return memoryOrchestratorService.buildMemoryPack(query);
    }

    @Override
    public void persistTurnMemory(TurnMemoryEvent event) {
        memoryOrchestratorService.persistTurnMemory(event);
    }

    @Override
    public List<MemoryItem> queryItems(MemoryQuery query) {
        return memoryOrchestratorService.queryItems(query);
    }

    @Override
    public void upsertSemanticItem(MemoryItem item) {
        memoryOrchestratorService.upsertSemanticItem(item);
    }

    @Override
    public void upsertProceduralItem(MemoryItem item) {
        memoryOrchestratorService.upsertProceduralItem(item);
    }
}
