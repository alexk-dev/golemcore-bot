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
import java.util.Optional;
import me.golemcore.bot.domain.context.ContextLayerLifecycle;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.planning.PlanService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;

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
        if (sessionIdentity != null) {
            return planService.isPlanModeActive(sessionIdentity)
                    || planService.hasPendingExecutionContext(sessionIdentity);
        }
        return planService.isPlanModeActive() || planService.hasPendingExecutionContext();
    }

    @Override
    public ContextLayerResult assemble(AgentContext context) {
        SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(
                context.getSession());
        boolean planModeActive = isPlanModeActive(sessionIdentity);
        if (planModeActive) {
            context.setAttribute(ContextAttributes.ACTIVE_MODE, ContextAttributes.ACTIVE_MODE_PLAN);
            context.setAttribute(ContextAttributes.PLAN_MODE_ACTIVE, true);
        }

        boolean pendingExecutionContext = !planModeActive && hasPendingExecutionContext(sessionIdentity);
        String planContext = resolvePlanContext(sessionIdentity, planModeActive, pendingExecutionContext);

        // Apply plan model tier override if applicable
        Optional<Plan> activePlan = sessionIdentity != null
                ? planService.getActivePlan(sessionIdentity)
                : planService.getActivePlan();
        activePlan.ifPresent(plan -> applyPlanModelTierOverride(context, plan));

        if (planContext == null || planContext.isBlank()) {
            return empty();
        }
        if (pendingExecutionContext) {
            context.setAttribute(ContextAttributes.PLAN_EXECUTION_CONTEXT_PENDING, true);
        }

        return result(planContext);
    }

    private void applyPlanModelTierOverride(AgentContext context, Plan plan) {
        if (context == null || plan == null || plan.getModelTier() == null || plan.getModelTier().isBlank()
                || isHardTierSource(context)) {
            return;
        }
        context.setModelTier(plan.getModelTier());
        context.setAttribute(ContextAttributes.MODEL_TIER_SOURCE, "plan_override");
        clearResolvedTierMetadata(context);
    }

    private boolean isHardTierSource(AgentContext context) {
        String source = context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE);
        if (source == null || source.isBlank()) {
            return false;
        }
        String normalized = source.trim();
        return "webhook".equals(normalized)
                || normalized.endsWith("_forced")
                || ("implicit_default".equals(normalized)
                        && context.getModelTier() != null && !context.getModelTier().isBlank());
    }

    private void clearResolvedTierMetadata(AgentContext context) {
        if (context.getAttributes() == null) {
            return;
        }
        context.getAttributes().remove(ContextAttributes.MODEL_TIER_MODEL_ID);
        context.getAttributes().remove(ContextAttributes.MODEL_TIER_REASONING);
    }

    private boolean isPlanModeActive(SessionIdentity sessionIdentity) {
        if (sessionIdentity != null) {
            return planService.isPlanModeActive(sessionIdentity);
        }
        return planService.isPlanModeActive();
    }

    private boolean hasPendingExecutionContext(SessionIdentity sessionIdentity) {
        if (sessionIdentity != null) {
            return planService.hasPendingExecutionContext(sessionIdentity);
        }
        return planService.hasPendingExecutionContext();
    }

    private String resolvePlanContext(
            SessionIdentity sessionIdentity,
            boolean planModeActive,
            boolean pendingExecutionContext) {
        if (sessionIdentity != null) {
            if (planModeActive) {
                return planService.buildPlanContext(sessionIdentity);
            }
            return pendingExecutionContext ? planService.peekExecutionContext(sessionIdentity) : null;
        }
        if (planModeActive) {
            return planService.buildPlanContext();
        }
        return pendingExecutionContext ? planService.peekExecutionContext() : null;
    }
}
