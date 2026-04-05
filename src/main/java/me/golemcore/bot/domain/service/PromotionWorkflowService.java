package me.golemcore.bot.domain.service;

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

/**
 * Persists evolution candidates and rollout decisions.
 */
@Service
public class PromotionWorkflowService {

    private final RuntimeConfigService runtimeConfigService;
    private final EvolutionCandidateService evolutionCandidateService;
    private final PromotionWorkflowStore promotionWorkflowStore;
    private final PromotionTargetResolver promotionTargetResolver;
    private final PromotionDecisionHydrationService promotionDecisionHydrationService;
    private final PromotionExecutionService promotionExecutionService;
    private final ArtifactBundleService artifactBundleService;

    public PromotionWorkflowService(
            RuntimeConfigService runtimeConfigService,
            EvolutionCandidateService evolutionCandidateService,
            PromotionWorkflowStore promotionWorkflowStore,
            PromotionTargetResolver promotionTargetResolver,
            PromotionDecisionHydrationService promotionDecisionHydrationService,
            PromotionExecutionService promotionExecutionService,
            ArtifactBundleService artifactBundleService) {
        this.runtimeConfigService = runtimeConfigService;
        this.evolutionCandidateService = evolutionCandidateService;
        this.promotionWorkflowStore = promotionWorkflowStore;
        this.promotionTargetResolver = promotionTargetResolver;
        this.promotionDecisionHydrationService = promotionDecisionHydrationService;
        this.promotionExecutionService = promotionExecutionService;
        this.artifactBundleService = artifactBundleService;
    }

    public List<EvolutionCandidate> registerCandidates(List<EvolutionCandidate> candidates) {
        List<EvolutionCandidate> storedCandidates = new ArrayList<>(getCandidates());
        if (candidates == null) {
            return List.of();
        }
        List<EvolutionCandidate> normalizedResults = new ArrayList<>();
        for (EvolutionCandidate candidate : candidates) {
            if (candidate == null || StringValueSupport.isBlank(candidate.getId())) {
                continue;
            }
            EvolutionCandidate normalizedCandidate = evolutionCandidateService
                    .ensureArtifactIdentity(cloneCandidate(candidate));
            upsertCandidate(storedCandidates, normalizedCandidate);
            evolutionCandidateService.syncTacticRecord(normalizedCandidate);
            normalizedResults.add(normalizedCandidate);
        }
        promotionWorkflowStore.saveCandidates(storedCandidates);
        return normalizedResults;
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
                target, runtimeConfigService.getSelfEvolvingPromotionMode());
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
        List<EvolutionCandidate> normalizedCandidates = new ArrayList<>(promotionWorkflowStore.getCandidates());
        boolean mutated = false;
        for (EvolutionCandidate candidate : normalizedCandidates) {
            if (candidate == null) {
                continue;
            }
            if (StringValueSupport.isBlank(candidate.getArtifactStreamId())
                    || StringValueSupport.isBlank(candidate.getContentRevisionId())
                    || StringValueSupport.isBlank(candidate.getLifecycleState())
                    || StringValueSupport.isBlank(candidate.getRolloutStage())) {
                mutated = true;
            }
            evolutionCandidateService.ensureArtifactIdentity(candidate);
            evolutionCandidateService.syncTacticRecord(candidate);
        }
        if (mutated) {
            promotionWorkflowStore.saveCandidates(normalizedCandidates);
        }
        return normalizedCandidates;
    }

    public List<PromotionDecision> getPromotionDecisions() {
        List<PromotionDecision> normalizedDecisions = new ArrayList<>(promotionWorkflowStore.getPromotionDecisions());
        boolean mutated = false;
        for (PromotionDecision decision : normalizedDecisions) {
            if (decision == null) {
                continue;
            }
            EvolutionCandidate candidate = findCandidate(decision.getCandidateId()).orElse(null);
            if (promotionDecisionHydrationService.hydrate(decision, candidate)) {
                mutated = true;
            }
        }
        if (mutated) {
            promotionWorkflowStore.savePromotionDecisions(normalizedDecisions);
        }
        return normalizedDecisions;
    }

    public Optional<EvolutionCandidate> findCandidate(String candidateId) {
        return getCandidates().stream()
                .filter(candidate -> candidate != null && candidateId.equals(candidate.getId()))
                .findFirst();
    }

    private boolean isApprovalGateMode() {
        return "approval_gate".equalsIgnoreCase(runtimeConfigService.getSelfEvolvingPromotionMode());
    }

    private void saveCandidate(EvolutionCandidate candidate) {
        List<EvolutionCandidate> candidates = new ArrayList<>(getCandidates());
        upsertCandidate(candidates, candidate);
        promotionWorkflowStore.saveCandidates(candidates);
        evolutionCandidateService.syncTacticRecord(candidate);
    }

    private void saveDecision(PromotionDecision decision) {
        List<PromotionDecision> decisions = new ArrayList<>(getPromotionDecisions());
        decisions.add(decision);
        promotionWorkflowStore.savePromotionDecisions(decisions);
    }

    private void upsertCandidate(List<EvolutionCandidate> candidates, EvolutionCandidate candidate) {
        for (int index = 0; index < candidates.size(); index++) {
            EvolutionCandidate existing = candidates.get(index);
            if (existing != null && candidate.getId().equals(existing.getId())) {
                candidates.set(index, candidate);
                return;
            }
        }
        candidates.add(candidate);
    }

    private EvolutionCandidate cloneCandidate(EvolutionCandidate candidate) {
        return candidate.toBuilder()
                .sourceRunIds(candidate.getSourceRunIds() != null ? new ArrayList<>(candidate.getSourceRunIds())
                        : new ArrayList<>())
                .artifactAliases(
                        candidate.getArtifactAliases() != null ? new ArrayList<>(candidate.getArtifactAliases())
                                : new ArrayList<>())
                .evidenceRefs(candidate.getEvidenceRefs() != null ? new ArrayList<>(candidate.getEvidenceRefs())
                        : new ArrayList<>())
                .build();
    }
}
