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
import org.springframework.stereotype.Service;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import me.golemcore.bot.domain.support.StringValueSupport;

/**
 * Resolves the next rollout target for an evolution candidate based on the
 * configured promotion mode and rollout requirements.
 */
@Service
public class PromotionTargetResolver {

    private final SelfEvolvingRuntimeConfigPort runtimeConfigPort;

    public PromotionTargetResolver(SelfEvolvingRuntimeConfigPort runtimeConfigPort) {
        this.runtimeConfigPort = runtimeConfigPort;
    }

    public PromotionTarget resolve(EvolutionCandidate candidate) {
        String promotionMode = runtimeConfigPort.getSelfEvolvingPromotionMode();
        if (!"approval_gate".equalsIgnoreCase(promotionMode)
                && !"auto_accept".equalsIgnoreCase(promotionMode)) {
            throw new IllegalArgumentException("Unsupported promotion mode: " + promotionMode);
        }
        String currentState = resolveCurrentState(candidate);
        if ("shadowed".equals(currentState)) {
            return runtimeConfigPort.isSelfEvolvingPromotionCanaryRequired()
                    ? new PromotionTarget("canary", "candidate", "canary")
                    : new PromotionTarget("active", "active", "active");
        }
        if ("canary".equals(currentState)) {
            return new PromotionTarget("active", "active", "active");
        }
        if ("active".equals(currentState)) {
            return new PromotionTarget("active", "active", "active");
        }
        if (runtimeConfigPort.isSelfEvolvingPromotionShadowRequired()) {
            return new PromotionTarget("shadowed", "candidate", "shadowed");
        }
        if (runtimeConfigPort.isSelfEvolvingPromotionCanaryRequired()) {
            return new PromotionTarget("canary", "candidate", "canary");
        }
        return new PromotionTarget("active", "active", "active");
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
}
