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

import me.golemcore.bot.port.outbound.SelfEvolvingProjectionPublishPort;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.EvolutionProposal;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticOutcomeEntry;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.selfevolving.benchmark.DeterministicJudgeService;
import me.golemcore.bot.domain.selfevolving.benchmark.LlmJudgeService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionGateService;
import me.golemcore.bot.domain.selfevolving.candidate.LlmEvolutionService;
import me.golemcore.bot.domain.selfevolving.promotion.PromotionWorkflowService;
import me.golemcore.bot.domain.selfevolving.tactic.TacticOutcomeJournalService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.selfevolving.run.SelfEvolvingRunService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal post-turn hook that materializes a SelfEvolving run record.
 */
@Component
@Slf4j
public class PostRunAnalysisSystem implements AgentSystem {

    private final RuntimeConfigService runtimeConfigService;
    private final SelfEvolvingRunService selfEvolvingRunService;
    private final DeterministicJudgeService deterministicJudgeService;
    private final LlmJudgeService llmJudgeService;
    private final LlmEvolutionService llmEvolutionService;
    private final EvolutionCandidateService evolutionCandidateService;
    private final EvolutionGateService evolutionGateService;
    private final PromotionWorkflowService promotionWorkflowService;
    private final TacticOutcomeJournalService tacticOutcomeJournalService;
    private final SelfEvolvingProjectionPublishPort projectionPublishPort;
    private volatile CompletableFuture<Void> lastBackgroundAnalysis;

    public PostRunAnalysisSystem(RuntimeConfigService runtimeConfigService,
            SelfEvolvingRunService selfEvolvingRunService,
            DeterministicJudgeService deterministicJudgeService,
            LlmJudgeService llmJudgeService,
            LlmEvolutionService llmEvolutionService,
            EvolutionCandidateService evolutionCandidateService,
            EvolutionGateService evolutionGateService,
            PromotionWorkflowService promotionWorkflowService,
            TacticOutcomeJournalService tacticOutcomeJournalService,
            SelfEvolvingProjectionPublishPort projectionPublishPort) {
        this.runtimeConfigService = runtimeConfigService;
        this.selfEvolvingRunService = selfEvolvingRunService;
        this.deterministicJudgeService = deterministicJudgeService;
        this.llmJudgeService = llmJudgeService;
        this.llmEvolutionService = llmEvolutionService;
        this.evolutionCandidateService = evolutionCandidateService;
        this.evolutionGateService = evolutionGateService;
        this.promotionWorkflowService = promotionWorkflowService;
        this.tacticOutcomeJournalService = tacticOutcomeJournalService;
        this.projectionPublishPort = projectionPublishPort;
    }

    @Override
    public String getName() {
        return "PostRunAnalysisSystem";
    }

    @Override
    public int getOrder() {
        return 58;
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigService.isSelfEvolvingEnabled();
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        if (!isEnabled()) {
            return false;
        }
        if (context == null || context.getTurnOutcome() == null) {
            return false;
        }
        return !Boolean.TRUE.equals(context.getAttribute(ContextAttributes.SELF_EVOLVING_ANALYSIS_COMPLETED));
    }

    @Override
    public AgentContext process(AgentContext context) {
        if (!shouldProcess(context)) {
            return context;
        }
        RunRecord startedRun = resolveRun(context);
        RunRecord completedRun = selfEvolvingRunService.completeRun(startedRun, context);
        TraceRecord traceRecord = resolveTrace(context, completedRun);
        RunVerdict deterministicVerdict = deterministicJudgeService.evaluate(completedRun, traceRecord);

        context.setAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID, completedRun.getId());
        context.setAttribute(ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID, completedRun.getArtifactBundleId());
        context.setAttribute(ContextAttributes.SELF_EVOLVING_ANALYSIS_COMPLETED, true);

