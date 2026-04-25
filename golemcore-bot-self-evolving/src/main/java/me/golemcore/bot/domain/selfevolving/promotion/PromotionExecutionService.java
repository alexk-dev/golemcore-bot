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

import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import me.golemcore.bot.domain.selfevolving.artifact.ArtifactBundleService;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Applies a resolved promotion target to a candidate and builds the persisted
 * decision record.
 */
@Service
public class PromotionExecutionService {

    private final ArtifactBundleService artifactBundleService;
    private final Clock clock;

    public PromotionExecutionService(ArtifactBundleService artifactBundleService, Clock clock) {
        this.artifactBundleService = artifactBundleService;
        this.clock = clock;
    }

    public PromotionExecutionResult execute(EvolutionCandidate candidate, PromotionTarget target,
            String promotionMode) {
        if (candidate == null || StringValueSupport.isBlank(candidate.getId())) {
            throw new IllegalArgumentException("Candidate must not be blank");
        }
        if (target == null) {
            throw new IllegalArgumentException("Promotion target must not be blank");
        }

        String targetBundleId = buildTargetBundleId(candidate, target);
        if (artifactBundleService != null) {
            ArtifactBundleRecord promotedBundle = artifactBundleService.promoteCandidateBundle(
                    targetBundleId,
                    candidate,
                    target.rolloutStage());
            if (promotedBundle != null && !StringValueSupport.isBlank(promotedBundle.getId())) {
                targetBundleId = promotedBundle.getId();
            }
        }

        PromotionDecision decision = PromotionDecision.builder()
                .id(UUID.randomUUID().toString())
                .candidateId(candidate.getId())
                .bundleId(targetBundleId)
                .originBundleId(candidate.getBaseVersion())
                .state(target.legacyState())
                .fromState(candidate.getStatus())
                .toState(target.legacyState())
                .artifactType(candidate.getArtifactType())
                .artifactSubtype(candidate.getArtifactSubtype())
                .artifactStreamId(candidate.getArtifactStreamId())
                .originArtifactStreamId(candidate.getOriginArtifactStreamId())
                .artifactKey(candidate.getArtifactKey())
                .contentRevisionId(candidate.getContentRevisionId())
                .baseContentRevisionId(candidate.getBaseContentRevisionId())
                .fromLifecycleState(candidate.getLifecycleState())
                .toLifecycleState(target.lifecycleState())
                .fromRolloutStage(candidate.getRolloutStage())
                .toRolloutStage(target.rolloutStage())
                .mode(promotionMode)
                .approvalRequestId(
                        "approved_pending".equals(target.legacyState()) ? candidate.getId() + "-approval" : null)
                .reason(buildReason(target.legacyState()))
                .decidedAt(Instant.now(clock))
                .build();

        EvolutionCandidate updatedCandidate = candidate.toBuilder()
                .status(target.legacyState())
                .lifecycleState(target.lifecycleState())
                .rolloutStage(target.rolloutStage())
                .build();
        return new PromotionExecutionResult(updatedCandidate, decision);
    }

    private String buildTargetBundleId(EvolutionCandidate candidate, PromotionTarget target) {
        return candidate.getId() + ":" + target.rolloutStage();
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

    public record PromotionExecutionResult(EvolutionCandidate updatedCandidate, PromotionDecision decision) {
    }
}
