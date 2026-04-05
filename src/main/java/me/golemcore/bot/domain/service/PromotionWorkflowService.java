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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persists evolution candidates and rollout decisions.
 */
@Service
@Slf4j
public class PromotionWorkflowService {

    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final String CANDIDATES_FILE = "candidates.json";
    private static final String DECISIONS_FILE = "promotion-decisions.json";
    private static final TypeReference<List<EvolutionCandidate>> CANDIDATE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<PromotionDecision>> DECISION_LIST_TYPE = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final RuntimeConfigService runtimeConfigService;
    private final EvolutionCandidateService evolutionCandidateService;
    private final ArtifactBundleService artifactBundleService;
    private final PromotionDecisionHydrationService promotionDecisionHydrationService;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final AtomicReference<List<EvolutionCandidate>> candidateCache = new AtomicReference<>();
    private final AtomicReference<List<PromotionDecision>> decisionCache = new AtomicReference<>();

    public PromotionWorkflowService(
            StoragePort storagePort,
            RuntimeConfigService runtimeConfigService,
            EvolutionCandidateService evolutionCandidateService,
            ArtifactBundleService artifactBundleService,
            PromotionDecisionHydrationService promotionDecisionHydrationService,
            Clock clock) {
        this.storagePort = storagePort;
        this.runtimeConfigService = runtimeConfigService;
        this.evolutionCandidateService = evolutionCandidateService;
        this.artifactBundleService = artifactBundleService;
        this.promotionDecisionHydrationService = promotionDecisionHydrationService;
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
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
        saveCandidates(storedCandidates);
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
        PromotionTarget target = resolvePromotionTarget(storedCandidate);
        String targetBundleId = buildTargetBundleId(storedCandidate, target);
        if (artifactBundleService != null) {
            ArtifactBundleRecord promotedBundle = artifactBundleService.promoteCandidateBundle(
                    targetBundleId,
                    storedCandidate,
                    target.rolloutStage());
            if (promotedBundle != null && !StringValueSupport.isBlank(promotedBundle.getId())) {
                targetBundleId = promotedBundle.getId();
            }
        }
        PromotionDecision decision = PromotionDecision.builder()
                .id(UUID.randomUUID().toString())
                .candidateId(storedCandidate.getId())
                .bundleId(targetBundleId)
                .originBundleId(storedCandidate.getBaseVersion())
                .state(target.legacyState())
                .fromState(storedCandidate.getStatus())
                .toState(target.legacyState())
                .artifactType(storedCandidate.getArtifactType())
                .artifactSubtype(storedCandidate.getArtifactSubtype())
                .artifactStreamId(storedCandidate.getArtifactStreamId())
                .originArtifactStreamId(storedCandidate.getOriginArtifactStreamId())
                .artifactKey(storedCandidate.getArtifactKey())
                .contentRevisionId(storedCandidate.getContentRevisionId())
                .baseContentRevisionId(storedCandidate.getBaseContentRevisionId())
                .fromLifecycleState(storedCandidate.getLifecycleState())
                .toLifecycleState(target.lifecycleState())
                .fromRolloutStage(storedCandidate.getRolloutStage())
                .toRolloutStage(target.rolloutStage())
                .mode(runtimeConfigService.getSelfEvolvingPromotionMode())
                .approvalRequestId(
                        "approved_pending".equals(target.legacyState()) ? storedCandidate.getId() + "-approval" : null)
                .reason(buildReason(target.legacyState()))
                .decidedAt(Instant.now(clock))
                .build();

        EvolutionCandidate updatedCandidate = cloneCandidate(storedCandidate);
        updatedCandidate.setStatus(target.legacyState());
        updatedCandidate.setLifecycleState(target.lifecycleState());
        updatedCandidate.setRolloutStage(target.rolloutStage());
        saveCandidate(updatedCandidate);
        saveDecision(decision);
        return decision;
    }

    public void bindCandidateBaseRevisions(String bundleId, List<EvolutionCandidate> candidates) {
        if (artifactBundleService == null) {
            return;
        }
        artifactBundleService.bindBaseRevisions(bundleId, candidates);
    }

    public List<EvolutionCandidate> getCandidates() {
        List<EvolutionCandidate> cached = candidateCache.get();
        if (cached == null) {
            cached = loadCandidates();
            candidateCache.set(cached);
        }
        return cached;
    }

    public List<PromotionDecision> getPromotionDecisions() {
        List<PromotionDecision> cached = decisionCache.get();
        if (cached == null) {
            cached = loadDecisions();
            decisionCache.set(cached);
        }
        return cached;
    }

    public Optional<EvolutionCandidate> findCandidate(String candidateId) {
        return getCandidates().stream()
                .filter(candidate -> candidate != null && candidateId.equals(candidate.getId()))
                .findFirst();
    }

