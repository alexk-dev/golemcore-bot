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
import me.golemcore.bot.domain.selfevolving.candidate.CandidateLifecycleResolver;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Hydrates legacy promotion decisions from candidate identity and rollout state
 * fallbacks.
 */
@Service
public class PromotionDecisionHydrationService {

    public boolean hydrate(PromotionDecision decision, EvolutionCandidate candidate) {
        if (decision == null) {
            return false;
        }
        boolean mutated = false;
        if (candidate != null) {
            if (StringValueSupport.isBlank(decision.getArtifactType())) {
                decision.setArtifactType(candidate.getArtifactType());
                mutated = true;
            }
            if (StringValueSupport.isBlank(decision.getArtifactSubtype())) {
                decision.setArtifactSubtype(candidate.getArtifactSubtype());
                mutated = true;
            }
            if (StringValueSupport.isBlank(decision.getArtifactStreamId())) {
                decision.setArtifactStreamId(candidate.getArtifactStreamId());
                mutated = true;
            }
            if (StringValueSupport.isBlank(decision.getOriginArtifactStreamId())) {
                decision.setOriginArtifactStreamId(candidate.getOriginArtifactStreamId());
                mutated = true;
            }
            if (StringValueSupport.isBlank(decision.getArtifactKey())) {
                decision.setArtifactKey(candidate.getArtifactKey());
                mutated = true;
            }
            if (StringValueSupport.isBlank(decision.getContentRevisionId())) {
                decision.setContentRevisionId(candidate.getContentRevisionId());
                mutated = true;
            }
            if (StringValueSupport.isBlank(decision.getBaseContentRevisionId())) {
                decision.setBaseContentRevisionId(candidate.getBaseContentRevisionId());
                mutated = true;
            }
            if (StringValueSupport.isBlank(decision.getOriginBundleId())) {
                decision.setOriginBundleId(candidate.getBaseVersion());
                mutated = true;
            }
            if (StringValueSupport.isBlank(decision.getFromLifecycleState())) {
                decision.setFromLifecycleState(candidate.getLifecycleState());
                mutated = true;
            }
            if (StringValueSupport.isBlank(decision.getFromRolloutStage())) {
                decision.setFromRolloutStage(candidate.getRolloutStage());
                mutated = true;
            }
        }

        PromotionTarget target = resolveTarget(decision);
        if (StringValueSupport.isBlank(decision.getToLifecycleState())) {
            decision.setToLifecycleState(target.lifecycleState());
            mutated = true;
        }
        if (StringValueSupport.isBlank(decision.getToRolloutStage())) {
            decision.setToRolloutStage(target.rolloutStage());
            mutated = true;
        }
        if (candidate != null && StringValueSupport.isBlank(decision.getBundleId())) {
            decision.setBundleId(buildTargetBundleId(candidate, target));
            mutated = true;
        }
        return mutated;
    }

    private PromotionTarget resolveTarget(PromotionDecision decision) {
        String toState = decision != null ? decision.getToState() : null;
        String toLifecycleState = decision != null ? decision.getToLifecycleState() : null;
        String toRolloutStage = decision != null ? decision.getToRolloutStage() : null;
        return new PromotionTarget(
                !StringValueSupport.isBlank(toState)
                        ? toState
                        : CandidateLifecycleResolver.resolveLegacyStatus(toLifecycleState, toRolloutStage),
                !StringValueSupport.isBlank(toLifecycleState)
                        ? toLifecycleState
                        : CandidateLifecycleResolver.resolveLifecycleState(toState),
                !StringValueSupport.isBlank(toRolloutStage)
                        ? toRolloutStage
                        : CandidateLifecycleResolver.resolveRolloutStage(toState));
    }

    private String buildTargetBundleId(EvolutionCandidate candidate, PromotionTarget target) {
        if (candidate == null || StringValueSupport.isBlank(candidate.getId())) {
            return candidate != null ? candidate.getBaseVersion() : null;
        }
        return candidate.getId() + ":" + target.rolloutStage();
    }

}
