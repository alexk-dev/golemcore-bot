package me.golemcore.bot.domain.system;

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

/**
 * Base interface for all agent processing systems in the ordered pipeline.
 * Systems implement specific stages of message processing (sanitization,
 * routing, context building, LLM execution, tool execution, persistence,
 * response routing). Execution order is determined by the @Order annotation
 * value. Each system receives and returns an {@link AgentContext} that flows
 * through the pipeline, accumulating state modifications.
 */
public interface AgentSystem {

    /**
     * Get the system name.
     */
    String getName();

    /**
     * Get the processing order (lower = earlier).
     */
    int getOrder();

    /**
     * Process the context. Returns the (possibly modified) context.
     */
    AgentContext process(AgentContext context);

    /**
     * Check if this system should process the given context.
     */
    default boolean shouldProcess(AgentContext context) {
        return true;
    }

    /**
     * Check if system is enabled.
     */
    default boolean isEnabled() {
        return true;
    }
}
