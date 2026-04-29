package me.golemcore.bot.domain.memory.persistence;

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
import me.golemcore.bot.domain.memory.MemoryPromotionService;
import me.golemcore.bot.domain.memory.MemoryScopeSupport;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Promotes extracted episodic memories into longer-lived semantic or procedural stores when policy allows.
 */
@Service
public class MemoryPromotionOrchestrator {

    private final MemoryPromotionService memoryPromotionService;
    private final MemoryPersistenceOrchestrator memoryPersistenceOrchestrator;

    public MemoryPromotionOrchestrator(MemoryPromotionService memoryPromotionService,
            MemoryPersistenceOrchestrator memoryPersistenceOrchestrator) {
        this.memoryPromotionService = memoryPromotionService;
        this.memoryPersistenceOrchestrator = memoryPersistenceOrchestrator;
    }

    /**
     * Promote extracted episodic items into durable memory layers.
     *
     * @param items
     *            normalized episodic items to inspect
     */
    public void promote(List<MemoryItem> items) {
        if (items == null || items.isEmpty() || !memoryPromotionService.isPromotionEnabled()) {
            return;
        }

        for (MemoryItem item : items) {
            if (item == null) {
                continue;
            }
            if (memoryPromotionService.shouldPromoteToSemantic(item)) {
                MemoryItem promoted = cloneForLayer(item, MemoryItem.Layer.SEMANTIC);
                memoryPersistenceOrchestrator.upsertSemantic(promoted);
            }
            if (memoryPromotionService.shouldPromoteToProcedural(item)) {
                MemoryItem promoted = cloneForLayer(item, MemoryItem.Layer.PROCEDURAL);
                memoryPersistenceOrchestrator.upsertProcedural(promoted);
            }
        }
    }

    private MemoryItem cloneForLayer(MemoryItem source, MemoryItem.Layer layer) {
        return MemoryItem.builder().id(source.getId()).layer(layer).type(source.getType()).title(source.getTitle())
                .content(source.getContent()).scope(MemoryScopeSupport.GLOBAL_SCOPE)
                .tags(source.getTags() != null ? new ArrayList<>(source.getTags()) : List.of())
                .source(source.getSource()).confidence(source.getConfidence()).salience(source.getSalience())
                .ttlDays(source.getTtlDays()).createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt() != null ? source.getUpdatedAt() : Instant.now())
                .lastAccessedAt(source.getLastAccessedAt())
                .references(source.getReferences() != null ? new ArrayList<>(source.getReferences()) : List.of())
                .fingerprint(source.getFingerprint()).build();
    }
}