        // LLM judge + evolution pipeline runs in the background so the main
        // session completes immediately and the chat does not hang.
        TacticSearchQuery tacticQuery = context != null
                ? context.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY)
                : null;
        TacticSearchResult tacticSelection = context != null
                ? context.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION)
                : null;
        String userQuery = resolveLastUserMessage(context);
        String assistantResponse = context.getTurnOutcome() != null
                ? context.getTurnOutcome().getAssistantText()
                : null;
        lastBackgroundAnalysis = CompletableFuture.runAsync(() -> runAnalysisInBackground(
                completedRun, traceRecord, deterministicVerdict, tacticQuery, tacticSelection,
                userQuery, assistantResponse));

        return context;
    }

    private void runAnalysisInBackground(RunRecord completedRun, TraceRecord traceRecord,
            RunVerdict deterministicVerdict, TacticSearchQuery tacticQuery, TacticSearchResult tacticSelection,
            String userQuery, String assistantResponse) {
        try {
            RunVerdict llmVerdict = llmJudgeService.judge(completedRun, traceRecord, deterministicVerdict,
                    userQuery, assistantResponse);
            selfEvolvingRunService.saveVerdict(completedRun.getId(), llmVerdict);
            recordTacticOutcomes(completedRun, llmVerdict, tacticQuery, tacticSelection);
            EvolutionProposal proposal = llmEvolutionService.propose(completedRun, llmVerdict);
            List<EvolutionCandidate> candidates;
            if (evolutionGateService.shouldEvolve(completedRun, llmVerdict, proposal)) {
                candidates = evolutionCandidateService.deriveCandidates(completedRun, llmVerdict, proposal);
            } else {
                candidates = List.of();
            }
            if (isAutoAcceptMode()) {
                promotionWorkflowService.registerAndPlanCandidates(candidates);
            } else {
                promotionWorkflowService.registerCandidates(candidates);
            }
            if (!candidates.isEmpty()) {
                bindRunBundleToCandidateBaselines(completedRun, candidates);
            }
            try {
                projectionPublishPort.publishSelfEvolvingProjection(completedRun, llmVerdict, candidates);
            } catch (RuntimeException exception) {
                log.debug("[Hive] Skipping SelfEvolving projection publish: {}", exception.getMessage());
            }
            log.info("[SelfEvolving] Background analysis completed for run {}", completedRun.getId());
        } catch (RuntimeException exception) { // NOSONAR - background task must not propagate
            log.warn("[SelfEvolving] Background analysis failed for run {}: {}", completedRun.getId(),
                    exception.getMessage());
        }
    }

    private void recordTacticOutcomes(RunRecord completedRun, RunVerdict verdict,
            TacticSearchQuery query, TacticSearchResult selection) {
        List<String> appliedTacticIds = completedRun != null ? completedRun.getAppliedTacticIds() : null;
        if (appliedTacticIds == null || appliedTacticIds.isEmpty()) {
            return;
        }
        String finishReason = mapVerdictToFinishReason(verdict);
        for (String tacticId : appliedTacticIds) {
            if (tacticId == null || tacticId.isBlank()) {
                continue;
            }
            try {
                TacticOutcomeEntry entry = TacticOutcomeEntry.builder()
                        .tacticId(tacticId)
                        .rawQuery(query != null ? query.getRawQuery() : null)
                        .queryViews(query != null ? new ArrayList<>(query.getQueryViews()) : null)
                        .searchMode(
                                selection != null && selection.getExplanation() != null
                                        ? selection.getExplanation().getSearchMode()
                                        : null)
                        .finalScore(
                                selection != null && selection.getExplanation() != null
                                        ? selection.getExplanation().getFinalScore()
                                        : null)
                        .finishReason(finishReason)
                        .recordedAt(Instant.now())
                        .build();
                tacticOutcomeJournalService.record(entry);
            } catch (RuntimeException exception) { // NOSONAR - journal must not break pipeline
                log.debug("[PostRunAnalysis] Failed to record tactic outcome for {}: {}", tacticId,
                        exception.getMessage());
            }
        }
    }

    private String mapVerdictToFinishReason(RunVerdict verdict) {
        if (verdict == null || verdict.getOutcomeStatus() == null) {
            return "unknown";
        }
        return switch (verdict.getOutcomeStatus().toUpperCase()) {
        case "COMPLETED", "SUCCESS" -> "success";
        case "FAILED", "ERROR" -> "failure";
        case "PARTIAL" -> "partial";
        default -> verdict.getOutcomeStatus().toLowerCase();
        };
    }

    private boolean isAutoAcceptMode() {
        return "auto_accept".equalsIgnoreCase(runtimeConfigService.getSelfEvolvingPromotionMode());
    }

    private RunRecord resolveRun(AgentContext context) {
        String runId = context != null ? context.getAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID) : null;
        Optional<RunRecord> existingRun = selfEvolvingRunService.findRun(runId);
        if (existingRun.isPresent()) {
            return existingRun.get();
        }
        return selfEvolvingRunService.startRun(context);
    }

    private TraceRecord resolveTrace(AgentContext context, RunRecord completedRun) {
        if (context == null || context.getSession() == null || context.getSession().getTraces() == null) {
            return null;
        }
        if (completedRun != null && completedRun.getTraceId() != null && !completedRun.getTraceId().isBlank()) {
            return context.getSession().getTraces().stream()
                    .filter(trace -> trace != null && completedRun.getTraceId().equals(trace.getTraceId()))
                    .findFirst()
                    .orElse(null);
        }
        if (context.getTraceContext() == null || context.getTraceContext().getTraceId() == null
                || context.getTraceContext().getTraceId().isBlank()) {
            return null;
        }
        return context.getSession().getTraces().stream()
                .filter(trace -> trace != null && context.getTraceContext().getTraceId().equals(trace.getTraceId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Awaits the last background analysis. Visible for testing only.
     */
    CompletableFuture<Void> getLastBackgroundAnalysis() {
        return lastBackgroundAnalysis;
    }

    private String resolveLastUserMessage(AgentContext context) {
        if (context == null || context.getMessages() == null || context.getMessages().isEmpty()) {
            return null;
        }
        for (int i = context.getMessages().size() - 1; i >= 0; i--) {
            me.golemcore.bot.domain.model.Message message = context.getMessages().get(i);
            if (message != null && message.isUserMessage() && message.getContent() != null) {
                return message.getContent();
            }
        }
        return null;
    }

    private void bindRunBundleToCandidateBaselines(RunRecord completedRun, List<EvolutionCandidate> candidates) {
        if (completedRun == null || completedRun.getArtifactBundleId() == null
                || completedRun.getArtifactBundleId().isBlank()) {
            return;
        }
        promotionWorkflowService.bindCandidateBaseRevisions(completedRun.getArtifactBundleId(), candidates);
    }
}
