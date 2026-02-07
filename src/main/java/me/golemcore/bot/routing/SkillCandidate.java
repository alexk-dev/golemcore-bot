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

/**
 * Represents a skill candidate identified by semantic search.
 *
 * <p>
 * This model holds the skill's basic information along with its semantic
 * similarity score (0-1) computed by comparing embeddings between the user's
 * query and the skill's description.
 *
 * <p>
 * Candidates are passed to the LLM classifier for final selection or used
 * directly if semantic score is high enough.
 *
 * @since 1.0
 * @see HybridSkillMatcher
 */
@Data
@Builder
public class SkillCandidate {

    /**
     * Skill name.
     */
    private String name;

    /**
     * Skill description.
     */
    private String description;

    /**
     * Semantic similarity score (0-1).
     */
    private double semanticScore;

    /**
     * Create a summary for LLM prompt.
     */
    public String toPromptSummary() {
        return String.format("- **%s** (score: %.2f): %s", name, semanticScore, description);
    }
}
