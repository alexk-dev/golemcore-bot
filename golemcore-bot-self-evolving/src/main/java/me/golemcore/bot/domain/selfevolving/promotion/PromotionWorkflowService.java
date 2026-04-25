package me.golemcore.bot.domain.selfevolving.promotion;

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

import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.golemcore.bot.domain.selfevolving.artifact.ArtifactBundleService;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Orchestrates promotion planning and execution for stored evolution
 * candidates.
 */
@Service
public class PromotionWorkflowService {

    private final SelfEvolvingRuntimeConfigPort runtimeConfigPort;
    private final PromotionWorkflowStateService promotionWorkflowStateService;
    private final PromotionTargetResolver promotionTargetResolver;
    private final PromotionExecutionService promotionExecutionService;
    private final ArtifactBundleService artifactBundleService;

    public PromotionWorkflowService(
            SelfEvolvingRuntimeConfigPort runtimeConfigPort,
            PromotionWorkflowStateService promotionWorkflowStateService,
            PromotionTargetResolver promotionTargetResolver,
            PromotionExecutionService promotionExecutionService,
            ArtifactBundleService artifactBundleService) {
        this.runtimeConfigPort = runtimeConfigPort;
        this.promotionWorkflowStateService = promotionWorkflowStateService;
        this.promotionTargetResolver = promotionTargetResolver;
        this.promotionExecutionService = promotionExecutionService;
        this.artifactBundleService = artifactBundleService;
    }

    public List<EvolutionCandidate> registerCandidates(List<EvolutionCandidate> candidates) {
        return promotionWorkflowStateService.registerCandidates(candidates);
    }

    public List<PromotionDecision> registerAndPlanCandidates(List<EvolutionCandidate> candidates) {
        registerCandidates(candidates);
        if (candidates == null || isApprovalGateMode()) {
            return List.of();
        }
        List<PromotionDecision> decisions = new ArrayList<>();
        for (EvolutionCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            decisions.add(planPromotion(candidate));
        }
        return decisions;
    }

    public PromotionDecision planPromotion(String candidateId) {
        EvolutionCandidate candidate = findCandidate(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));
        return planPromotion(candidate);
    }

    public PromotionDecision planPromotion(EvolutionCandidate candidate) {
        if (candidate == null || StringValueSupport.isBlank(candidate.getId())) {
            throw new IllegalArgumentException("Candidate must not be blank");
        }
        registerCandidates(List.of(candidate));

        EvolutionCandidate storedCandidate = findCandidate(candidate.getId()).orElse(candidate);
        PromotionTarget target = promotionTargetResolver.resolve(storedCandidate);
        PromotionExecutionService.PromotionExecutionResult result = promotionExecutionService.execute(storedCandidate,
                target, runtimeConfigPort.getSelfEvolvingPromotionMode());
        saveCandidate(result.updatedCandidate());
        saveDecision(result.decision());
        return result.decision();
    }

    public void bindCandidateBaseRevisions(String bundleId, List<EvolutionCandidate> candidates) {
        if (artifactBundleService == null) {
            return;
        }
        artifactBundleService.bindBaseRevisions(bundleId, candidates);
    }

    public List<EvolutionCandidate> getCandidates() {
        return promotionWorkflowStateService.getCandidates();
    }

    public List<PromotionDecision> getPromotionDecisions() {
        return promotionWorkflowStateService.getPromotionDecisions();
    }

    public Optional<EvolutionCandidate> findCandidate(String candidateId) {
        return promotionWorkflowStateService.findCandidate(candidateId);
    }

    private boolean isApprovalGateMode() {
        return "approval_gate".equalsIgnoreCase(runtimeConfigPort.getSelfEvolvingPromotionMode());
    }

    private void saveCandidate(EvolutionCandidate candidate) {
        promotionWorkflowStateService.saveCandidate(candidate);
    }

    private void saveDecision(PromotionDecision decision) {
        promotionWorkflowStateService.saveDecision(decision);
    }
}
