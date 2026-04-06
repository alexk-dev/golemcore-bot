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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.TraceService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Attaches tactic-search query, selection, attribution, and transient advisory
 * state to the current turn context.
 */
@Service
@Slf4j
public class TacticTurnContextService {

    private final RuntimeConfigService runtimeConfigService;
    private final TacticSearchService tacticSearchService;
    private final TraceService traceService;

    public TacticTurnContextService(
            RuntimeConfigService runtimeConfigService,
            TacticSearchService tacticSearchService,
            TraceService traceService) {
        this.runtimeConfigService = runtimeConfigService;
        this.tacticSearchService = tacticSearchService;
        this.traceService = traceService;
    }

    public void attach(AgentContext context) {
        if (context == null) {
            return;
        }
        resetTurnScopedTacticState(context);
        if (runtimeConfigService == null || tacticSearchService == null
                || !runtimeConfigService.isSelfEvolvingEnabled()) {
            return;
        }
        Instant startedAt = Instant.now();
        TraceContext spanContext = startTacticEnrichSpan(context, startedAt);
        try {
            TacticSearchQuery query = tacticSearchService.buildQuery(context);
            context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY, query);
            List<TacticSearchResult> results = tacticSearchService.search(query);
            if (results == null) {
                results = List.of();
            }
            context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_RESULTS, results);
            if (results != null && !results.isEmpty()) {
                TacticSearchResult selectedTactic = results.getFirst();
                context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION, selectedTactic);
                context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_GUIDANCE, selectedTactic);
                recordAppliedTacticIds(context, selectedTactic);
                attachTransientTacticAdvisory(context, selectedTactic);
                recordTacticSpanAttributes(context, spanContext, selectedTactic, results.size());
            } else {
                recordTacticSpanAttributes(context, spanContext, null, 0);
            }
            finishTacticEnrichSpan(context, spanContext, TraceStatusCode.OK, null);
        } catch (RuntimeException exception) { // NOSONAR - tactic search must never break context assembly
            log.warn("[ContextBuildingSystem] Failed to attach tactic search context: {}", exception.getMessage());
            finishTacticEnrichSpan(context, spanContext, TraceStatusCode.ERROR, exception.getMessage());
        }
    }

    private void resetTurnScopedTacticState(AgentContext context) {
        removeExistingTacticAdvisories(context);
        if (context.getAttributes() == null) {
            return;
        }
        context.getAttributes().remove(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY);
        context.getAttributes().remove(ContextAttributes.SELF_EVOLVING_TACTIC_RESULTS);
        context.getAttributes().remove(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION);
        context.getAttributes().remove(ContextAttributes.SELF_EVOLVING_TACTIC_GUIDANCE);
        context.getAttributes().remove(ContextAttributes.APPLIED_TACTIC_IDS);
    }

    private void recordAppliedTacticIds(AgentContext context, TacticSearchResult selectedTactic) {
        if (context == null || selectedTactic == null) {
            return;
        }
        // Only the selected tactic is actually applied downstream (via
        // SELF_EVOLVING_TACTIC_SELECTION / the transient advisory). Other
        // ranked candidates were evaluated but not used, so recording them
        // would pollute attribution telemetry consumed by metrics.
        String selectedId = selectedTactic.getTacticId();
        if (selectedId == null || selectedId.isBlank()) {
            return;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        List<String> existing = context.getAttribute(ContextAttributes.APPLIED_TACTIC_IDS);
        if (existing != null) {
            for (String id : existing) {
                if (id != null && !id.isBlank()) {
                    merged.add(id);
                }
            }
        }
        merged.add(selectedId);
        context.setAttribute(ContextAttributes.APPLIED_TACTIC_IDS, new ArrayList<>(merged));
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
        Message advisoryMessage = Message.builder()
                .id(UUID.randomUUID().toString())
                .role("assistant")
                .content(advisoryContent)
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
        int insertIndex = context.getMessages().size();
        for (int index = context.getMessages().size() - 1; index >= 0; index--) {
            Message message = context.getMessages().get(index);
            if (message != null && message.isUserMessage() && !message.isInternalMessage()) {
                insertIndex = index;
                break;
            }
        }
        context.getMessages().add(insertIndex, advisoryMessage);
    }

    private void removeExistingTacticAdvisories(AgentContext context) {
        if (context == null || context.getMessages() == null) {
            return;
        }
        Iterator<Message> iterator = context.getMessages().iterator();
        while (iterator.hasNext()) {
            Message message = iterator.next();
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
        String intentSummary = sanitizeAdvisoryText(tacticSearchResult.getIntentSummary());
        if (intentSummary != null) {
            builder.append("Intent summary: ").append(intentSummary).append("\n");
        }
        String behaviorSummary = sanitizeAdvisoryText(tacticSearchResult.getBehaviorSummary());
        if (behaviorSummary != null && !behaviorSummary.equals(intentSummary)) {
            builder.append("Behavior summary: ").append(behaviorSummary).append("\n");
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

    private String sanitizeAdvisoryText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.matches("selfevolving:[a-z_]+:[a-z_]+")) {
            return null;
        }
        return trimmed;
    }

    private TraceContext startTacticEnrichSpan(AgentContext context, Instant startedAt) {
        if (traceService == null || context.getSession() == null || context.getTraceContext() == null
                || !runtimeConfigService.isTracingEnabled()) {
            return null;
        }
        try {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("component", "tactic.enrich");
            return traceService.startSpan(context.getSession(), context.getTraceContext(),
                    "tactic.enrich", TraceSpanKind.INTERNAL, startedAt, attributes);
        } catch (RuntimeException exception) { // NOSONAR - tracing must not break tactic enrichment
            log.debug("[TacticTurnContextService] Failed to start tactic.enrich span: {}", exception.getMessage());
            return null;
        }
    }

    private void recordTacticSpanAttributes(AgentContext context, TraceContext spanContext,
            TacticSearchResult selectedTactic, int resultCount) {
        if (traceService == null || spanContext == null || context.getSession() == null) {
            return;
        }
        try {
            Map<String, Object> eventAttributes = new LinkedHashMap<>();
            eventAttributes.put("tactic.result_count", resultCount);
            if (selectedTactic != null) {
                eventAttributes.put("tactic.selected_id", selectedTactic.getTacticId());
                eventAttributes.put("tactic.selected_title", selectedTactic.getTitle());
                eventAttributes.put("tactic.promotion_state", selectedTactic.getPromotionState());
                if (selectedTactic.getExplanation() != null) {
                    eventAttributes.put("tactic.search_mode", selectedTactic.getExplanation().getSearchMode());
                    if (selectedTactic.getExplanation().getFinalScore() != null) {
                        eventAttributes.put("tactic.final_score", selectedTactic.getExplanation().getFinalScore());
                    }
                    if (selectedTactic.getExplanation().getRerankerVerdict() != null) {
                        eventAttributes.put("tactic.reranker_verdict",
                                selectedTactic.getExplanation().getRerankerVerdict());
                    }
                }
            }
            traceService.appendEvent(context.getSession(), spanContext,
                    "tactic.search.completed", Instant.now(), eventAttributes);
        } catch (RuntimeException exception) { // NOSONAR - tracing must not break tactic enrichment
            log.debug("[TacticTurnContextService] Failed to record tactic span attributes: {}",
                    exception.getMessage());
        }
    }

    private void finishTacticEnrichSpan(AgentContext context, TraceContext spanContext,
            TraceStatusCode statusCode, String statusMessage) {
        if (traceService == null || spanContext == null || context == null || context.getSession() == null) {
            return;
        }
        try {
            traceService.finishSpan(context.getSession(), spanContext, statusCode, statusMessage, Instant.now());
        } catch (RuntimeException exception) { // NOSONAR - tracing must not break tactic enrichment
            log.debug("[TacticTurnContextService] Failed to finish tactic.enrich span: {}", exception.getMessage());
        }
    }
}
