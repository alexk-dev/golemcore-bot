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

import java.util.List;

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
 * <h3>Example</h3>
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
        if (blueprint == null) {
            return FALLBACK_PROMPT;
        }

        List<ContextLayerResult> contentResults = blueprint.getContentResults();
        if (contentResults.isEmpty()) {
            log.warn("[PromptComposer] No layers contributed content, using fallback prompt");
            return FALLBACK_PROMPT;
        }

        StringBuilder sb = new StringBuilder();
        for (ContextLayerResult result : contentResults) {
            if (sb.length() > 0) {
                sb.append(SECTION_SEPARATOR);
            }
            sb.append(result.getContent());
        }

        String prompt = sb.toString().trim();
        log.debug("[PromptComposer] Composed prompt from {} layers, {} chars",
                contentResults.size(), prompt.length());
        return prompt;
    }
}
