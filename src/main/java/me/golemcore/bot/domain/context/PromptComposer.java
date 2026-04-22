package me.golemcore.bot.domain.context;

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

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Composes a {@link ContextBlueprint} into the final system prompt string.
 *
 * <p>
 * The composer iterates over all {@link ContextLayerResult results} in the
 * blueprint that carry non-blank content, joining them with double newlines.
 * The result is a trimmed, ready-to-send system prompt.
 *
 * <p>
 * If no layers contribute content, a minimal fallback prompt is used to ensure
 * the LLM always receives a system instruction.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * ContextBlueprint blueprint = ...;
 * String systemPrompt = promptComposer.compose(blueprint);
 * // → "# Identity\n...\n\n# Memory\n...\n\n# Tools\n..."
 * </pre>
 */
@Slf4j
public class PromptComposer {

    private static final String FALLBACK_PROMPT = "You are a helpful AI assistant.";
    private static final String SECTION_SEPARATOR = "\n\n";

    /**
     * Composes the final system prompt from the given blueprint.
     *
     * <p>
     * Iterates over all results with content in blueprint order, joins them with
     * double newlines, and trims the result. If no layers contributed content,
     * returns a minimal fallback prompt.
     *
     * @param blueprint
     *            the assembled context blueprint
     * @return the composed system prompt, never {@code null} or blank
     */
    public String compose(ContextBlueprint blueprint) {
        return compose(blueprint, Integer.MAX_VALUE);
    }

    /**
     * Composes the final system prompt while enforcing a global token budget.
     *
     * <p>
     * Required layers are always included. Optional layers are selected by priority
     * and per-layer budget first, then rendered back in blueprint order so prompt
     * structure remains stable.
     *
     * @param blueprint
     *            the assembled context blueprint
     * @param maxPromptTokens
     *            global system-prompt token cap; non-positive values mean unlimited
     * @return the composed system prompt, never {@code null} or blank
     */
    public String compose(ContextBlueprint blueprint, int maxPromptTokens) {
        if (blueprint == null) {
            return FALLBACK_PROMPT;
        }

        List<ContextLayerResult> contentResults = blueprint.getContentResults();
        if (contentResults.isEmpty()) {
            log.warn("[PromptComposer] No layers contributed content, using fallback prompt");
            return FALLBACK_PROMPT;
        }

        List<ContextLayerResult> selectedResults = selectResults(contentResults, maxPromptTokens);
        StringBuilder sb = new StringBuilder();
        for (ContextLayerResult result : selectedResults) {
            if (sb.length() > 0) {
                sb.append(SECTION_SEPARATOR);
            }
            sb.append(result.getContent());
        }

        String prompt = sb.toString().trim();
        log.debug("[PromptComposer] Composed prompt from {} of {} layers, {} chars",
                selectedResults.size(), contentResults.size(), prompt.length());
        return prompt;
    }

    private List<ContextLayerResult> selectResults(List<ContextLayerResult> contentResults, int maxPromptTokens) {
        if (maxPromptTokens <= 0 || maxPromptTokens == Integer.MAX_VALUE) {
            return contentResults;
        }

        Set<ContextLayerResult> selected = Collections.newSetFromMap(new IdentityHashMap<>());
        int usedTokens = 0;
        for (ContextLayerResult result : contentResults) {
            if (result.isRequired()) {
                selected.add(result);
                usedTokens += positiveTokens(result);
            }
        }

        int remainingTokens = maxPromptTokens - usedTokens;
        List<ContextLayerResult> optionalResults = new ArrayList<>();
        for (ContextLayerResult result : contentResults) {
            if (!result.isRequired()) {
                optionalResults.add(result);
            }
        }
        optionalResults.sort(Comparator
                .comparingInt(ContextLayerResult::getPriority)
                .reversed());

        for (ContextLayerResult result : optionalResults) {
            int tokens = positiveTokens(result);
            if (tokens > normalizeLayerBudget(result.getTokenBudget())) {
                log.debug("[PromptComposer] Dropping layer '{}' because ~{} tokens exceed its {} token layer budget",
                        result.getLayerName(), tokens, result.getTokenBudget());
                continue;
            }
            if (tokens <= remainingTokens) {
                selected.add(result);
                remainingTokens -= tokens;
            } else {
                log.debug(
                        "[PromptComposer] Dropping layer '{}' because ~{} tokens do not fit remaining {} token budget",
                        result.getLayerName(), tokens, Math.max(0, remainingTokens));
            }
        }

        if (usedTokens > maxPromptTokens) {
            log.warn("[PromptComposer] Required context layers exceed system prompt budget: used={} budget={}",
                    usedTokens, maxPromptTokens);
        }

        List<ContextLayerResult> orderedSelected = new ArrayList<>();
        for (ContextLayerResult result : contentResults) {
            if (selected.contains(result)) {
                orderedSelected.add(result);
            }
        }
        if (orderedSelected.isEmpty()) {
            ContextLayerResult fallback = optionalResults.isEmpty() ? contentResults.get(0) : optionalResults.get(0);
            log.warn("[PromptComposer] System prompt budget selected no layers; keeping highest-priority layer '{}'",
                    fallback.getLayerName());
            return List.of(fallback);
        }
        return orderedSelected;
    }

    private int positiveTokens(ContextLayerResult result) {
        return Math.max(0, result.getEstimatedTokens());
    }

    private int normalizeLayerBudget(int tokenBudget) {
        if (tokenBudget <= 0) {
            return ContextLayer.UNLIMITED_TOKEN_BUDGET;
        }
        return tokenBudget;
    }
}
