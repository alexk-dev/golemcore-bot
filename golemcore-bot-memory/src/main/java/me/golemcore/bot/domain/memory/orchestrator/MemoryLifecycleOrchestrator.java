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

import me.golemcore.bot.domain.memory.persistence.MemoryNormalizationService;
import me.golemcore.bot.domain.memory.persistence.MemoryPersistenceOrchestrator;
import me.golemcore.bot.domain.memory.persistence.MemoryPromotionOrchestrator;
import me.golemcore.bot.domain.memory.persistence.TurnMemoryExtractionOrchestrator;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.TurnMemoryEvent;
import me.golemcore.bot.domain.memory.MemoryScopeSupport;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Coordinates memory persistence and promotion entry points.
 * <p>
 * This shell keeps the high-level lifecycle readable while the low-level JSONL storage logic remains in specialized
 * write services.
 */
@Service
public class MemoryLifecycleOrchestrator {

    private final RuntimeConfigService runtimeConfigService;
    private final TurnMemoryExtractionOrchestrator turnMemoryExtractionOrchestrator;
    private final MemoryNormalizationService memoryNormalizationService;
    private final MemoryPersistenceOrchestrator memoryPersistenceOrchestrator;
    private final MemoryPromotionOrchestrator memoryPromotionOrchestrator;

    public MemoryLifecycleOrchestrator(RuntimeConfigService runtimeConfigService,
            TurnMemoryExtractionOrchestrator turnMemoryExtractionOrchestrator,
            MemoryNormalizationService memoryNormalizationService,
            MemoryPersistenceOrchestrator memoryPersistenceOrchestrator,
            MemoryPromotionOrchestrator memoryPromotionOrchestrator) {
        this.runtimeConfigService = runtimeConfigService;
        this.turnMemoryExtractionOrchestrator = turnMemoryExtractionOrchestrator;
        this.memoryNormalizationService = memoryNormalizationService;
        this.memoryPersistenceOrchestrator = memoryPersistenceOrchestrator;
        this.memoryPromotionOrchestrator = memoryPromotionOrchestrator;
    }

    /**
     * Persist turn memory into the structured stores.
     *
     * @param event
     *            turn payload to persist
     */
    public void persistTurnMemory(TurnMemoryEvent event) {
        if (event == null || !runtimeConfigService.isMemoryEnabled()) {
            return;
        }

        String scope = MemoryScopeSupport.normalizeScopeOrGlobal(event.getScope());
        List<MemoryItem> extractedItems = turnMemoryExtractionOrchestrator.extract(event, scope);
        List<MemoryItem> normalizedItems = memoryNormalizationService.normalizeExtractedItems(extractedItems, scope);
        if (normalizedItems.isEmpty()) {
            return;
        }

        memoryPersistenceOrchestrator.appendEpisodic(normalizedItems, scope);
        memoryPromotionOrchestrator.promote(normalizedItems);
    }

    /**
     * Upsert a semantic memory item.
     *
     * @param item
     *            semantic item to upsert
     */
    public void upsertSemanticItem(MemoryItem item) {
        if (item == null || !runtimeConfigService.isMemoryEnabled()) {
            return;
        }

        String scope = MemoryScopeSupport.normalizeScopeOrGlobal(item.getScope());
        MemoryItem normalized = memoryNormalizationService.normalizeForLayer(item, MemoryItem.Layer.SEMANTIC, scope);
        memoryPersistenceOrchestrator.upsertSemantic(normalized);
    }

    /**
     * Upsert a procedural memory item.
     *
     * @param item
     *            procedural item to upsert
     */
    public void upsertProceduralItem(MemoryItem item) {
        if (item == null || !runtimeConfigService.isMemoryEnabled()) {
            return;
        }

        String scope = MemoryScopeSupport.normalizeScopeOrGlobal(item.getScope());
        MemoryItem normalized = memoryNormalizationService.normalizeForLayer(item, MemoryItem.Layer.PROCEDURAL, scope);
        memoryPersistenceOrchestrator.upsertProcedural(normalized);
    }
}
