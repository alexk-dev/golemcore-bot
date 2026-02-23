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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PlanExecutionCompletedEvent;
import me.golemcore.bot.domain.model.PlanStep;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.ToolCatalogPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Executes approved plans by running each step's tool call in order. Handles
 * step failures, resume from partially completed plans, and publishes execution
 * summary events for channel adapters to deliver.
 */
@Service
@Slf4j
public class PlanExecutionService {

    private static final long TOOL_TIMEOUT_SECONDS = 30;
    private static final int MAX_RESULT_DISPLAY_LENGTH = 200;

    private final PlanService planService;
    private final Map<String, ToolComponent> toolRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final BotProperties properties;

    public PlanExecutionService(PlanService planService,
            ToolCatalogPort pluginToolCatalog,
            ApplicationEventPublisher eventPublisher,
            BotProperties properties) {
        this.planService = planService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;

        this.toolRegistry = new ConcurrentHashMap<>();
        for (ToolComponent tool : pluginToolCatalog.getAllTools()) {
            toolRegistry.put(tool.getToolName(), tool);
        }
    }

    /**
     * Executes an approved plan asynchronously. Iterates through steps in order,
     * executing each tool call and recording results.
     */
    public CompletableFuture<Void> executePlan(String planId) {
        return CompletableFuture.runAsync(() -> doExecutePlan(planId));
    }

    /**
     * Resumes a partially completed plan from the first pending step.
     */
    public CompletableFuture<Void> resumePlan(String planId) {
        Plan plan = planService.getPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));

        if (plan.getStatus() != Plan.PlanStatus.PARTIALLY_COMPLETED) {
            throw new IllegalStateException("Can only resume PARTIALLY_COMPLETED plans, current: " + plan.getStatus());
        }

        planService.approvePlan(planId);
        return executePlan(planId);
    }

    private void doExecutePlan(String planId) {
        Plan plan = planService.getPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));

        if (plan.getStatus() != Plan.PlanStatus.APPROVED) {
            log.warn("[PlanExec] Plan '{}' is not approved (status: {})", planId, plan.getStatus());
            return;
        }

        planService.markPlanExecuting(planId);
        log.info("[PlanExec] Starting execution of plan '{}' ({} steps)", planId, plan.getSteps().size());

        boolean stopOnFailure = properties.getPlan().isStopOnFailure();
        boolean hasFailure = false;

        List<PlanStep> sortedSteps = plan.getSteps().stream()
                .sorted(Comparator.comparingInt(PlanStep::getOrder))
                .toList();

        for (PlanStep step : sortedSteps) {
            if (step.getStatus() == PlanStep.StepStatus.COMPLETED
                    || step.getStatus() == PlanStep.StepStatus.SKIPPED) {
                continue; // Already done (resume case)
            }

            planService.markStepInProgress(planId, step.getId());

            ToolComponent tool = toolRegistry.get(step.getToolName());
            if (tool == null || !tool.isEnabled()) {
                String error = tool == null
                        ? "Tool not found: " + step.getToolName()
                        : "Tool is disabled: " + step.getToolName();
                planService.markStepFailed(planId, step.getId(), error);
                log.warn("[PlanExec] Step {} failed: {}", step.getOrder(), error);
                hasFailure = true;
                if (stopOnFailure) {
                    break;
                }
                continue;
            }

            try {
                log.info("[PlanExec] Executing step {}: {} ({})",
                        step.getOrder(), step.getToolName(), step.getDescription());

                CompletableFuture<ToolResult> future = tool.execute(step.getToolArguments());
                ToolResult result = future.get(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                if (result.isSuccess()) {
                    String output = truncate(result.getOutput(), MAX_RESULT_DISPLAY_LENGTH);
                    planService.markStepCompleted(planId, step.getId(), output);
                    log.info("[PlanExec] Step {} completed: {}", step.getOrder(), output);
                } else {
                    String error = result.getError() != null ? result.getError() : "Unknown error";
                    planService.markStepFailed(planId, step.getId(), error);
                    log.warn("[PlanExec] Step {} failed: {}", step.getOrder(), error);
                    hasFailure = true;
                    if (stopOnFailure) {
                        break;
                    }
                }
            } catch (Exception e) { // NOSONAR - intentionally catch all for tool execution
                String error = "Execution failed: " + e.getMessage();
                planService.markStepFailed(planId, step.getId(), error);
                log.error("[PlanExec] Step {} exception", step.getOrder(), e);
                hasFailure = true;
                if (stopOnFailure) {
                    break;
                }
            }
        }

        // Determine final status
        if (hasFailure && stopOnFailure) {
            planService.markPlanPartiallyCompleted(planId);
        } else {
            planService.completePlan(planId);
        }

        // Publish summary event for channel adapters
        publishExecutionSummary(plan);
    }

    private void publishExecutionSummary(Plan plan) {
        String deliveryChatId = plan.getTransportChatId() != null
                ? plan.getTransportChatId()
                : plan.getChatId();
        if (deliveryChatId == null) {
            return;
        }

        String summary = buildExecutionSummary(plan);
        eventPublisher.publishEvent(new PlanExecutionCompletedEvent(plan.getId(), deliveryChatId, summary));
    }

    String buildExecutionSummary(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Plan Execution ").append(plan.getStatus() == Plan.PlanStatus.COMPLETED ? "Complete" : "Stopped")
                .append("**\n\n");

        List<PlanStep> sortedSteps = plan.getSteps().stream()
                .sorted(Comparator.comparingInt(PlanStep::getOrder))
                .toList();

        for (PlanStep step : sortedSteps) {
            String icon = switch (step.getStatus()) {
            case COMPLETED -> "\u2705";
            case FAILED -> "\u274C";
            case PENDING -> "\u23F3";
            case IN_PROGRESS -> "\u25B6\uFE0F";
            case SKIPPED -> "\u23ED\uFE0F";
            };
            sb.append(icon).append(" `").append(step.getToolName()).append("`");
            if (step.getDescription() != null) {
                sb.append(" — ").append(step.getDescription());
            }
            if (step.getResult() != null && !step.getResult().isBlank()) {
                sb.append("\n   ").append(truncate(step.getResult(), 100));
            }
            sb.append("\n");
        }

        long completed = plan.getCompletedStepCount();
        long failed = plan.getFailedStepCount();
        sb.append(String.format("%n%d/%d completed", completed, plan.getSteps().size()));
        if (failed > 0) {
            sb.append(String.format(", %d failed", failed));
        }
        if (plan.getStatus() == Plan.PlanStatus.PARTIALLY_COMPLETED) {
            sb.append("\nUse `/plan resume` to continue from failed step.");
        }

        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
