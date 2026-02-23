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
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Detects when plan mode should be finalized (via plan_set_content tool call)
 * and finalizes the plan (order=58, before ResponseRouting at 60).
 *
 * <p>
 * When the LLM invokes plan_set_content during plan mode:
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

    private static final String TOOL_PLAN_SET_CONTENT = "plan_set_content";

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
        SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(context.getSession());
        boolean planModeActive = sessionIdentity != null
                ? planService.isPlanModeActive(sessionIdentity)
                : planService.isPlanModeActive();
        if (!planModeActive) {
            return false;
        }

        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        if (response == null) {
            return false;
        }

        boolean finalizeRequested = false;
        if (response.getToolCalls() != null) {
            finalizeRequested = response.getToolCalls().stream()
                    .anyMatch(tc -> TOOL_PLAN_SET_CONTENT.equals(tc.getName()));
        }
        return finalizeRequested
                || Boolean.TRUE.equals(context.getAttribute(ContextAttributes.PLAN_SET_CONTENT_REQUESTED));
    }

    @Override
    public AgentContext process(AgentContext context) {
        SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(context.getSession());
        Optional<Plan> activePlan = sessionIdentity != null
                ? planService.getActivePlan(sessionIdentity)
                : planService.getActivePlan();
        if (activePlan.isEmpty()) {
            log.warn("[PlanSetContent] Plan work active but no active plan found");
            if (sessionIdentity != null) {
                planService.deactivatePlanMode(sessionIdentity);
            } else {
                planService.deactivatePlanMode();
            }
            return context;
        }

        Plan plan = activePlan.get();
        String chatId = SessionIdentitySupport.resolveTransportChatId(context.getSession());

        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        PlanSetContentArgs finalizeArgs = PlanSetContentArgs.from(response, context);
        if (finalizeArgs == null || finalizeArgs.planMarkdown() == null || finalizeArgs.planMarkdown().isBlank()) {
            log.warn("[PlanSetContent] plan_set_content called without plan_markdown");
            return context;
        }

        // Persist canonical markdown draft (SSOT) and move plan state forward.
        // Note: for EXECUTING plans PlanService can create a new revision with a new
        // id.
        planService.finalizePlan(plan.getId(), finalizeArgs.planMarkdown(), finalizeArgs.title());

        // Publish event for Telegram approval UI using the current active plan id
        // (important for EXECUTING -> READY revision flow).
        Optional<Plan> readyPlan = sessionIdentity != null
                ? planService.getActivePlan(sessionIdentity)
                : planService.getActivePlan();
        String readyPlanId = readyPlan.map(Plan::getId).orElse(plan.getId());
        eventPublisher.publishEvent(new PlanReadyEvent(readyPlanId, chatId));
        context.setAttribute(ContextAttributes.PLAN_APPROVAL_NEEDED, readyPlanId);

        log.info("[PlanSetContent] Plan '{}' updated and ready for approval", readyPlanId);

        return context;
    }

    private record PlanSetContentArgs(String planMarkdown, String title) {

        @SuppressWarnings("unchecked")
        static PlanSetContentArgs from(LlmResponse response, AgentContext context) {
            if (response == null || response.getToolCalls() == null) {
                return null;
            }
            return response.getToolCalls().stream()
                    .filter(tc -> TOOL_PLAN_SET_CONTENT.equals(tc.getName()))
                    .findFirst()
                    .map(tc -> {
                        Map<String, Object> args = tc.getArguments() != null ? tc.getArguments() : Map.of();
                        Object md = args.get("plan_markdown");
                        if (!(md instanceof String) && context != null && context.getToolResults() != null) {
                            ToolResult tr = context.getToolResults().get(tc.getId() != null ? tc.getId() : tc.getName());
                            if (tr != null && tr.isSuccess() && tr.getOutput() != null) {
                                md = tr.getOutput();
                            }
                        }
                        Object title = args.get("title");
                        return new PlanSetContentArgs(md instanceof String ? (String) md : null,
                                title instanceof String ? (String) title : null);
                    })
                    .orElse(null);
        }
    }
}
