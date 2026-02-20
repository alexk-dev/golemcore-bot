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
import me.golemcore.bot.domain.model.MemoryItem;
import org.springframework.stereotype.Service;

/**
 * Promotion policy for Memory V2 layer transitions.
 */
@Service
@RequiredArgsConstructor
public class MemoryPromotionService {

    private final RuntimeConfigService runtimeConfigService;

    public boolean isPromotionEnabled() {
        return runtimeConfigService.isMemoryPromotionEnabled();
    }

    public boolean shouldPromoteToSemantic(MemoryItem item) {
        if (!hasPromotionConfidence(item)) {
            return false;
        }

        return item.getType() == MemoryItem.Type.CONSTRAINT
                || item.getType() == MemoryItem.Type.PREFERENCE
                || item.getType() == MemoryItem.Type.PROJECT_FACT
                || item.getType() == MemoryItem.Type.DECISION;
    }

    public boolean shouldPromoteToProcedural(MemoryItem item) {
        if (!hasPromotionConfidence(item)) {
            return false;
        }

        return item.getType() == MemoryItem.Type.FAILURE
                || item.getType() == MemoryItem.Type.FIX
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
