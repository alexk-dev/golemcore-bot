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

import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-based classifier for selecting the best skill from semantic search
 * candidates.
 *
 * <p>
 * This component uses a fast LLM (typically gpt-4o-mini or claude-haiku) to:
 * <ul>
 * <li>Analyze user intent and conversation context</li>
 * <li>Select the most appropriate skill from candidates</li>
 * <li>Determine the recommended model tier (fast/balanced/smart/coding)</li>
 * <li>Provide reasoning for the selection</li>
 * </ul>
 *
 * <p>
 * The classifier expects structured JSON responses and includes robust parsing
 * with fallback extraction from markdown code blocks. Configurable timeout
 * prevents slow classifications from blocking requests.
 *
 * <p>
 * Classification considers both the current user message and recent
 * conversation history for context-aware routing.
 *
 * @since 1.0
 * @see HybridSkillMatcher
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmSkillClassifier {

    private final BotProperties properties;
    private final ObjectMapper objectMapper;

    private static final Pattern JSON_PATTERN = Pattern.compile("```json\\s*(\\{.*?})\\s*```", Pattern.DOTALL);
    private static final Pattern SIMPLE_JSON_PATTERN = Pattern.compile("(\\{[^{}]*\"skill\"[^{}]*})", Pattern.DOTALL);

    private static final String SYSTEM_PROMPT = """
            You are a skill routing assistant. Given a user request and a list of candidate skills, select the most appropriate skill.

            ## Instructions:
            1. Analyze the user's intent
            2. Select the BEST matching skill from candidates (or "none" if no skill fits)
            3. Determine the appropriate model tier:
               - "fast": Simple tasks, greetings, quick answers, translations
               - "balanced": Standard tasks, summarization, general questions
               - "coding": Programming tasks: code generation, debugging, refactoring, code review, writing tests
               - "smart": Complex reasoning, architecture decisions, security analysis, multi-step planning
            4. Respond ONLY with valid JSON (no markdown, no explanation):

            {"skill": "skill_name", "confidence": 0.95, "model_tier": "coding", "reason": "Brief explanation"}
            """;

    /**
     * Classify the user request to select the best skill.
     *
     * @param userMessage
     *            the user's message
     * @param conversationHistory
     *            recent conversation for context
     * @param candidates
     *            skill candidates from semantic search
     * @param llmPort
     *            the LLM port to use for classification
     * @return classification result
     */
    public CompletableFuture<ClassificationResult> classify(
            String userMessage,
            List<Message> conversationHistory,
            List<SkillCandidate> candidates,
            LlmPort llmPort) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                String prompt = buildPrompt(userMessage, conversationHistory, candidates);

                BotProperties.ClassifierProperties config = properties.getRouter()
                        .getSkillMatcher()
                        .getClassifier();

                log.debug("[Classifier] Building request with model: {}", config.getModel());
                log.debug("[Classifier] Prompt:\n{}", prompt);

                LlmRequest request = LlmRequest.builder()
                        .model(config.getModel())
                        .systemPrompt(SYSTEM_PROMPT)
                        .messages(List.of(Message.builder()
                                .role("user")
                                .content(prompt)
                                .build()))
                        .build();

                log.info("[Classifier] Sending request to LLM (timeout: {}ms)...", config.getTimeoutMs());
                long startMs = System.currentTimeMillis();
                LlmResponse response = llmPort.chat(request)
                        .get(config.getTimeoutMs(), TimeUnit.MILLISECONDS);

                log.info("[Classifier] LLM responded in {}ms", System.currentTimeMillis() - startMs);
                log.debug("[Classifier] Raw response: {}", response.getContent());

                ClassificationResult result = parseResponse(response.getContent(), candidates);
                log.info("[Classifier] Parsed result: skill={}, confidence={}, tier={}",
                        result.skill(), String.format("%.2f", result.confidence()), result.modelTier());
                return result;

            } catch (Exception e) {
                log.warn("[Classifier] LLM classification FAILED: {}", e.getMessage());
                // Fallback to top semantic candidate
                if (!candidates.isEmpty()) {
                    SkillCandidate top = candidates.get(0);
                    log.info("[Classifier] Fallback to semantic top: {}", top.getName());
                    return new ClassificationResult(
                            top.getName(),
                            top.getSemanticScore(),
                            "balanced",
                            "Fallback to semantic match (LLM failed)");
                }
                return new ClassificationResult(null, 0, "fast", "No match found");
            }
        });
    }

    private String buildPrompt(
            String userMessage,
            List<Message> conversationHistory,
            List<SkillCandidate> candidates) {

        StringBuilder sb = new StringBuilder();

        // Skills section
        sb.append("## Available Skills (pre-filtered by semantic similarity):\n\n");
        for (int i = 0; i < candidates.size(); i++) {
            SkillCandidate c = candidates.get(i);
            sb.append(String.format("%d. **%s** (score: %.2f)%n   %s%n%n",
                    i + 1, c.getName(), c.getSemanticScore(), c.getDescription()));
        }

        // Conversation context (last 3 messages)
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            sb.append("## Conversation Context:\n");
            int startIdx = Math.max(0, conversationHistory.size() - 3);
            for (int i = startIdx; i < conversationHistory.size(); i++) {
                Message msg = conversationHistory.get(i);
                sb.append(String.format("- %s: %s%n",
                        msg.getRole(),
                        truncate(msg.getContent(), 100)));
            }
            sb.append("\n");
        }

        // User request
        sb.append("## Current User Request:\n");
        sb.append(truncate(userMessage, 500));
        sb.append("\n\nSelect the best skill and respond with JSON only.");

        return sb.toString();
    }

    private ClassificationResult parseResponse(String response, List<SkillCandidate> candidates) {
        try {
            // Try to extract JSON from response
            String json = extractJson(response);

            JsonNode node = objectMapper.readTree(json);

            String parsedSkill = node.has("skill") ? node.get("skill").asText() : null;
            double confidence = node.has("confidence") ? node.get("confidence").asDouble() : 0.8;
            String modelTier = node.has("model_tier") ? node.get("model_tier").asText() : "balanced";
            String reason = node.has("reason") ? node.get("reason").asText() : "LLM classification";

            // Validate skill exists in candidates
            String selectedSkill = null;
            if (parsedSkill != null && !parsedSkill.equalsIgnoreCase("none")) {
                final String skillToFind = parsedSkill;
                boolean found = candidates.stream()
                        .anyMatch(c -> c.getName().equalsIgnoreCase(skillToFind));
                if (found) {
                    selectedSkill = parsedSkill;
                } else {
                    log.warn("LLM returned unknown skill: {}", parsedSkill);
                }
            }

            return new ClassificationResult(selectedSkill, confidence, modelTier, reason);

        } catch (Exception e) {
            log.warn("Failed to parse LLM response: {}", e.getMessage());

            // Fallback to top candidate
            if (!candidates.isEmpty()) {
                SkillCandidate top = candidates.get(0);
                return new ClassificationResult(
                        top.getName(),
                        top.getSemanticScore(),
                        "balanced",
                        "Parse failed, using semantic top");
            }

            return new ClassificationResult(null, 0, "fast", "Parse failed, no match");
        }
    }

    private String extractJson(String response) {
        // Try to extract from markdown code block
        Matcher mdMatcher = JSON_PATTERN.matcher(response);
        if (mdMatcher.find()) {
            return mdMatcher.group(1);
        }

        // Try to find raw JSON
        Matcher simpleMatcher = SIMPLE_JSON_PATTERN.matcher(response);
        if (simpleMatcher.find()) {
            return simpleMatcher.group(1);
        }

        // Assume entire response is JSON
        return response.trim();
    }

    private String truncate(String text, int maxLen) {
        if (text == null)
            return "";
        if (text.length() <= maxLen)
            return text;
        return text.substring(0, maxLen) + "...";
    }

    /**
     * Result of LLM classification.
     */
    public record ClassificationResult(
            String skill,
            double confidence,
            String modelTier,
            String reason) {
    }
}
