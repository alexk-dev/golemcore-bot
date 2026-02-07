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

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result of a skill matching operation containing the selected skill and
 * routing metadata.
 *
 * <p>
 * This model encapsulates the outcome of hybrid skill matching:
 * <ul>
 * <li>Selected skill - the best matching skill name (or null if no match)</li>
 * <li>Confidence - matching confidence score (0-1)</li>
 * <li>Model tier - recommended LLM tier (fast/balanced/smart/coding)</li>
 * <li>Reason - explanation for the selection</li>
 * <li>Candidates - all candidates considered during matching</li>
 * <li>Performance metrics - latency, cache status, classifier usage</li>
 * </ul>
 *
 * <p>
 * Factory methods {@link #noMatch(String)} and
 * {@link #fromSemantic(SkillCandidate, List)} provide convenient result
 * construction.
 *
 * @since 1.0
 * @see HybridSkillMatcher
 */
@Data
@Builder
public class SkillMatchResult {

    /**
     * Selected skill name, or null if no skill matches.
     */
    private String selectedSkill;

    /**
     * Confidence score (0-1).
     */
    private double confidence;

    /**
     * Recommended model tier: fast, balanced, smart, coding.
     */
    private String modelTier;

    /**
     * Reason for the selection (from LLM classifier or semantic match).
     */
    private String reason;

    /**
     * All skill candidates considered.
     */
    private List<SkillCandidate> candidates;

    /**
     * Whether the result was cached.
     */
    @Builder.Default
    private boolean cached = false;

    /**
     * Latency of the matching operation in milliseconds.
     */
    private long latencyMs;

    /**
     * Whether LLM classifier was used (vs pure semantic).
     */
    @Builder.Default
    private boolean llmClassifierUsed = false;

    /**
     * Create a result indicating no skill matched.
     */
    public static SkillMatchResult noMatch(String reason) {
        return SkillMatchResult.builder()
                .selectedSkill(null)
                .confidence(1.0)
                .modelTier("fast")
                .reason(reason)
                .build();
    }

    /**
     * Create a result from semantic search only.
     */
    public static SkillMatchResult fromSemantic(SkillCandidate candidate, List<SkillCandidate> allCandidates) {
        return SkillMatchResult.builder()
                .selectedSkill(candidate.getName())
                .confidence(candidate.getSemanticScore())
                .modelTier("balanced") // Default tier for semantic-only match
                .reason("High-confidence semantic match")
                .candidates(allCandidates)
                .llmClassifierUsed(false)
                .build();
    }

    /**
     * Check if a skill was matched.
     */
    public boolean hasMatch() {
        return selectedSkill != null && !selectedSkill.isBlank();
    }
}
