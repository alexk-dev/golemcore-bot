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
import me.golemcore.bot.domain.model.PlanStep;
import me.golemcore.bot.domain.service.PlanService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Detects when the LLM has finished proposing plan steps (responds with text,
 * no tool calls) and finalizes the plan (order=58, before ResponseRouting at
 * 60).
 *
 * <p>
 * When the LLM produces a text response without tool calls during plan mode:
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

        // Only finalize when LLM responds with text (no tool calls)
        List<?> toolCalls = context.getAttribute("llm.toolCalls");
        if (toolCalls != null && !toolCalls.isEmpty()) {
            return false;
        }

        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        return response != null && response.getContent() != null && !response.getContent().isBlank();
    }

    @Override
    public AgentContext process(AgentContext context) {
        Optional<Plan> activePlan = planService.getActivePlan();
        if (activePlan.isEmpty()) {
            log.warn("[PlanFinalize] Plan mode active but no active plan found");
            planService.deactivatePlanMode();
            return context;
        }

        Plan plan = activePlan.get();
        String chatId = context.getSession().getChatId();

        // Empty plan — cancel and let normal response routing proceed
        if (plan.getSteps().isEmpty()) {
            log.info("[PlanFinalize] Empty plan, cancelling");
            planService.cancelPlan(plan.getId());
            return context;
        }

        // Finalize the plan
        planService.finalizePlan(plan.getId());

        // Append plan summary to LLM response
        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        String summary = buildPlanSummary(plan);
        String updatedContent = response.getContent() + "\n\n" + summary;
        response.setContent(updatedContent);

        // Publish event for Telegram approval UI
        eventPublisher.publishEvent(new PlanReadyEvent(plan.getId(), chatId));
        context.setAttribute(ContextAttributes.PLAN_APPROVAL_NEEDED, plan.getId());

        log.info("[PlanFinalize] Plan '{}' ready for approval ({} steps)", plan.getId(), plan.getSteps().size());

        return context;
    }

    private String buildPlanSummary(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("**Plan** `").append(plan.getId().substring(0, 8)).append("` ")
                .append("(").append(plan.getSteps().size()).append(" steps):\n");
        for (int i = 0; i < plan.getSteps().size(); i++) {
            PlanStep step = plan.getSteps().get(i);
            sb.append(String.format("%d. `%s`", i + 1, step.getToolName()));
            if (step.getDescription() != null && !step.getDescription().isBlank()) {
                sb.append(" — ").append(step.getDescription());
            }
            sb.append("\n");
        }

        sb.append("\n_Waiting for approval..._\n");
        sb.append("\nQuick commands:\n");
        sb.append("- Approve: `/plan approve `").append(plan.getId()).append("\n");
        sb.append("- Cancel (reset): `/plan cancel `").append(plan.getId()).append("\n");
        sb.append("- Show status: `/plan status`\n");
        sb.append("- List all plans: `/plans`\n");
        return sb.toString();
    }
}
