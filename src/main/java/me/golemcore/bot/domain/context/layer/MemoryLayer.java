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
import me.golemcore.bot.domain.model.MemoryPreset;
import me.golemcore.bot.domain.model.MemoryPresetIds;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.MemoryScopeSupport;
import me.golemcore.bot.domain.service.RuntimeConfigService;

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
@RequiredArgsConstructor
@Slf4j
public class MemoryLayer implements ContextLayer {

    private final MemoryComponent memoryComponent;
    private final RuntimeConfigService runtimeConfigService;
    private final MemoryPresetService memoryPresetService;

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
        return !isMemoryDisabled(context);
    }

    @Override
    public ContextLayerResult assemble(AgentContext context) {
        if (isMemoryDisabled(context)) {
            context.setMemoryContext("");
            return ContextLayerResult.empty(getName());
        }
        RuntimeConfig.MemoryConfig memoryConfig = resolveMemoryPresetConfig(context);

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
                .softPromptBudgetTokens(resolveSoftPromptBudget(memoryConfig))
                .maxPromptBudgetTokens(resolveMaxPromptBudget(memoryConfig))
                .workingTopK(resolveWorkingTopK(memoryConfig))
                .episodicTopK(resolveEpisodicTopK(memoryConfig))
                .semanticTopK(resolveSemanticTopK(memoryConfig))
                .proceduralTopK(resolveProceduralTopK(memoryConfig))
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
                .estimatedTokens(TokenEstimator.estimate(content))
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

    private boolean isMemoryDisabled(AgentContext context) {
        RuntimeConfig.MemoryConfig memoryConfig = resolveMemoryPresetConfig(context);
        if (memoryConfig != null) {
            return Boolean.FALSE.equals(memoryConfig.getEnabled());
        }
        String memoryPreset = context != null ? context.getAttribute(ContextAttributes.MEMORY_PRESET_ID) : null;
        return memoryPreset != null && MemoryPresetIds.DISABLED.equalsIgnoreCase(memoryPreset.trim());
    }

    private RuntimeConfig.MemoryConfig resolveMemoryPresetConfig(AgentContext context) {
        String memoryPreset = context != null ? context.getAttribute(ContextAttributes.MEMORY_PRESET_ID) : null;
        if (memoryPreset == null || memoryPreset.isBlank()) {
            return null;
        }
        return memoryPresetService.findById(memoryPreset)
                .map(MemoryPreset::getMemory)
                .orElse(null);
    }

    private int resolveSoftPromptBudget(RuntimeConfig.MemoryConfig memoryConfig) {
        if (memoryConfig != null && memoryConfig.getSoftPromptBudgetTokens() != null) {
            return memoryConfig.getSoftPromptBudgetTokens();
        }
        return runtimeConfigService.getMemorySoftPromptBudgetTokens();
    }

    private int resolveMaxPromptBudget(RuntimeConfig.MemoryConfig memoryConfig) {
        if (memoryConfig != null && memoryConfig.getMaxPromptBudgetTokens() != null) {
            return memoryConfig.getMaxPromptBudgetTokens();
        }
        return runtimeConfigService.getMemoryMaxPromptBudgetTokens();
    }

    private int resolveWorkingTopK(RuntimeConfig.MemoryConfig memoryConfig) {
        if (memoryConfig != null && memoryConfig.getWorkingTopK() != null) {
            return memoryConfig.getWorkingTopK();
        }
        return runtimeConfigService.getMemoryWorkingTopK();
    }

    private int resolveEpisodicTopK(RuntimeConfig.MemoryConfig memoryConfig) {
        if (memoryConfig != null && memoryConfig.getEpisodicTopK() != null) {
            return memoryConfig.getEpisodicTopK();
        }
        return runtimeConfigService.getMemoryEpisodicTopK();
    }

    private int resolveSemanticTopK(RuntimeConfig.MemoryConfig memoryConfig) {
        if (memoryConfig != null && memoryConfig.getSemanticTopK() != null) {
            return memoryConfig.getSemanticTopK();
        }
        return runtimeConfigService.getMemorySemanticTopK();
    }

    private int resolveProceduralTopK(RuntimeConfig.MemoryConfig memoryConfig) {
        if (memoryConfig != null && memoryConfig.getProceduralTopK() != null) {
            return memoryConfig.getProceduralTopK();
        }
        return runtimeConfigService.getMemoryProceduralTopK();
    }
}
