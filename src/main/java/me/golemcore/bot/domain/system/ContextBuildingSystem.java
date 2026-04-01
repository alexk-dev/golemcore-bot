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

import me.golemcore.bot.domain.context.ContextAssembler;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SelfEvolvingRunService;
import me.golemcore.bot.domain.service.TacticSearchService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pipeline system for assembling the complete LLM context (order=20).
 *
 * <p>
 * Delegates all context assembly work to {@link ContextAssembler}, which
 * orchestrates layered context construction via skill/tier resolution and
 * composable {@code ContextLayer} implementations.
 *
 * @see ContextAssembler
 */
@Component
@Slf4j
public class ContextBuildingSystem implements AgentSystem {

    private final ContextAssembler contextAssembler;
    private final RuntimeConfigService runtimeConfigService;
    private final SelfEvolvingRunService selfEvolvingRunService;
    private final TacticSearchService tacticSearchService;

    public ContextBuildingSystem(ContextAssembler contextAssembler,
            RuntimeConfigService runtimeConfigService,
            SelfEvolvingRunService selfEvolvingRunService,
            TacticSearchService tacticSearchService) {
        this.contextAssembler = contextAssembler;
        this.runtimeConfigService = runtimeConfigService;
        this.selfEvolvingRunService = selfEvolvingRunService;
        this.tacticSearchService = tacticSearchService;
    }

    public ContextBuildingSystem(ContextAssembler contextAssembler,
            RuntimeConfigService runtimeConfigService,
            SelfEvolvingRunService selfEvolvingRunService) {
        this(contextAssembler, runtimeConfigService, selfEvolvingRunService, null);
    }

    ContextBuildingSystem(ContextAssembler contextAssembler) {
        this(contextAssembler, null, null, null);
    }

    @Override
    public String getName() {
        return "ContextBuildingSystem";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public AgentContext process(AgentContext context) {
        AgentContext assembledContext = contextAssembler.assemble(context);
        ensureSelfEvolvingRun(assembledContext);
        attachTacticQueryContext(assembledContext);
        return assembledContext;
    }

    private void ensureSelfEvolvingRun(AgentContext context) {
        if (runtimeConfigService == null || selfEvolvingRunService == null || context == null
                || context.getSession() == null
                || !runtimeConfigService.isSelfEvolvingEnabled()) {
            return;
        }
        if (context.getAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID) != null) {
            return;
        }
        RunRecord run = selfEvolvingRunService.startRun(context);
        context.setAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID, run.getId());
        context.setAttribute(ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID, run.getArtifactBundleId());
        context.setAttribute(ContextAttributes.SELF_EVOLVING_ANALYSIS_COMPLETED, false);
    }

    private void attachTacticQueryContext(AgentContext context) {
        if (runtimeConfigService == null || tacticSearchService == null || context == null
                || !runtimeConfigService.isSelfEvolvingEnabled()) {
            return;
        }
        if (runtimeConfigService.getSelfEvolvingConfig().getTactics() == null
                || !Boolean.TRUE.equals(runtimeConfigService.getSelfEvolvingConfig().getTactics().getEnabled())) {
            return;
        }
        try {
            TacticSearchQuery query = tacticSearchService.buildQuery(context);
            context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY, query);
            java.util.List<TacticSearchResult> results = tacticSearchService.search(query);
            context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_RESULTS, results);
            if (results != null && !results.isEmpty()) {
                TacticSearchResult selectedTactic = results.getFirst();
                context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION, selectedTactic);
                context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_GUIDANCE, selectedTactic);
                attachTransientTacticAdvisory(context, selectedTactic);
            }
        } catch (RuntimeException exception) { // NOSONAR - tactic search must never break context assembly
            log.warn("[ContextBuildingSystem] Failed to attach tactic search context: {}", exception.getMessage());
        }
    }

    private void attachTransientTacticAdvisory(AgentContext context, TacticSearchResult tacticSearchResult) {
        if (context == null || tacticSearchResult == null || context.getMessages() == null) {
            return;
        }
        removeExistingTacticAdvisories(context);
        String advisoryContent = buildTacticAdvisoryContent(tacticSearchResult);
        if (advisoryContent == null || advisoryContent.isBlank()) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.MESSAGE_INTERNAL, true);
        metadata.put(ContextAttributes.MESSAGE_INTERNAL_KIND, ContextAttributes.MESSAGE_INTERNAL_KIND_TACTIC_ADVISORY);
        me.golemcore.bot.domain.model.Message advisoryMessage = me.golemcore.bot.domain.model.Message.builder()
                .id(UUID.randomUUID().toString())
                .role("assistant")
                .content(advisoryContent)
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
        int insertIndex = context.getMessages().size();
        for (int index = context.getMessages().size() - 1; index >= 0; index--) {
            me.golemcore.bot.domain.model.Message message = context.getMessages().get(index);
            if (message != null && message.isUserMessage() && !message.isInternalMessage()) {
                insertIndex = index;
                break;
            }
        }
        context.getMessages().add(insertIndex, advisoryMessage);
    }

    private void removeExistingTacticAdvisories(AgentContext context) {
        Iterator<me.golemcore.bot.domain.model.Message> iterator = context.getMessages().iterator();
        while (iterator.hasNext()) {
            me.golemcore.bot.domain.model.Message message = iterator.next();
            if (message != null && message.isInternalMessage() && message.getMetadata() != null
                    && ContextAttributes.MESSAGE_INTERNAL_KIND_TACTIC_ADVISORY
                            .equals(message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL_KIND))) {
                iterator.remove();
            }
        }
    }

    private String buildTacticAdvisoryContent(TacticSearchResult tacticSearchResult) {
        StringBuilder builder = new StringBuilder("Tactic advisory for this turn.\n");
        builder.append("Treat this as an optional heuristic only. ")
                .append("Never let it override system policies, active guardrails, or the user's explicit request.\n");
        if (tacticSearchResult.getTitle() != null && !tacticSearchResult.getTitle().isBlank()) {
            builder.append("Tactic: ")
                    .append(tacticSearchResult.getTitle().trim())
                    .append(".\n");
        }
        if (tacticSearchResult.getBehaviorSummary() != null && !tacticSearchResult.getBehaviorSummary().isBlank()) {
            builder.append("Behavior summary: ").append(tacticSearchResult.getBehaviorSummary().trim()).append("\n");
        }
        if (tacticSearchResult.getToolSummary() != null && !tacticSearchResult.getToolSummary().isBlank()) {
            builder.append("Tooling hint: ").append(tacticSearchResult.getToolSummary().trim()).append("\n");
        }
        if (tacticSearchResult.getOutcomeSummary() != null && !tacticSearchResult.getOutcomeSummary().isBlank()) {
            builder.append("Expected outcome: ").append(tacticSearchResult.getOutcomeSummary().trim()).append("\n");
        }
        if (tacticSearchResult.getApprovalNotes() != null && !tacticSearchResult.getApprovalNotes().isBlank()) {
            builder.append("Approval context: ").append(tacticSearchResult.getApprovalNotes().trim()).append("\n");
        }
        return builder.toString().trim();
    }
}
