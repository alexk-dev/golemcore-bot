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

import java.util.concurrent.CompletableFuture;

/**
 * Port for RAG (Retrieval-Augmented Generation) operations using LightRAG.
 * Provides semantic long-term memory via knowledge graph retrieval and document
 * indexing. Conversations are indexed asynchronously for future context
 * retrieval.
 */
public interface RagPort {

    /**
     * Query the RAG system for relevant context.
     *
     * @param query
     *            the search query
     * @param mode
     *            query mode (naive, local, global, hybrid)
     * @return formatted text from RAG, or empty string if unavailable
     */
    CompletableFuture<String> query(String query, String mode);

    /**
     * Index content into the RAG system for future retrieval.
     *
     * @param content
     *            text content to index
     */
    CompletableFuture<Void> index(String content);

    /**
     * Check if the RAG system is available (enabled + healthy).
     */
    boolean isAvailable();
}
