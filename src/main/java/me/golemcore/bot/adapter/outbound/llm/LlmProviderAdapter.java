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

package me.golemcore.bot.adapter.outbound.llm;

import me.golemcore.bot.port.outbound.LlmPort;

/**
 * Interface for LLM provider adapters.
 *
 * <p>
 * Extends {@link LlmPort} with provider identification and availability checks.
 * All provider adapters must implement this interface to be managed by
 * {@link LlmAdapterFactory}.
 *
 * @see LlmAdapterFactory
 */
public interface LlmProviderAdapter extends LlmPort {

    /**
     * Get the provider ID (e.g., "langchain4j", "none").
     */
    String getProviderId();

    /**
     * Initialize the adapter. Called when adapter is selected.
     */
    default void initialize() {
        // Default no-op
    }

    /**
     * Check if this adapter can be used (e.g., API key is configured).
     */
    boolean isAvailable();
}
