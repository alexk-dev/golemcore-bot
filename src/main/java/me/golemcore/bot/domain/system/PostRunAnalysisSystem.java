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

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.adapter.outbound.hive.HiveEventBatchPublisher;
import me.golemcore.bot.domain.service.DeterministicJudgeService;
import me.golemcore.bot.domain.service.EvolutionCandidateService;
import me.golemcore.bot.domain.service.LlmJudgeService;
import me.golemcore.bot.domain.service.PromotionWorkflowService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SelfEvolvingRunService;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

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
    private final EvolutionCandidateService evolutionCandidateService;
    private final PromotionWorkflowService promotionWorkflowService;
    private final HiveEventBatchPublisher hiveEventBatchPublisher;

    public PostRunAnalysisSystem(RuntimeConfigService runtimeConfigService,
            SelfEvolvingRunService selfEvolvingRunService,
            DeterministicJudgeService deterministicJudgeService,
            LlmJudgeService llmJudgeService,
            EvolutionCandidateService evolutionCandidateService,
            PromotionWorkflowService promotionWorkflowService,
            HiveEventBatchPublisher hiveEventBatchPublisher) {
        this.runtimeConfigService = runtimeConfigService;
        this.selfEvolvingRunService = selfEvolvingRunService;
        this.deterministicJudgeService = deterministicJudgeService;
        this.llmJudgeService = llmJudgeService;
        this.evolutionCandidateService = evolutionCandidateService;
        this.promotionWorkflowService = promotionWorkflowService;
        this.hiveEventBatchPublisher = hiveEventBatchPublisher;
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
        RunVerdict deterministicVerdict = deterministicJudgeService.evaluate(completedRun, null);
        RunVerdict llmVerdict = llmJudgeService.judge(completedRun, null, deterministicVerdict);
        List<me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate> candidates = evolutionCandidateService
                .deriveCandidates(completedRun, llmVerdict);
        promotionWorkflowService.registerAndPlanCandidates(candidates);
        try {
            hiveEventBatchPublisher.publishSelfEvolvingProjection(completedRun, llmVerdict, candidates);
        } catch (RuntimeException exception) {
            log.debug("[Hive] Skipping SelfEvolving projection publish: {}", exception.getMessage());
        }
        context.setAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID, completedRun.getId());
        context.setAttribute(ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID, completedRun.getArtifactBundleId());
        context.setAttribute(ContextAttributes.SELF_EVOLVING_ANALYSIS_COMPLETED, true);
        return context;
    }

    private RunRecord resolveRun(AgentContext context) {
        String runId = context != null ? context.getAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID) : null;
        Optional<RunRecord> existingRun = selfEvolvingRunService.findRun(runId);
        if (existingRun.isPresent()) {
            return existingRun.get();
        }
        return selfEvolvingRunService.startRun(context);
    }
}
