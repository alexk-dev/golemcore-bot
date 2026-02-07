package me.golemcore.bot.port.outbound;

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

import me.golemcore.bot.domain.model.LlmChunk;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Port for integrating with LLM providers (OpenAI, Anthropic, etc.). Provides
 * chat completion with function calling support and optional streaming.
 */
public interface LlmPort {

    /**
     * Returns the provider identifier (e.g., "openai", "anthropic").
     */
    String getProviderId();

    /**
     * Executes a chat completion request and returns the full response.
     */
    CompletableFuture<LlmResponse> chat(LlmRequest request);

    /**
     * Executes a streaming chat request, returning incremental chunks. Default
     * implementation throws UnsupportedOperationException; providers should
     * override if streaming is supported.
     */
    default Flux<LlmChunk> chatStream(LlmRequest request) {
        throw new UnsupportedOperationException("Streaming not supported by this provider");
    }

    /**
     * Checks if this provider supports streaming responses.
     */
    default boolean supportsStreaming() {
        return false;
    }

    /**
     * Returns the list of model identifiers supported by this provider.
     */
    List<String> getSupportedModels();

    /**
     * Returns the current or default model identifier used by this provider.
     */
    String getCurrentModel();

    /**
     * Checks if the provider is configured and operational.
     */
    boolean isAvailable();
}
