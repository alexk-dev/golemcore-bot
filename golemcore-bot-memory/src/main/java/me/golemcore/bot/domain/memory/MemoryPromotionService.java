package me.golemcore.bot.domain.memory;

import me.golemcore.bot.domain.runtimeconfig.MemoryRuntimeConfigView;

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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Promotion policy for Memory V2 layer transitions.
 */
@Service
public class MemoryPromotionService {

    private final MemoryRuntimeConfigView runtimeConfigService;

    public MemoryPromotionService(MemoryRuntimeConfigView runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    public boolean isPromotionEnabled() {
        return runtimeConfigService.isMemoryPromotionEnabled();
    }

    /**
     * Determine durable target layers for a candidate episodic item.
     *
     * @param item
     *            source item to inspect
     *
     * @return target layers allowed by promotion policy
     */
    public List<MemoryItem.Layer> determinePromotionLayers(MemoryItem item) {
        List<MemoryItem.Layer> layers = new ArrayList<>();
        if (shouldPromoteToSemantic(item)) {
            layers.add(MemoryItem.Layer.SEMANTIC);
        }
        if (shouldPromoteToProcedural(item)) {
            layers.add(MemoryItem.Layer.PROCEDURAL);
        }
        return layers;
    }

    public boolean shouldPromoteToSemantic(MemoryItem item) {
        if (!hasPromotionConfidence(item)) {
            return false;
        }

        return item.getType() == MemoryItem.Type.CONSTRAINT || item.getType() == MemoryItem.Type.PREFERENCE
                || item.getType() == MemoryItem.Type.PROJECT_FACT || item.getType() == MemoryItem.Type.DECISION;
    }

    public boolean shouldPromoteToProcedural(MemoryItem item) {
        if (!hasPromotionConfidence(item)) {
            return false;
        }

        return item.getType() == MemoryItem.Type.FAILURE || item.getType() == MemoryItem.Type.FIX
                || item.getType() == MemoryItem.Type.COMMAND_RESULT;
    }

    private boolean hasPromotionConfidence(MemoryItem item) {
        if (item == null) {
            return false;
        }

        double minConfidence = runtimeConfigService.getMemoryPromotionMinConfidence();
        double confidence = defaultDouble(item.getConfidence(), 0.0);
        return confidence >= minConfidence;
    }

    private double defaultDouble(Double value, double fallback) {
        return value != null ? value : fallback;
    }
}
