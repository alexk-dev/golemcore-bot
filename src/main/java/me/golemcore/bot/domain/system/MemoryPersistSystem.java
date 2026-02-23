package me.golemcore.bot.domain.system;

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

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.model.TurnMemoryEvent;
import me.golemcore.bot.domain.service.MemoryScopeSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * System for persisting structured turn-level memory events (order=50).
 * Captures user/assistant exchange and selected tool outputs for Memory V2.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemoryPersistSystem implements AgentSystem {

    private final MemoryComponent memoryComponent;

    @Override
    public String getName() {
        return "MemoryPersistSystem";
    }

    @Override
    public int getOrder() {
        return 50; // After tool execution
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        // Prefer TurnOutcome; fall back to legacy finalAnswerReady
        TurnOutcome outcome = context.getTurnOutcome();
        if (outcome != null) {
            return outcome.getAssistantText() != null && !outcome.getAssistantText().isBlank();
        }
        return Boolean.TRUE.equals(context.getAttribute(ContextAttributes.FINAL_ANSWER_READY));
    }

    @Override
    public AgentContext process(AgentContext context) {
        // Get the last user message
        Message lastUserMessage = getLastUserMessage(context);
        if (lastUserMessage == null) {
            return context;
        }

        // Prefer TurnOutcome.assistantText; fall back to legacy LLM_RESPONSE
        TurnOutcome outcome = context.getTurnOutcome();
        String assistantContent;
        if (outcome != null && outcome.getAssistantText() != null) {
            assistantContent = outcome.getAssistantText();
        } else {
            LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
            if (response == null || response.getContent() == null) {
                return context;
            }
            assistantContent = response.getContent();
        }

        // Persist structured memory event (Memory V2)
        try {
            List<String> scopes = resolvePersistScopes(context);
            List<String> toolOutputs = extractToolOutputs(context);
            for (String scope : scopes) {
                TurnMemoryEvent event = TurnMemoryEvent.builder()
                        .timestamp(Instant.now())
                        .userText(lastUserMessage.getContent())
                        .assistantText(assistantContent)
                        .activeSkill(context.getActiveSkill() != null ? context.getActiveSkill().getName() : null)
                        .scope(scope)
                        .toolOutputs(toolOutputs)
                        .build();
                memoryComponent.persistTurnMemory(event);
            }
        } catch (Exception e) {
            log.warn("Failed to persist structured memory event", e);
        }

        return context;
    }

    private Message getLastUserMessage(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return null;
        }

        for (int i = context.getMessages().size() - 1; i >= 0; i--) {
            Message msg = context.getMessages().get(i);
            if (msg.isUserMessage()) {
                return msg;
            }
        }
        return null;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        text = text.replace("\n", " ").trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    private List<String> extractToolOutputs(AgentContext context) {
        List<String> outputs = new ArrayList<>();
        Map<String, ToolResult> toolResults = context.getToolResults();
        if (toolResults == null || toolResults.isEmpty()) {
            return outputs;
        }

        for (ToolResult result : toolResults.values()) {
            if (result == null) {
                continue;
            }
            String output = result.getOutput();
            if (output != null && !output.isBlank()) {
                outputs.add(truncate(output, 800));
                continue;
            }
            String error = result.getError();
            if (error != null && !error.isBlank()) {
                outputs.add("Error: " + truncate(error, 800));
            }
        }
        return outputs;
    }

    private List<String> resolvePersistScopes(AgentContext context) {
        String runKind = context.getAttribute(ContextAttributes.AUTO_RUN_KIND);
        String goalId = context.getAttribute(ContextAttributes.AUTO_GOAL_ID);
        String taskId = context.getAttribute(ContextAttributes.AUTO_TASK_ID);

        String sessionScope = MemoryScopeSupport.resolveScopeFromSessionOrGlobal(context.getSession());
        if (runKind == null || runKind.isBlank()) {
            return List.of(sessionScope);
        }

        Set<String> scopes = new LinkedHashSet<>();
        String taskScope = MemoryScopeSupport.buildTaskScopeOrGlobal(taskId);
        if (!MemoryScopeSupport.GLOBAL_SCOPE.equals(taskScope)) {
            scopes.add(taskScope);
        }

        if ("GOAL_RUN".equalsIgnoreCase(runKind)) {
            String channelType = context.getAttribute(ContextAttributes.SESSION_IDENTITY_CHANNEL);
            String conversationKey = context.getAttribute(ContextAttributes.SESSION_IDENTITY_CONVERSATION);
            String goalScope = MemoryScopeSupport.buildGoalScopeOrGlobal(channelType, conversationKey, goalId);
            if (!MemoryScopeSupport.GLOBAL_SCOPE.equals(goalScope)) {
                scopes.add(goalScope);
            }
        }

        if (scopes.isEmpty()) {
            scopes.add(sessionScope);
        }
        return new ArrayList<>(scopes);
    }
}
