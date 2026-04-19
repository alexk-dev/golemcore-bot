package me.golemcore.bot.domain.system.toolloop.resilience;

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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small factory for resilience trace DTOs.
 */
final class ResilienceTraceSupport {

    private ResilienceTraceSupport() {
    }

    /**
     * Creates a trace step with normalized non-null attributes.
     */
    static LlmResilienceOrchestrator.ResilienceTraceStep traceStep(String layer, String action, String detail,
            Map<String, Object> attributes) {
        return new LlmResilienceOrchestrator.ResilienceTraceStep(layer, action, detail, attributes);
    }

    /**
     * Builds ordered trace attributes while skipping null values.
     */
    static Map<String, Object> traceAttributes(Object... entries) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (entries == null) {
            return attributes;
        }
        for (int index = 0; index + 1 < entries.length; index += 2) {
            Object keyObject = entries[index];
            if (keyObject instanceof String key && !key.isBlank() && entries[index + 1] != null) {
                attributes.put(key, entries[index + 1]);
            }
        }
        return attributes;
    }
}
