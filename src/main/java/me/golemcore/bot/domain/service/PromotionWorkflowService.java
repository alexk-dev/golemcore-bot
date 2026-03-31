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
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final AtomicReference<List<EvolutionCandidate>> candidateCache = new AtomicReference<>();
    private final AtomicReference<List<PromotionDecision>> decisionCache = new AtomicReference<>();

    public PromotionWorkflowService(StoragePort storagePort, RuntimeConfigService runtimeConfigService) {
        this(storagePort, runtimeConfigService, Clock.systemUTC());
    }

    PromotionWorkflowService(StoragePort storagePort, RuntimeConfigService runtimeConfigService, Clock clock) {
        this.storagePort = storagePort;
        this.runtimeConfigService = runtimeConfigService;
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public List<EvolutionCandidate> registerCandidates(List<EvolutionCandidate> candidates) {
        List<EvolutionCandidate> storedCandidates = new ArrayList<>(getCandidates());
        if (candidates == null) {
            return List.of();
        }
        for (EvolutionCandidate candidate : candidates) {
            if (candidate == null || StringValueSupport.isBlank(candidate.getId())) {
                continue;
            }
            upsertCandidate(storedCandidates, candidate);
        }
        saveCandidates(storedCandidates);
        return new ArrayList<>(candidates);
    }

    public List<PromotionDecision> registerAndPlanCandidates(List<EvolutionCandidate> candidates) {
        registerCandidates(candidates);
        if (candidates == null) {
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
        String nextState = resolveNextState();
        PromotionDecision decision = PromotionDecision.builder()
                .id(UUID.randomUUID().toString())
                .candidateId(storedCandidate.getId())
                .bundleId(storedCandidate.getBaseVersion())
                .state(nextState)
                .fromState(storedCandidate.getStatus())
                .toState(nextState)
                .mode(runtimeConfigService.getSelfEvolvingPromotionMode())
                .approvalRequestId("approved_pending".equals(nextState) ? storedCandidate.getId() + "-approval" : null)
                .reason(buildReason(nextState))
                .decidedAt(Instant.now(clock))
                .build();

        EvolutionCandidate updatedCandidate = cloneCandidate(storedCandidate);
        updatedCandidate.setStatus(nextState);
        saveCandidate(updatedCandidate);
        saveDecision(decision);
        return decision;
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

    private String resolveNextState() {
        String promotionMode = runtimeConfigService.getSelfEvolvingPromotionMode();
        return switch (promotionMode) {
        case "approval_gate" -> "approved_pending";
        case "auto_accept" -> "shadowed";
        default -> throw new IllegalArgumentException("Unsupported promotion mode: " + promotionMode);
        };
    }

    private String buildReason(String nextState) {
        return switch (nextState) {
        case "approved_pending" -> "Awaiting approval before rollout";
        case "shadowed" -> "Auto-accepted into shadow rollout";
        default -> "Promotion planned";
        };
    }

    private void saveCandidate(EvolutionCandidate candidate) {
        List<EvolutionCandidate> candidates = new ArrayList<>(getCandidates());
        upsertCandidate(candidates, candidate);
        saveCandidates(candidates);
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
            return candidates != null ? new ArrayList<>(candidates) : new ArrayList<>();
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
            return decisions != null ? new ArrayList<>(decisions) : new ArrayList<>();
        } catch (IOException | RuntimeException e) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load promotion decisions: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void saveCandidates(List<EvolutionCandidate> candidates) {
        try {
            String json = objectMapper.writeValueAsString(candidates);
            storagePort.putText(SELF_EVOLVING_DIR, CANDIDATES_FILE, json).join();
            candidateCache.set(new ArrayList<>(candidates));
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist self-evolving candidates", e);
        }
    }

    private void saveDecisions(List<PromotionDecision> decisions) {
        try {
            String json = objectMapper.writeValueAsString(decisions);
            storagePort.putText(SELF_EVOLVING_DIR, DECISIONS_FILE, json).join();
            decisionCache.set(new ArrayList<>(decisions));
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist promotion decisions", e);
        }
    }

    private EvolutionCandidate cloneCandidate(EvolutionCandidate candidate) {
        return EvolutionCandidate.builder()
                .id(candidate.getId())
                .golemId(candidate.getGolemId())
                .goal(candidate.getGoal())
                .artifactType(candidate.getArtifactType())
                .baseVersion(candidate.getBaseVersion())
                .proposedDiff(candidate.getProposedDiff())
                .expectedImpact(candidate.getExpectedImpact())
                .riskLevel(candidate.getRiskLevel())
                .status(candidate.getStatus())
                .createdAt(candidate.getCreatedAt())
                .sourceRunIds(candidate.getSourceRunIds() != null ? new ArrayList<>(candidate.getSourceRunIds())
                        : new ArrayList<>())
                .evidenceRefs(candidate.getEvidenceRefs() != null ? new ArrayList<>(candidate.getEvidenceRefs())
                        : new ArrayList<>())
                .build();
    }
}
