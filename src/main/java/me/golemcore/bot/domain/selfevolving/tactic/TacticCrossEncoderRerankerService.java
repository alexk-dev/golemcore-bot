package me.golemcore.bot.domain.selfevolving.tactic;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.port.outbound.LlmPort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Executes a tier-resolved LLM reranking pass for tactic candidates.
 */
@Service
public class TacticCrossEncoderRerankerService {

    private static final String SYSTEM_PROMPT = """
            You are a tactic reranker.
            Return strict JSON with this schema:
            {
              "results": [
                {
                  "tacticId": "string",
                  "score": 0.0,
                  "verdict": "short explanation"
                }
              ]
            }
            Score is an additive rerank adjustment in [0.0, 0.15].
            Rank only the provided candidates. Do not invent tactics.
            """;

    private final ModelSelectionService modelSelectionService;
    private final LlmPort llmPort;
    private final ObjectMapper objectMapper;

    public TacticCrossEncoderRerankerService(
            ModelSelectionService modelSelectionService,
            LlmPort llmPort,
            ObjectMapper objectMapper) {
        this.modelSelectionService = modelSelectionService;
        this.llmPort = llmPort;
        this.objectMapper = objectMapper;
    }

    public List<RerankedCandidate> rerank(
            TacticSearchQuery query,
            List<TacticSearchResult> candidates,
            String tier,
            Integer timeoutMs) {
        if (query == null || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        try {
            String effectiveTier = StringValueSupport.isBlank(tier) ? "deep" : tier;
            ModelSelectionService.ModelSelection selection = modelSelectionService.resolveExplicitTier(effectiveTier);
            LlmRequest request = LlmRequest.builder()
                    .model(selection.model())
                    .reasoningEffort(selection.reasoning())
                    .systemPrompt(SYSTEM_PROMPT)
                    .messages(List.of(Message.builder()
                            .role("user")
                            .content(buildPrompt(query, candidates, tier))
                            .build()))
                    .temperature(0.1d)
                    .build();
            LlmResponse response = llmPort.chat(request).get(resolveTimeoutMs(timeoutMs), TimeUnit.MILLISECONDS);
            if (response == null || StringValueSupport.isBlank(response.getContent())) {
                throw new IllegalArgumentException("Cross-encoder reranker returned empty response");
            }
            return parse(response.getContent());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cross-encoder reranker interrupted", exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("Cross-encoder reranker timed out", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Cross-encoder reranker failed", exception);
        }
    }

    private long resolveTimeoutMs(Integer timeoutMs) {
        if (timeoutMs == null || timeoutMs <= 0) {
            return 15000L;
        }
        return timeoutMs.longValue();
    }

    private String buildPrompt(TacticSearchQuery query, List<TacticSearchResult> candidates, String tier) {
        StringBuilder builder = new StringBuilder();
        builder.append("Tier: ").append(tier).append("\n");
        builder.append("Query: ").append(firstNonBlank(query.getRawQuery(), "")).append("\n");
        builder.append("Query views: ").append(query.getQueryViews() != null ? query.getQueryViews() : List.of())
                .append("\n");
        builder.append("Candidates:\n");
        for (TacticSearchResult candidate : candidates) {
            builder.append("- tacticId: ").append(candidate.getTacticId()).append("\n");
            builder.append("  title: ").append(firstNonBlank(candidate.getTitle(), "")).append("\n");
            builder.append("  behavior: ").append(firstNonBlank(candidate.getBehaviorSummary(), "")).append("\n");
            builder.append("  tools: ").append(firstNonBlank(candidate.getToolSummary(), "")).append("\n");
            builder.append("  promotionState: ").append(firstNonBlank(candidate.getPromotionState(), "")).append("\n");
        }
        return builder.toString();
    }

    private List<RerankedCandidate> parse(String rawContent) {
        try {
            JsonNode root = objectMapper.readTree(rawContent);
            List<RerankedCandidate> results = new ArrayList<>();
            for (JsonNode node : root.path("results")) {
                String tacticId = node.path("tacticId").asText();
                if (StringValueSupport.isBlank(tacticId)) {
                    continue;
                }
                double score = node.path("score").asDouble(0.0d);
                String verdict = node.path("verdict").asText("");
                results.add(new RerankedCandidate(tacticId, score, verdict));
            }
            return results;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse cross-encoder reranker response", exception);
        }
    }

    private String firstNonBlank(String value, String fallback) {
        if (!StringValueSupport.isBlank(value)) {
            return value;
        }
        return fallback;
    }

    public record RerankedCandidate(String tacticId, double score, String verdict) {
    }
}
