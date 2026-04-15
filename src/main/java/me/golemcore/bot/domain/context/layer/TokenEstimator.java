package me.golemcore.bot.domain.context.layer;

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

/**
 * Shared cheap character-based token estimator used by every
 * {@link ContextLayer} to report the approximate token footprint of its
 * rendered content.
 *
 * <p>
 * The heuristic divides the character length by {@value #CHARS_PER_TOKEN} and
 * rounds up &mdash; this is a deliberately rough, tokenizer-agnostic upper
 * bound suitable for budgeting pipeline layers without pulling a real tokenizer
 * into the hot path. Values below &frac12; a token are still reported as one
 * token so that zero-length output distinguishes cleanly from any rendered
 * content.
 *
 * <p>
 * Rationale for factoring this out of individual layers: keeping the formula in
 * a single place removes copy-paste drift between layers, gives us a single
 * regression-test surface for the estimator, and makes it cheap to swap in a
 * smarter estimator in the future without touching every layer.
 */
public final class TokenEstimator {

    /**
     * Average number of characters per token in the English prompts the agent
     * produces; chosen empirically to bias slightly high so budgeting decisions err
     * on the side of reserving headroom.
     */
    static final double CHARS_PER_TOKEN = 3.5;

    private TokenEstimator() {
        // utility class; prevent instantiation
    }

    /**
     * Estimate the number of tokens a string contributes to the prompt budget.
     *
     * @param text
     *            rendered layer content (may be {@code null} or empty)
     * @return a non-negative integer approximation; zero for {@code null} or empty
     *         inputs
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
