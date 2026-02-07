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

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Skill;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service interface for matching user requests to the most appropriate skill.
 *
 * <p>
 * Implementations use a hybrid approach combining:
 * <ul>
 * <li>Semantic search (embeddings) for fast pre-filtering</li>
 * <li>LLM classification for accurate final selection</li>
 * </ul>
 *
 * <p>
 * The matcher must be initialized by calling {@link #indexSkills(List)} before
 * use. Skills should be re-indexed when they are added, removed, or modified.
 *
 * @since 1.0
 * @see HybridSkillMatcher
 */
public interface SkillMatcher {

    /**
     * Find the best matching skill for a user request.
     *
     * @param userMessage
     *            the user's message
     * @param conversationHistory
     *            recent conversation history for context
     * @param availableSkills
     *            list of available skills to match against
     * @return match result with selected skill, confidence, and model tier
     */
    CompletableFuture<SkillMatchResult> match(
            String userMessage,
            List<Message> conversationHistory,
            List<Skill> availableSkills);

    /**
     * Pre-compute and index embeddings for skills. Should be called when skills are
     * loaded/reloaded.
     *
     * @param skills
     *            list of skills to index
     */
    void indexSkills(List<Skill> skills);

    /**
     * Clear the skill embeddings cache.
     */
    void clearIndex();

    /**
     * Check if the matcher is ready (skills indexed).
     */
    boolean isReady();

    /**
     * Check if the matcher is enabled.
     */
    boolean isEnabled();
}
