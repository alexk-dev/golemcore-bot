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
import me.golemcore.bot.domain.model.LlmChunk;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for selecting LLM adapters based on configuration.
 *
 * <p>
 * This factory manages multiple LLM provider adapters and selects the active
 * one based on {@code bot.llm.provider} configuration:
 * <ul>
 * <li>langchain4j - OpenAI, Anthropic via langchain4j library
 * <li>custom - Custom OpenAI-compatible endpoints
 * <li>none - No-op adapter for testing
 * </ul>
 *
 * <p>
 * All adapters are always available as Spring beans. Selection happens at
 * runtime in {@link #init()} based on configuration.
 *
 * @see LlmProviderAdapter
 * @see Langchain4jAdapter
 * @see CustomLlmAdapter
 * @see NoOpLlmAdapter
 */
@Component
@Primary
@RequiredArgsConstructor
@Slf4j
public class LlmAdapterFactory implements LlmPort {

    private static final String PROVIDER_NONE = "none";

    private final BotProperties properties;
    private final List<LlmProviderAdapter> adapters;

    private final Map<String, LlmProviderAdapter> adaptersByProvider = new ConcurrentHashMap<>();
    private LlmProviderAdapter activeAdapter;

    @PostConstruct
    public void init() {
        // Index adapters by provider ID
        for (LlmProviderAdapter adapter : adapters) {
            adaptersByProvider.put(adapter.getProviderId(), adapter);
            log.debug("Registered LLM adapter: {}", adapter.getProviderId());
        }

        // Select active adapter based on config
        String provider = properties.getLlm().getProvider();
        activeAdapter = adaptersByProvider.get(provider);

        if (activeAdapter == null) {
            // Fallback to noop if configured provider not found
            activeAdapter = adaptersByProvider.get(PROVIDER_NONE);
            if (activeAdapter == null && !adapters.isEmpty()) {
                activeAdapter = adapters.get(0);
            }
            log.warn("Provider '{}' not found, using: {}",
                    provider, activeAdapter != null ? activeAdapter.getProviderId() : PROVIDER_NONE);
        } else {
            log.info("Active LLM provider: {}", provider);
        }
    }

    /**
     * Get the active LLM adapter based on current configuration.
     */
    public LlmPort getActiveAdapter() {
        return activeAdapter;
    }

    /**
     * Get adapter by provider ID.
     */
    public LlmPort getAdapter(String providerId) {
        return adaptersByProvider.get(providerId);
    }

    /**
     * Get all available adapters.
     */
    public Map<String, LlmProviderAdapter> getAllAdapters() {
        return Map.copyOf(adaptersByProvider);
    }

    /**
     * Check if a provider is available.
     */
    public boolean isProviderAvailable(String providerId) {
        LlmProviderAdapter adapter = adaptersByProvider.get(providerId);
        return adapter != null && adapter.isAvailable();
    }

    /**
     * Get the active LlmComponent.
     */
    public LlmComponent getActiveLlmComponent() {
        if (activeAdapter instanceof LlmComponent) {
            return (LlmComponent) activeAdapter;
        }
        return null;
    }

    // ==================== LlmPort delegation ====================

    @Override
    public String getProviderId() {
        return activeAdapter != null ? activeAdapter.getProviderId() : PROVIDER_NONE;
    }

    @Override
    public CompletableFuture<LlmResponse> chat(LlmRequest request) {
        return activeAdapter.chat(request);
    }

    @Override
    public Flux<LlmChunk> chatStream(LlmRequest request) {
        return activeAdapter.chatStream(request);
    }

    @Override
    public boolean supportsStreaming() {
        return activeAdapter != null && activeAdapter.supportsStreaming();
    }

    @Override
    public List<String> getSupportedModels() {
        return activeAdapter != null ? activeAdapter.getSupportedModels() : List.of();
    }

    @Override
    public String getCurrentModel() {
        return activeAdapter != null ? activeAdapter.getCurrentModel() : PROVIDER_NONE;
    }

    @Override
    public boolean isAvailable() {
        return activeAdapter != null && activeAdapter.isAvailable();
    }
}
