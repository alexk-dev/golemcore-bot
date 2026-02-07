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

import me.golemcore.bot.domain.model.LlmChunk;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.port.outbound.LlmPort;
import reactor.core.publisher.Flux;

import java.util.concurrent.CompletableFuture;

/**
 * Component providing access to Large Language Model (LLM) providers. Wraps an
 * {@link LlmPort} to enable chat completion requests with optional streaming
 * support. Supports multiple providers (OpenAI, Anthropic, custom endpoints)
 * via the LlmAdapterFactory pattern.
 */
public interface LlmComponent extends Component {

    @Override
    default String getComponentType() {
        return "llm";
    }

    /**
     * Returns the underlying LLM port implementation.
     *
     * @return the LLM port
     */
    LlmPort getLlmPort();

    /**
     * Executes a chat completion request and returns the full response.
     *
     * @param request
     *            the LLM request with messages and parameters
     * @return a future containing the complete LLM response
     */
    default CompletableFuture<LlmResponse> chat(LlmRequest request) {
        return getLlmPort().chat(request);
    }

    /**
     * Executes a streaming chat completion request, emitting chunks as they arrive.
     *
     * @param request
     *            the LLM request with messages and parameters
     * @return a Flux stream of response chunks
     */
    default Flux<LlmChunk> chatStream(LlmRequest request) {
        return getLlmPort().chatStream(request);
    }

    /**
     * Checks whether this component supports streaming responses.
     *
     * @return true if streaming is supported
     */
    default boolean supportsStreaming() {
        return getLlmPort().supportsStreaming();
    }

    /**
     * Returns the currently configured model name.
     *
     * @return the model identifier (e.g., "gpt-4", "claude-3-opus")
     */
    default String getModel() {
        return getLlmPort().getCurrentModel();
    }

    /**
     * Returns the provider identifier for this LLM component.
     *
     * @return the provider ID (e.g., "langchain4j", "custom")
     */
    String getProviderId();
}
