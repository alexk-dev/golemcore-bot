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

package me.golemcore.bot.adapter.outbound.embedding;

import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.EmbeddingPort;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Embedding adapter using langchain4j and OpenAI.
 *
 * <p>
 * This adapter provides text embedding via OpenAI's text-embedding-3-small
 * model (or configured alternative). Used by the hybrid skill routing system
 * for semantic similarity search.
 *
 * <p>
 * Default model: text-embedding-3-small (1536 dimensions)
 *
 * <p>
 * Configuration:
 * <ul>
 * <li>{@code bot.llm.langchain4j.providers.openai.api-key} - OpenAI API key
 * <li>{@code bot.router.skill-matcher.embedding.model} - Embedding model name
 * </ul>
 *
 * @see me.golemcore.bot.routing.SkillEmbeddingStore
 * @see me.golemcore.bot.port.outbound.EmbeddingPort
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Langchain4jEmbeddingAdapter implements EmbeddingPort {

    private final BotProperties properties;

    private volatile EmbeddingModel embeddingModel;
    private volatile boolean initialized = false;

    private static final String DEFAULT_MODEL = "text-embedding-3-small";
    private static final int DIMENSION = 1536;

    private synchronized void ensureInitialized() {
        if (initialized)
            return;

        var openaiConfig = properties.getLlm().getLangchain4j().getProviders().get("openai");
        String apiKey = openaiConfig != null ? openaiConfig.getApiKey() : null;
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OpenAI API key not configured, embedding service unavailable");
            initialized = true;
            return;
        }

        String model = properties.getRouter().getSkillMatcher().getEmbedding().getModel();
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }

        try {
            embeddingModel = OpenAiEmbeddingModel.builder()
                    .apiKey(apiKey)
                    .modelName(model)
                    .build();
            log.info("Embedding model initialized: {}", model);
        } catch (Exception e) {
            log.error("Failed to initialize embedding model", e);
        }

        initialized = true;
    }

    @Override
    public CompletableFuture<float[]> embed(String text) {
        return CompletableFuture.supplyAsync(() -> {
            ensureInitialized();

            if (embeddingModel == null) {
                throw new IllegalStateException("Embedding model not available");
            }

            Response<Embedding> response = embeddingModel.embed(text);
            return response.content().vector();
        });
    }

    @Override
    public CompletableFuture<List<float[]>> embedBatch(List<String> texts) {
        return CompletableFuture.supplyAsync(() -> {
            ensureInitialized();

            if (embeddingModel == null) {
                throw new IllegalStateException("Embedding model not available");
            }

            List<TextSegment> segments = texts.stream()
                    .map(TextSegment::from)
                    .toList();

            Response<List<Embedding>> response = embeddingModel.embedAll(segments);

            return response.content().stream()
                    .map(Embedding::vector)
                    .toList();
        });
    }

    @Override
    public int getDimension() {
        return DIMENSION;
    }

    @Override
    public String getModel() {
        String model = properties.getRouter().getSkillMatcher().getEmbedding().getModel();
        return model != null && !model.isBlank() ? model : DEFAULT_MODEL;
    }

    @Override
    public boolean isAvailable() {
        ensureInitialized();
        return embeddingModel != null;
    }
}
