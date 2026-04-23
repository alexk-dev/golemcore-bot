package me.golemcore.bot.domain.context.layer;

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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.context.ContextLayerLifecycle;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;

import java.util.Optional;

/**
 * Injects plan mode context when a planning session is active.
 *
 * <p>
 * Only applies when plan mode is active for the current session. Renders the
 * plan context (current plan content, instructions) and may apply a
 * plan-specified model tier override.
 */
@Slf4j
public class PlanModeLayer extends AbstractContextLayer {

    private final PlanService planService;

    public PlanModeLayer(PlanService planService) {
        super("plan_mode", 72, 95, ContextLayerLifecycle.TURN, true);
        this.planService = planService;
    }

    @Override
    public boolean appliesTo(AgentContext context) {
        SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(
                context.getSession());
        return sessionIdentity != null
                ? planService.isPlanModeActive(sessionIdentity)
                : planService.isPlanModeActive();
    }

    @Override
    public ContextLayerResult assemble(AgentContext context) {
        SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(
                context.getSession());

        String planContext = sessionIdentity != null
                ? planService.buildPlanContext(sessionIdentity)
                : planService.buildPlanContext();

        // Apply plan model tier override if applicable
        Optional<Plan> activePlan = sessionIdentity != null
                ? planService.getActivePlan(sessionIdentity)
                : planService.getActivePlan();
        activePlan.ifPresent(plan -> {
            if (plan.getModelTier() != null && context.getModelTier() == null) {
                context.setModelTier(plan.getModelTier());
            }
        });

        if (planContext == null || planContext.isBlank()) {
            return empty();
        }

        return result(planContext);
    }
}
