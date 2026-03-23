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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.context.ContextLayer;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.MemoryScopeSupport;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Assembles memory context from the structured Memory V2 system.
 *
 * <p>
 * Builds a {@link MemoryQuery} from the current context (user query, active
 * skill, scope chain, token budgets), retrieves a {@link MemoryPack}, and
 * renders it as a "# Memory" section. Also stores diagnostics in
 * {@link ContextAttributes} and sets {@code memoryContext} on the
 * {@link AgentContext}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemoryLayer implements ContextLayer {

    private final MemoryComponent memoryComponent;
    private final RuntimeConfigService runtimeConfigService;

    @Override
    public String getName() {
        return "memory";
    }

    @Override
    public int getOrder() {
        return 30;
    }

    @Override
    public boolean appliesTo(AgentContext context) {
        return true;
    }

    @Override
    public ContextLayerResult assemble(AgentContext context) {
        String userQuery = getLastUserMessageText(context);
        String sessionScope = MemoryScopeSupport.resolveScopeFromSessionOrGlobal(
                context.getSession());

        String autoRunKind = context.getAttribute(ContextAttributes.AUTO_RUN_KIND);
        String autoGoalId = context.getAttribute(ContextAttributes.AUTO_GOAL_ID);
        String autoTaskId = context.getAttribute(ContextAttributes.AUTO_TASK_ID);
        List<String> scopeChain = MemoryScopeSupport.resolveScopeChain(
                context.getSession(), autoRunKind, autoGoalId, autoTaskId);

        MemoryQuery query = MemoryQuery.builder()
                .queryText(userQuery)
                .activeSkill(context.getActiveSkill() != null
                        ? context.getActiveSkill().getName()
                        : null)
                .scope(sessionScope)
                .scopeChain(scopeChain)
                .softPromptBudgetTokens(runtimeConfigService.getMemorySoftPromptBudgetTokens())
                .maxPromptBudgetTokens(runtimeConfigService.getMemoryMaxPromptBudgetTokens())
                .workingTopK(runtimeConfigService.getMemoryWorkingTopK())
                .episodicTopK(runtimeConfigService.getMemoryEpisodicTopK())
                .semanticTopK(runtimeConfigService.getMemorySemanticTopK())
                .proceduralTopK(runtimeConfigService.getMemoryProceduralTopK())
                .build();

        String memoryContext = "";
        try {
            MemoryPack pack = memoryComponent.buildMemoryPack(query);
            if (pack != null && pack.getRenderedContext() != null
                    && !pack.getRenderedContext().isBlank()) {
                memoryContext = pack.getRenderedContext();
            }
            if (pack != null && pack.getDiagnostics() != null && !pack.getDiagnostics().isEmpty()) {
                context.setAttribute(ContextAttributes.MEMORY_PACK_DIAGNOSTICS, pack.getDiagnostics());
            }
        } catch (Exception e) { // NOSONAR — best-effort memory retrieval
            log.debug("[MemoryLayer] Memory pack build failed: {}", e.getMessage());
        }

        context.setMemoryContext(memoryContext);

        if (memoryContext.isBlank()) {
            return ContextLayerResult.empty(getName());
        }

        String content = "# Memory\n" + memoryContext;
        return ContextLayerResult.builder()
                .layerName(getName())
                .content(content)
                .estimatedTokens((int) Math.ceil(content.length() / 3.5))
                .build();
    }

    private String getLastUserMessageText(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return null;
        }
        for (int i = context.getMessages().size() - 1; i >= 0; i--) {
            Message msg = context.getMessages().get(i);
            if (msg.isUserMessage() && !msg.isInternalMessage()) {
                return msg.getContent();
            }
        }
        return null;
    }
}
