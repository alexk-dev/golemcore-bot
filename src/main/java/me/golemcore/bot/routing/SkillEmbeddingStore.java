package me.golemcore.bot.routing;

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

import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.port.outbound.EmbeddingPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory vector store for skill embeddings used in semantic search.
 *
 * <p>
 * This component maintains precomputed embeddings for all available skills to
 * enable fast similarity search. For each skill, it stores:
 * <ul>
 * <li>Embedding vector - computed from skill name and description</li>
 * <li>Metadata - skill name and description for quick lookup</li>
 * </ul>
 *
 * <p>
 * The store supports batch indexing for efficiency and provides similarity
 * search using cosine similarity. Results are filtered by minimum score
 * threshold and limited to top-K candidates.
 *
 * <p>
 * Thread-safe implementation using {@link ConcurrentHashMap}.
 *
 * @since 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillEmbeddingStore {

    private final EmbeddingPort embeddingPort;

    /**
     * Map of skill name to its embedding vector.
     */
    private final Map<String, float[]> skillEmbeddings = new ConcurrentHashMap<>();

    /**
     * Map of skill name to its metadata (for quick lookup).
     */
    private final Map<String, SkillMetadata> skillMetadata = new ConcurrentHashMap<>();

    /**
     * Index a skill by computing its embedding.
     *
     * @param skill
     *            the skill to index
     */
    public void indexSkill(Skill skill) {
        try {
            // Create text for embedding: name + description
            String textToEmbed = skill.getName() + ": " + skill.getDescription();

            float[] embedding = embeddingPort.embed(textToEmbed).join();

            skillEmbeddings.put(skill.getName(), embedding);
            skillMetadata.put(skill.getName(), new SkillMetadata(
                    skill.getName(),
                    skill.getDescription()));

            log.debug("Indexed skill: {}", skill.getName());
        } catch (Exception e) {
            log.warn("Failed to index skill: {}", skill.getName(), e);
        }
    }

    /**
     * Index multiple skills in batch.
     *
     * @param skills
     *            skills to index
     */
    public void indexSkills(List<Skill> skills) {
        if (skills.isEmpty()) {
            return;
        }

        log.info("Indexing {} skills...", skills.size());

        try {
            // Prepare texts for batch embedding
            List<String> texts = skills.stream()
                    .map(s -> s.getName() + ": " + s.getDescription())
                    .toList();

            // Batch embed
            List<float[]> embeddings = embeddingPort.embedBatch(texts).join();

            // Store results
            for (int i = 0; i < skills.size(); i++) {
                Skill skill = skills.get(i);
                skillEmbeddings.put(skill.getName(), embeddings.get(i));
                skillMetadata.put(skill.getName(), new SkillMetadata(
                        skill.getName(),
                        skill.getDescription()));
            }

            log.info("Indexed {} skills successfully", skills.size());
        } catch (Exception e) {
            log.error("Batch indexing failed, falling back to individual indexing", e);
            // Fallback to individual indexing
            for (Skill skill : skills) {
                indexSkill(skill);
            }
        }
    }

    /**
     * Find top-K most similar skills for a query.
     *
     * @param queryEmbedding
     *            the query embedding
     * @param topK
     *            number of results to return
     * @param minScore
     *            minimum similarity score threshold
     * @return list of skill candidates sorted by score (descending)
     */
    public List<SkillCandidate> findSimilar(float[] queryEmbedding, int topK, double minScore) {
        log.debug("[EmbeddingStore] Finding similar skills (topK={}, minScore={}, indexed={})",
                topK, minScore, skillEmbeddings.size());

        List<SkillCandidate> candidates = new ArrayList<>();

        for (Map.Entry<String, float[]> entry : skillEmbeddings.entrySet()) {
            String skillName = entry.getKey();
            float[] skillEmbedding = entry.getValue();

            double similarity = embeddingPort.cosineSimilarity(queryEmbedding, skillEmbedding);
            log.debug("[EmbeddingStore] Skill '{}' similarity: {}", skillName, String.format("%.3f", similarity));

            if (similarity >= minScore) {
                SkillMetadata metadata = skillMetadata.get(skillName);
                candidates.add(SkillCandidate.builder()
                        .name(skillName)
                        .description(metadata != null ? metadata.description() : "")
                        .semanticScore(similarity)
                        .build());
            }
        }

        // Sort by score descending and take top-K
        List<SkillCandidate> result = candidates.stream()
                .sorted((a, b) -> Double.compare(b.getSemanticScore(), a.getSemanticScore()))
                .limit(topK)
                .toList();

        log.info("[EmbeddingStore] Found {} candidates above minScore {}",
                result.size(), minScore);
        for (SkillCandidate c : result) {
            log.debug("[EmbeddingStore]   - {} (score: {})", c.getName(), String.format("%.3f", c.getSemanticScore()));
        }

        return result;
    }

    /**
     * Clear all stored embeddings.
     */
    public void clear() {
        skillEmbeddings.clear();
        skillMetadata.clear();
        log.info("Skill embedding store cleared");
    }

    /**
     * Check if any skills are indexed.
     */
    public boolean isEmpty() {
        return skillEmbeddings.isEmpty();
    }

    /**
     * Get the number of indexed skills.
     */
    public int size() {
        return skillEmbeddings.size();
    }

    /**
     * Metadata for a skill (for quick lookup without full Skill object).
     */
    private record SkillMetadata(String name, String description) {
    }
}
