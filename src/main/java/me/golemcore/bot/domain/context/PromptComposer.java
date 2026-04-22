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
import me.golemcore.bot.domain.context.layer.TokenEstimator;

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
    private static final String TRUNCATION_NOTICE_PREFIX = "\n\n[Layer truncated by system prompt budget: ";
    private static final String TRUNCATION_NOTICE_SUFFIX = "]";

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
            return fallbackWithinBudget(maxPromptTokens);
        }

        List<ContextLayerResult> contentResults = blueprint.getContentResults();
        if (contentResults.isEmpty()) {
            log.warn("[PromptComposer] No layers contributed content, using fallback prompt");
            return fallbackWithinBudget(maxPromptTokens);
        }

        List<ContextLayerResult> selectedResults = selectResults(contentResults, maxPromptTokens);
        String prompt = renderWithinBudget(selectedResults, maxPromptTokens);
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
            if (mustSelect(result)) {
                selected.add(result);
                usedTokens += positiveTokens(result);
            }
        }

        int remainingTokens = maxPromptTokens - usedTokens;
        List<ContextLayerResult> optionalResults = new ArrayList<>();
        for (ContextLayerResult result : contentResults) {
            if (!mustSelect(result)) {
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

    private String renderWithinBudget(List<ContextLayerResult> selectedResults, int maxPromptTokens) {
        if (maxPromptTokens <= 0 || maxPromptTokens == Integer.MAX_VALUE) {
            return render(selectedResults);
        }

        StringBuilder sb = new StringBuilder();
        for (ContextLayerResult result : selectedResults) {
            String content = result.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }

            String separator = sb.length() > 0 ? SECTION_SEPARATOR : "";
            int separatorTokens = TokenEstimator.estimate(separator);
            int usedTokens = TokenEstimator.estimate(sb.toString());
            int remainingTokens = maxPromptTokens - usedTokens - separatorTokens;
            if (remainingTokens <= 0) {
                if (isUntrimmable(result)) {
                    throw promptBudgetExceeded(result, maxPromptTokens, 0);
                }
                log.debug("[PromptComposer] Dropping layer '{}' because the hard prompt budget is exhausted",
                        result.getLayerName());
                continue;
            }

            if (TokenEstimator.estimate(content) <= remainingTokens) {
                sb.append(separator).append(content);
                continue;
            }

            if (isUntrimmable(result)) {
                throw promptBudgetExceeded(result, maxPromptTokens, remainingTokens);
            }
            String trimmed = trimToTokenBudget(content, remainingTokens, result.getLayerName());
            if (trimmed.isBlank()) {
                log.debug("[PromptComposer] Dropping layer '{}' because it cannot fit the remaining hard budget",
                        result.getLayerName());
                continue;
            }
            sb.append(separator).append(trimmed);
            log.debug("[PromptComposer] Truncated layer '{}' to fit remaining {} token hard budget",
                    result.getLayerName(), remainingTokens);
        }

        String prompt = sb.toString().trim();
        if (prompt.isBlank()) {
            return fallbackWithinBudget(maxPromptTokens);
        }
        if (TokenEstimator.estimate(prompt) > maxPromptTokens) {
            if (selectedResults.stream().anyMatch(this::isUntrimmable)) {
                throw new IllegalStateException("Pinned untrimmable prompt layers exceed system prompt budget "
                        + maxPromptTokens);
            }
            return trimToTokenBudget(prompt, maxPromptTokens, "prompt");
        }
        return prompt;
    }

    private String render(List<ContextLayerResult> selectedResults) {
        StringBuilder sb = new StringBuilder();
        for (ContextLayerResult result : selectedResults) {
            if (sb.length() > 0) {
                sb.append(SECTION_SEPARATOR);
            }
            sb.append(result.getContent());
        }
        return sb.toString().trim();
    }

    private String trimToTokenBudget(String content, int maxTokens, String layerName) {
        if (content == null || content.isBlank() || maxTokens <= 0) {
            return "";
        }
        String notice = TRUNCATION_NOTICE_PREFIX + safeLayerName(layerName) + TRUNCATION_NOTICE_SUFFIX;
        String candidate = content;
        if (TokenEstimator.estimate(candidate) <= maxTokens) {
            return candidate;
        }

        int noticeTokens = TokenEstimator.estimate(notice);
        int maxChars = Math.max(0, (int) Math.floor(maxTokens * 3.5d));
        if (noticeTokens < maxTokens) {
            maxChars = Math.max(0, (int) Math.floor((maxTokens - noticeTokens) * 3.5d));
            candidate = content.substring(0, Math.min(content.length(), maxChars)) + notice;
        } else {
            candidate = content.substring(0, Math.min(content.length(), maxChars));
        }

        while (TokenEstimator.estimate(candidate) > maxTokens && !candidate.isEmpty()) {
            int nextLength = Math.max(0, candidate.length() - Math.max(1, candidate.length() / 10));
            candidate = candidate.substring(0, nextLength);
        }
        return candidate.trim();
    }

    private String safeLayerName(String layerName) {
        return layerName == null || layerName.isBlank() ? "unknown" : layerName;
    }

    private String fallbackWithinBudget(int maxPromptTokens) {
        if (maxPromptTokens <= 0 || maxPromptTokens == Integer.MAX_VALUE
                || TokenEstimator.estimate(FALLBACK_PROMPT) <= maxPromptTokens) {
            return FALLBACK_PROMPT;
        }
        String fallback = trimToTokenBudget(FALLBACK_PROMPT, maxPromptTokens, "fallback");
        return fallback.isBlank() ? "AI" : fallback;
    }

    private int positiveTokens(ContextLayerResult result) {
        return Math.max(0, result.getEstimatedTokens());
    }

    private boolean mustSelect(ContextLayerResult result) {
        return result != null
                && (result.isRequired() || result.getCriticality() != LayerCriticality.OPTIONAL);
    }

    private boolean isUntrimmable(ContextLayerResult result) {
        return result != null && result.getCriticality() == LayerCriticality.PINNED_UNTRIMMABLE;
    }

    private IllegalStateException promptBudgetExceeded(ContextLayerResult result,
            int maxPromptTokens, int remainingTokens) {
        return new IllegalStateException("Pinned untrimmable context layer '" + safeLayerName(result.getLayerName())
                + "' exceeds system prompt budget " + maxPromptTokens
                + " with remaining budget " + Math.max(0, remainingTokens));
    }

    private int normalizeLayerBudget(int tokenBudget) {
        if (tokenBudget <= 0) {
            return ContextLayer.UNLIMITED_TOKEN_BUDGET;
        }
        return tokenBudget;
    }
}
