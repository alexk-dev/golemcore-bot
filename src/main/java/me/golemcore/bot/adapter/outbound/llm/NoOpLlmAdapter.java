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

import me.golemcore.bot.domain.component.LlmComponent;
import me.golemcore.bot.domain.model.*;
import me.golemcore.bot.port.outbound.LlmPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * No-op LLM adapter for testing and when no LLM is configured.
 *
 * <p>
 * This adapter always returns placeholder responses without calling any
 * external LLM API. It is used as a fallback when no real LLM provider is
 * configured or available.
 *
 * <p>
 * Provider ID: {@code "none"}
 *
 * @see LlmProviderAdapter
 */
@Component
@Slf4j
public class NoOpLlmAdapter implements LlmProviderAdapter, LlmComponent {

    @Override
    public String getProviderId() {
        return "none";
    }

    @Override
    public void initialize() {
        // No-op, always ready
    }

    @Override
    public CompletableFuture<LlmResponse> chat(LlmRequest request) {
        log.warn("NoOpLlmAdapter: chat() called - no LLM configured");
        return CompletableFuture.completedFuture(LlmResponse.builder()
                .content("[No LLM configured]")
                .model("none")
                .finishReason("stop")
                .usage(LlmUsage.builder()
                        .inputTokens(0)
                        .outputTokens(0)
                        .totalTokens(0)
                        .build())
                .build());
    }

    @Override
    public Flux<LlmChunk> chatStream(LlmRequest request) {
        return Flux.just(LlmChunk.builder()
                .text("[No LLM configured]")
                .done(true)
                .build());
    }

    @Override
    public boolean supportsStreaming() {
        return false;
    }

    @Override
    public List<String> getSupportedModels() {
        return Collections.emptyList();
    }

    @Override
    public String getCurrentModel() {
        return "none";
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public LlmPort getLlmPort() {
        return this;
    }
}
