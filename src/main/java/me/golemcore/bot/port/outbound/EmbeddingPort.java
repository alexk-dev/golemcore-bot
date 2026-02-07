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

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Port for generating text embeddings (dense vector representations). Used for
 * semantic similarity search in skill routing and other RAG use cases.
 */
public interface EmbeddingPort {

    /**
     * Generate embedding for a single text.
     *
     * @param text
     *            the text to embed
     * @return vector representation
     */
    CompletableFuture<float[]> embed(String text);

    /**
     * Generate embeddings for multiple texts (batch).
     *
     * @param texts
     *            list of texts to embed
     * @return list of vector representations
     */
    CompletableFuture<List<float[]>> embedBatch(List<String> texts);

    /**
     * Get the embedding dimension.
     *
     * @return vector dimension (e.g., 1536 for OpenAI text-embedding-3-small)
     */
    int getDimension();

    /**
     * Get the model name.
     *
     * @return model identifier
     */
    String getModel();

    /**
     * Check if the embedding service is available.
     */
    boolean isAvailable();

    /**
     * Calculates cosine similarity between two embedding vectors. Returns a value
     * between -1 and 1, where 1 means identical direction.
     */
    default double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have same length");
        }

        double dotProduct = 0;
        double normA = 0;
        double normB = 0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) {
            return 0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
