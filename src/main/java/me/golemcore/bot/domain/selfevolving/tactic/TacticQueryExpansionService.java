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

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Expands incoming task text into multiple tactic-search query views.
 */
@Service
public class TacticQueryExpansionService {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "the", "to", "for", "from", "with", "on", "of", "in", "at", "by",
            "is", "are", "be", "this", "that", "it");
    private static final Set<String> TOOL_TERMS = Set.of(
            "shell", "bash", "zsh", "git", "python", "maven", "npm", "docker", "kubectl", "curl");
    private static final Set<String> FAILURE_TERMS = Set.of(
            "fail", "failed", "failure", "recover", "recovery", "retry", "fix", "error", "broken", "rollback");

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
        LinkedHashSet<String> failureTerms = new LinkedHashSet<>();
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

        LinkedHashSet<String> queryViews = new LinkedHashSet<>(domainTerms);
        queryViews.addAll(toolTerms);
        queryViews.addAll(failureTerms);
        if (queryViews.isEmpty() && !normalized.isBlank()) {
            queryViews.add(normalized);
        }

        return TacticSearchQuery.builder()
                .rawQuery(normalized)
                .queryViews(new ArrayList<>(queryViews))
                .viewQueries(viewQueries)
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
