package me.golemcore.bot.domain.system;

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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PlanReadyEvent;
import me.golemcore.bot.domain.service.PlanService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Detects when plan mode should be finalized (via plan_finalize tool call) and
 * finalizes the plan (order=58, before ResponseRouting at 60).
 *
 * <p>
 * When the LLM invokes plan_finalize during plan mode:
 * <ol>
 * <li>If plan has 0 steps, cancels the empty plan and returns</li>
 * <li>Finalizes the plan (COLLECTING -> READY)</li>
 * <li>Appends a plan summary to the LLM response</li>
 * <li>Publishes PlanReadyEvent for the Telegram approval UI</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanFinalizationSystem implements AgentSystem {

    private final PlanService planService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public String getName() {
        return "PlanFinalizationSystem";
    }

    @Override
    public int getOrder() {
        return 58;
    }

    @Override
    public boolean isEnabled() {
        return planService.isFeatureEnabled();
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        if (!planService.isPlanModeActive()) {
            return false;
        }

        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        if (response == null) {
            return false;
        }

        boolean finalizeRequested = false;
        if (response.getToolCalls() != null) {
            finalizeRequested = response.getToolCalls().stream()
                    .anyMatch(tc -> me.golemcore.bot.tools.PlanFinalizeTool.TOOL_NAME.equals(tc.getName()));
        }

        if (finalizeRequested) {
            context.setAttribute(ContextAttributes.PLAN_FINALIZE_REQUESTED, true);
        }

        return finalizeRequested;
    }

    @Override
    public AgentContext process(AgentContext context) {
        Optional<Plan> activePlan = planService.getActivePlan();
        if (activePlan.isEmpty()) {
            log.warn("[PlanFinalize] Plan work active but no active plan found");
            planService.deactivatePlanMode();
            return context;
        }

        Plan plan = activePlan.get();
        String chatId = context.getSession().getChatId();

        // Empty plan ? cancel and let normal response routing proceed
        if (plan.getSteps() != null && plan.getSteps().isEmpty()) {
            log.info("[PlanFinalize] Empty plan, cancelling");
            planService.cancelPlan(plan.getId());
            return context;
        }

        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        PlanFinalizeArgs finalizeArgs = PlanFinalizeArgs.from(response);
        if (finalizeArgs == null || finalizeArgs.planMarkdown() == null || finalizeArgs.planMarkdown().isBlank()) {
            log.warn("[PlanFinalize] plan_finalize called without plan_markdown");
            return context;
        }

        // Persist canonical markdown draft (SSOT) and move plan state forward.
        planService.finalizePlan(plan.getId(), finalizeArgs.planMarkdown(), finalizeArgs.title());

        // Publish event for Telegram approval UI
        eventPublisher.publishEvent(new PlanReadyEvent(plan.getId(), chatId));
        context.setAttribute(ContextAttributes.PLAN_APPROVAL_NEEDED, plan.getId());

        log.info("[PlanFinalize] Plan '{}' updated and ready for approval", plan.getId());

        return context;
    }

    private record PlanFinalizeArgs(String planMarkdown, String title) {

        @SuppressWarnings("unchecked")
        static PlanFinalizeArgs from(LlmResponse response) {
            if (response == null || response.getToolCalls() == null) {
                return null;
            }
            return response.getToolCalls().stream()
                    .filter(tc -> me.golemcore.bot.tools.PlanFinalizeTool.TOOL_NAME.equals(tc.getName()))
                    .findFirst()
                    .map(tc -> {
                        Map<String, Object> args = tc.getArguments() != null ? tc.getArguments() : Map.of();
                        Object md = args.get("plan_markdown");
                        Object title = args.get("title");
                        return new PlanFinalizeArgs(md instanceof String ? (String) md : null,
                                title instanceof String ? (String) title : null);
                    })
                    .orElse(null);
        }
    }
}