    private PromotionTarget resolvePromotionTarget(EvolutionCandidate candidate) {
        String promotionMode = runtimeConfigService.getSelfEvolvingPromotionMode();
        if (!"approval_gate".equalsIgnoreCase(promotionMode)
                && !"auto_accept".equalsIgnoreCase(promotionMode)) {
            throw new IllegalArgumentException("Unsupported promotion mode: " + promotionMode);
        }
        String currentState = resolveCurrentState(candidate);
        if ("shadowed".equals(currentState)) {
            return runtimeConfigService.isSelfEvolvingPromotionCanaryRequired()
                    ? new PromotionTarget("canary", "candidate", "canary")
                    : new PromotionTarget("active", "active", "active");
        }
        if ("canary".equals(currentState)) {
            return new PromotionTarget("active", "active", "active");
        }
        if ("active".equals(currentState)) {
            return new PromotionTarget("active", "active", "active");
        }
        if (runtimeConfigService.isSelfEvolvingPromotionShadowRequired()) {
            return new PromotionTarget("shadowed", "candidate", "shadowed");
        }
        if (runtimeConfigService.isSelfEvolvingPromotionCanaryRequired()) {
            return new PromotionTarget("canary", "candidate", "canary");
        }
        return new PromotionTarget("active", "active", "active");
    }

    private boolean isApprovalGateMode() {
        return "approval_gate".equalsIgnoreCase(runtimeConfigService.getSelfEvolvingPromotionMode());
    }

    private String buildTargetBundleId(EvolutionCandidate storedCandidate, PromotionTarget target) {
        if (storedCandidate == null || StringValueSupport.isBlank(storedCandidate.getId())) {
            return storedCandidate != null ? storedCandidate.getBaseVersion() : null;
        }
        return storedCandidate.getId() + ":" + target.rolloutStage();
    }

    private String buildReason(String nextState) {
        return switch (nextState) {
        case "approved_pending" -> "Queued for approval";
        case "shadowed" -> "Approved and entered shadow rollout";
        case "canary" -> "Advanced into canary rollout";
        case "active" -> "Approved and activated as tactic";
        default -> "Promotion planned";
        };
    }

    private String resolveCurrentState(EvolutionCandidate candidate) {
        if (candidate == null) {
            return "proposed";
        }
        if (!StringValueSupport.isBlank(candidate.getRolloutStage())
                && !"approved".equals(candidate.getRolloutStage())) {
            return candidate.getRolloutStage();
        }
        if (!StringValueSupport.isBlank(candidate.getStatus())
                && !"approved_pending".equals(candidate.getStatus())
                && !"approved".equals(candidate.getStatus())) {
            return candidate.getStatus();
        }
        return "proposed";
    }

    private void saveCandidate(EvolutionCandidate candidate) {
        List<EvolutionCandidate> candidates = new ArrayList<>(getCandidates());
        upsertCandidate(candidates, candidate);
        saveCandidates(candidates);
        evolutionCandidateService.syncTacticRecord(candidate);
    }

    private void saveDecision(PromotionDecision decision) {
        List<PromotionDecision> decisions = new ArrayList<>(getPromotionDecisions());
        decisions.add(decision);
        saveDecisions(decisions);
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

    private List<EvolutionCandidate> loadCandidates() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, CANDIDATES_FILE).join();
            if (StringValueSupport.isBlank(json)) {
                return new ArrayList<>();
            }
            List<EvolutionCandidate> candidates = objectMapper.readValue(json, CANDIDATE_LIST_TYPE);
            List<EvolutionCandidate> normalizedCandidates = candidates != null ? new ArrayList<>(candidates)
                    : new ArrayList<>();
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
                saveCandidates(normalizedCandidates);
            }
            return normalizedCandidates;
        } catch (IOException | RuntimeException e) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load candidates: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<PromotionDecision> loadDecisions() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, DECISIONS_FILE).join();
            if (StringValueSupport.isBlank(json)) {
                return new ArrayList<>();
            }
            List<PromotionDecision> decisions = objectMapper.readValue(json, DECISION_LIST_TYPE);
            List<PromotionDecision> normalizedDecisions = decisions != null ? new ArrayList<>(decisions)
                    : new ArrayList<>();
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
                saveDecisions(normalizedDecisions);
            }
            return normalizedDecisions;
        } catch (IOException | RuntimeException e) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load promotion decisions: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void saveCandidates(List<EvolutionCandidate> candidates) {
        try {
            String json = objectMapper.writeValueAsString(candidates);
            storagePort.putTextAtomic(SELF_EVOLVING_DIR, CANDIDATES_FILE, json, true).join();
            candidateCache.set(new ArrayList<>(candidates));
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist self-evolving candidates", e);
        }
    }

    private void saveDecisions(List<PromotionDecision> decisions) {
        try {
            String json = objectMapper.writeValueAsString(decisions);
            storagePort.putTextAtomic(SELF_EVOLVING_DIR, DECISIONS_FILE, json, true).join();
            decisionCache.set(new ArrayList<>(decisions));
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist promotion decisions", e);
        }
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

    private record PromotionTarget(String legacyState, String lifecycleState, String rolloutStage) {
    }
}
