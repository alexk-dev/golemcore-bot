package me.golemcore.bot.domain.component;

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

/**
 * Base interface for all components in the agent system. Components provide
 * modular, pluggable functionality that can be attached to agent sessions. Each
 * component type (skill, tool, memory, llm, browser, sanitizer) extends this
 * interface to provide specialized capabilities while maintaining a consistent
 * lifecycle contract.
 */
public interface Component {

    /**
     * Returns the unique type identifier for this component.
     *
     * @return the component type (e.g., "skill", "tool", "memory")
     */
    String getComponentType();

    /**
     * Initializes the component, allocating resources or performing setup tasks.
     * Default implementation does nothing.
     */
    default void initialize() {
        // Default no-op
    }

    /**
     * Cleans up component resources and performs shutdown tasks. Default
     * implementation does nothing.
     */
    default void destroy() {
        // Default no-op
    }

    /**
     * Checks whether this component is currently enabled and available for use.
     * Components with unmet requirements or disabled via configuration return
     * false.
     *
     * @return true if the component is enabled, false otherwise
     */
    default boolean isEnabled() {
        return true;
    }
}
