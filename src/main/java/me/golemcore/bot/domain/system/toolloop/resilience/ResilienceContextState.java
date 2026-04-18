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

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Restores context mutations made by L4/L5 resilience layers after a successful
 * LLM call.
 */
final class ResilienceContextState {

    private ResilienceContextState() {
    }

    /**
     * Restores degraded request state and clears stale delayed retry attributes.
     *
     * @param context
     *            current agent context; may be {@code null}
     */
    static void restoreAfterSuccess(AgentContext context) {
        if (context == null || context.getAttributes() == null) {
            return;
        }
        restoreL4DegradationState(context);
        clearL5SuspensionState(context.getAttributes());
    }

    private static void restoreL4DegradationState(AgentContext context) {
        Map<String, Object> attributes = context.getAttributes();
        boolean hadOriginalTier = attributes.containsKey(ContextAttributes.RESILIENCE_L4_ORIGINAL_MODEL_TIER);
        Object originalTier = attributes.remove(ContextAttributes.RESILIENCE_L4_ORIGINAL_MODEL_TIER);
        if (hadOriginalTier) {
            context.setModelTier(originalTier instanceof String tier ? tier : null);
        }
        boolean hadOriginalTools = attributes.containsKey(ContextAttributes.RESILIENCE_L4_ORIGINAL_TOOLS);
        Object originalTools = attributes.remove(ContextAttributes.RESILIENCE_L4_ORIGINAL_TOOLS);
        if (hadOriginalTools) {
            context.setAvailableTools(copyToolDefinitions(originalTools));
        }
        attributes.remove(ContextAttributes.RESILIENCE_L4_MODEL_DOWNGRADE_ATTEMPTED);
        attributes.remove(ContextAttributes.RESILIENCE_L4_TOOL_STRIP_ATTEMPTED);
    }

    private static void clearL5SuspensionState(Map<String, Object> attributes) {
        attributes.remove(ContextAttributes.RESILIENCE_TURN_SUSPENDED);
        attributes.remove(ContextAttributes.RESILIENCE_L5_RESUME_ATTEMPT);
        attributes.remove(ContextAttributes.RESILIENCE_L5_ERROR_CODE);
        attributes.remove(ContextAttributes.RESILIENCE_L5_ORIGINAL_PROMPT);
    }

    private static List<ToolDefinition> copyToolDefinitions(Object originalTools) {
        List<ToolDefinition> restored = new ArrayList<>();
        if (originalTools instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof ToolDefinition toolDefinition) {
                    restored.add(toolDefinition);
                }
            }
        }
        return restored;
    }
}
