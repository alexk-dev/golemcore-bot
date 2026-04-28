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
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateService;
import me.golemcore.bot.domain.support.StringValueSupport;

/**
 * Candidate and decision state operations for the promotion workflow.
 */
@Service
public class PromotionWorkflowStateService {

    private final PromotionWorkflowStore promotionWorkflowStore;
    private final EvolutionCandidateService evolutionCandidateService;
    private final PromotionDecisionHydrationService promotionDecisionHydrationService;

    public PromotionWorkflowStateService(
            PromotionWorkflowStore promotionWorkflowStore,
            EvolutionCandidateService evolutionCandidateService,
            PromotionDecisionHydrationService promotionDecisionHydrationService) {
        this.promotionWorkflowStore = promotionWorkflowStore;
        this.evolutionCandidateService = evolutionCandidateService;
        this.promotionDecisionHydrationService = promotionDecisionHydrationService;
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

    public Optional<EvolutionCandidate> findCandidate(String candidateId) {
        return getCandidates().stream()
                .filter(candidate -> candidate != null && candidateId.equals(candidate.getId()))
                .findFirst();
    }

    public List<PromotionDecision> getPromotionDecisions() {
        List<PromotionDecision> normalizedDecisions = new ArrayList<>(promotionWorkflowStore.getPromotionDecisions());
        List<EvolutionCandidate> candidates = getCandidates();
        boolean mutated = false;
        for (PromotionDecision decision : normalizedDecisions) {
            if (decision == null) {
                continue;
            }
            EvolutionCandidate candidate = findCandidate(candidates, decision.getCandidateId()).orElse(null);
            if (promotionDecisionHydrationService.hydrate(decision, candidate)) {
                mutated = true;
            }
        }
        if (mutated) {
            promotionWorkflowStore.savePromotionDecisions(normalizedDecisions);
        }
        return normalizedDecisions;
    }

    public void saveCandidate(EvolutionCandidate candidate) {
        List<EvolutionCandidate> candidates = new ArrayList<>(getCandidates());
        upsertCandidate(candidates, candidate);
        promotionWorkflowStore.saveCandidates(candidates);
        evolutionCandidateService.syncTacticRecord(candidate);
    }

    public void saveDecision(PromotionDecision decision) {
        List<PromotionDecision> decisions = new ArrayList<>(getPromotionDecisions());
        decisions.add(decision);
        promotionWorkflowStore.savePromotionDecisions(decisions);
    }

    private Optional<EvolutionCandidate> findCandidate(List<EvolutionCandidate> candidates, String candidateId) {
        return candidates.stream()
                .filter(candidate -> candidate != null && candidateId.equals(candidate.getId()))
                .findFirst();
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
