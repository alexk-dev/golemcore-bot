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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.port.outbound.ModelSelectionQueryPort;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.port.outbound.LlmPort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Expands incoming task text into multiple tactic-search query views.
 * Optionally rewrites the query via LLM for richer semantic coverage.
 */
@Service
@Slf4j
public class TacticQueryExpansionService {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "the", "to", "for", "from", "with", "on", "of", "in", "at", "by",
            "is", "are", "be", "this", "that", "it");
    private static final Set<String> TOOL_TERMS = Set.of(
            "shell", "bash", "zsh", "git", "python", "maven", "npm", "docker", "kubectl", "curl");
    private static final Set<String> FAILURE_TERMS = Set.of(
            "fail", "failed", "failure", "recover", "recovery", "retry", "fix", "error", "broken", "rollback");

    private static final Set<String> PLANNING_TERMS = Set.of(
            "plan", "design", "architect", "propose", "strategy", "outline", "approach", "consider");
    private static final Set<String> RECOVERY_TERMS = Set.of(
            "fail", "failed", "error", "broken", "recover", "recovery", "rollback", "revert", "fix", "debug");
    private static final Set<String> OPTIMIZATION_TERMS = Set.of(
            "optimize", "improve", "performance", "faster", "efficient", "refactor", "cleanup", "reduce");

    private static final int LLM_QUERY_CACHE_MAX_SIZE = 256;
    private static final int LLM_RAW_QUERY_PREFIX_LENGTH = 120;

    private static final String LLM_QUERY_EXPANSION_SYSTEM_PROMPT = """
            You are a search query rewriter for an operational tactics knowledge base.
            Given the user message, generate 2-3 concise search queries that would find relevant tactics.
            Each query should focus on a different aspect: problem domain, tooling, or failure recovery.
            Return a JSON array of strings. No explanation, no markdown — only the JSON array.""";

    private final SelfEvolvingRuntimeConfigPort runtimeConfigPort;
    private final ModelSelectionQueryPort modelSelectionQueryPort;
    private final LlmPort llmPort;
    private final ObjectMapper objectMapper;
    private final Map<String, List<String>> llmQueryCache = new ConcurrentHashMap<>();

    public TacticQueryExpansionService(
            SelfEvolvingRuntimeConfigPort runtimeConfigPort,
            ModelSelectionQueryPort modelSelectionQueryPort,
            LlmPort llmPort,
            ObjectMapper objectMapper) {
        this.runtimeConfigPort = runtimeConfigPort;
        this.modelSelectionQueryPort = modelSelectionQueryPort;
        this.llmPort = llmPort;
        this.objectMapper = objectMapper;
    }

    public TacticSearchQuery expand(String rawQuery) {
        String normalized = normalize(rawQuery);
        List<String> tokens = tokens(normalized);
        List<String> domainTerms = tokens.stream()
                .filter(token -> token.length() > 2)
                .filter(token -> !STOP_WORDS.contains(token))
                .toList();
        List<String> toolTerms = tokens.stream()
                .filter(TOOL_TERMS::contains)
                .toList();
        Set<String> failureTerms = new LinkedHashSet<>();
        tokens.stream().filter(FAILURE_TERMS::contains).forEach(failureTerms::add);
        if (!failureTerms.isEmpty()) {
            failureTerms.add("failure");
        }

        Map<String, String> viewQueries = new LinkedHashMap<>();
        viewQueries.put("intent", normalized);
        if (!domainTerms.isEmpty()) {
            viewQueries.put("domain", String.join(" ", domainTerms));
        }
        if (!toolTerms.isEmpty()) {
            viewQueries.put("tool", String.join(" ", toolTerms));
        }
        if (!failureTerms.isEmpty()) {
            viewQueries.put("failure-recovery", String.join(" ", failureTerms));
        }

        String executionPhase = detectPhase(tokens);
        if (executionPhase != null) {
            viewQueries.put("phase", executionPhase + " " + normalized);
        }

        List<String> llmExpansions = expandViaLlm(normalized);
        for (int i = 0; i < llmExpansions.size(); i++) {
            viewQueries.put("llm-expansion-" + i, llmExpansions.get(i));
        }

        Set<String> queryViews = new LinkedHashSet<>(domainTerms);
        queryViews.addAll(toolTerms);
        queryViews.addAll(failureTerms);
        if (executionPhase != null) {
            queryViews.add(executionPhase);
        }
        for (String expansion : llmExpansions) {
            queryViews.addAll(tokens(expansion));
        }
        if (queryViews.isEmpty() && !normalized.isBlank()) {
            queryViews.add(normalized);
        }

        return TacticSearchQuery.builder()
                .rawQuery(normalized)
                .queryViews(new ArrayList<>(queryViews))
                .viewQueries(viewQueries)
                .executionPhase(executionPhase)
                .build();
    }

    public TacticSearchQuery expand(AgentContext context) {
        String rawQuery = lastUserMessage(context);
        TacticSearchQuery query = expand(rawQuery);
        if (context == null) {
            return query;
        }
        if (context.getAvailableTools() != null && !context.getAvailableTools().isEmpty()) {
            query.setAvailableTools(context.getAvailableTools().stream()
                    .map(ToolDefinition::getName)
                    .filter(name -> !StringValueSupport.isBlank(name))
                    .map(String::trim)
                    .toList());
        }
        query.setGolemId(context.getAttribute(ContextAttributes.HIVE_GOLEM_ID));
        return query;
    }

    private String detectPhase(List<String> tokens) {
        if (tokens.isEmpty()) {
            return null;
        }
        long recoveryCount = tokens.stream().filter(RECOVERY_TERMS::contains).count();
        long planningCount = tokens.stream().filter(PLANNING_TERMS::contains).count();
        long optimizationCount = tokens.stream().filter(OPTIMIZATION_TERMS::contains).count();
        if (recoveryCount > planningCount && recoveryCount > optimizationCount) {
            return "recovery";
        }
        if (planningCount > recoveryCount && planningCount > optimizationCount) {
            return "planning";
        }
        if (optimizationCount > recoveryCount && optimizationCount > planningCount) {
            return "optimization";
        }
        return "execution";
    }

    private List<String> expandViaLlm(String normalized) {
        if (StringValueSupport.isBlank(normalized) || !isLlmQueryExpansionEnabled()) {
            return List.of();
        }
        String cacheKey = normalized.length() > LLM_RAW_QUERY_PREFIX_LENGTH
                ? normalized.substring(0, LLM_RAW_QUERY_PREFIX_LENGTH)
                : normalized;
        List<String> cached = llmQueryCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            String tier = runtimeConfigPort.getTacticQueryExpansionTier();
            ModelSelectionQueryPort.ModelSelection selection = modelSelectionQueryPort.resolveExplicitSelection(tier);
            LlmRequest request = LlmRequest.builder()
                    .model(selection.model())
                    .reasoningEffort(selection.reasoning())
                    .systemPrompt(LLM_QUERY_EXPANSION_SYSTEM_PROMPT)
                    .messages(List.of(Message.builder()
                            .role("user")
                            .content(normalized)
                            .build()))
                    .temperature(0.3d)
                    .build();
            LlmResponse response = llmPort.chat(request).get();
            if (response == null || StringValueSupport.isBlank(response.getContent())) {
                return List.of();
            }
            List<String> expansions = parseExpansions(response.getContent());
            if (llmQueryCache.size() >= LLM_QUERY_CACHE_MAX_SIZE) {
                llmQueryCache.clear();
            }
            llmQueryCache.put(cacheKey, expansions);
            return expansions;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.debug("[TacticQueryExpansion] LLM expansion interrupted, falling back to keyword expansion");
            return List.of();
        } catch (java.util.concurrent.ExecutionException exception) {
            log.debug("[TacticQueryExpansion] LLM expansion failed, falling back to keyword expansion: {}",
                    exception.getMessage());
            return List.of();
        }
    }

    private List<String> parseExpansions(String content) {
        try {
            String trimmed = content.trim();
            int start = trimmed.indexOf('[');
            int end = trimmed.lastIndexOf(']');
            if (start >= 0 && end > start) {
                trimmed = trimmed.substring(start, end + 1);
            }
            List<String> result = objectMapper.readValue(trimmed, new TypeReference<>() {
            });
            return result.stream()
                    .filter(s -> !StringValueSupport.isBlank(s))
                    .map(s -> s.toLowerCase(Locale.ROOT).trim())
                    .limit(3)
                    .toList();
        } catch (java.io.IOException exception) {
            log.debug("[TacticQueryExpansion] Failed to parse LLM expansion response: {}", exception.getMessage());
            return List.of();
        }
    }

    private boolean isLlmQueryExpansionEnabled() {
        return runtimeConfigPort.isTacticQueryExpansionEnabled();
    }

    private String lastUserMessage(AgentContext context) {
        if (context == null || context.getMessages() == null || context.getMessages().isEmpty()) {
            return "";
        }
        for (int i = context.getMessages().size() - 1; i >= 0; i--) {
            Message message = context.getMessages().get(i);
            if (message != null && message.isUserMessage() && !message.isInternalMessage()
                    && !StringValueSupport.isBlank(message.getContent())) {
                return message.getContent();
            }
        }
        return "";
    }

    private List<String> tokens(String value) {
        if (StringValueSupport.isBlank(value)) {
            return List.of();
        }
        return List.of(value.split("[^a-z0-9:_-]+")).stream()
                .map(token -> token.toLowerCase(Locale.ROOT).trim())
                .filter(token -> !token.isBlank())
                .toList();
    }

    private String normalize(String value) {
        if (StringValueSupport.isBlank(value)) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
