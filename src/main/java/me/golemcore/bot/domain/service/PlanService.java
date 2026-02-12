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
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PlanStep;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core service for plan mode managing plan lifecycle, steps, and persistence.
 * Single-user design with simplified storage layout following
 * {@link AutoModeService} patterns.
 *
 * <p>
 * Storage layout:
 * <ul>
 * <li>auto/plans.json - list of all plans</li>
 * </ul>
 */
@Service
@Slf4j
public class PlanService {

    private static final String AUTO_DIR = "auto";
    private static final String PLANS_FILE = "plans.json";
    private static final String PLAN_NOT_FOUND = "Plan not found: ";
    private static final String RECOVERY_INTERRUPTED_MSG = "Interrupted by restart/crash during execution";
    private static final TypeReference<List<Plan>> PLAN_LIST_TYPE_REF = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;
    private final BotProperties properties;
    private final Clock clock;

    // In-memory state
    private volatile boolean planModeActive = false;
    private volatile String activePlanId;
    private final AtomicReference<List<Plan>> plansCache = new AtomicReference<>();

    public PlanService(StoragePort storagePort, ObjectMapper objectMapper,
            BotProperties properties, Clock clock) {
        this.storagePort = storagePort;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isFeatureEnabled() {
        return properties.getPlan().isEnabled();
    }

    // ==================== Plan mode state ====================

    public boolean isPlanModeActive() {
        return planModeActive;
    }

    public String getActivePlanId() {
        return activePlanId;
    }

    public void activatePlanMode(String chatId, String modelTier) {
        Plan plan = createPlan(null, null, chatId, modelTier);
        activePlanId = plan.getId();
        planModeActive = true;
        log.info("[PlanMode] Activated, plan: {}", plan.getId());
    }

    @SuppressWarnings("PMD.NullAssignment") // intentional: clearing volatile state means "no active plan"
    public void deactivatePlanMode() {
        planModeActive = false;
        activePlanId = null;
        log.info("[PlanMode] Deactivated");
    }

    // ==================== Plan CRUD ====================

    public Plan createPlan(String title, String description, String chatId, String modelTier) {
        List<Plan> plans = getPlans();

        long activeCount = plans.stream()
                .filter(p -> p.getStatus() == Plan.PlanStatus.COLLECTING
                        || p.getStatus() == Plan.PlanStatus.READY
                        || p.getStatus() == Plan.PlanStatus.APPROVED
                        || p.getStatus() == Plan.PlanStatus.EXECUTING)
                .count();
        if (activeCount >= properties.getPlan().getMaxPlans()) {
            throw new IllegalStateException("Maximum active plans reached: " + properties.getPlan().getMaxPlans());
        }

        Instant now = Instant.now(clock);
        Plan plan = Plan.builder()
                .id(UUID.randomUUID().toString())
                .title(title)
                .description(description)
                .chatId(chatId)
                .modelTier(modelTier)
                .status(Plan.PlanStatus.COLLECTING)
                .createdAt(now)
                .updatedAt(now)
                .build();

        plans.add(plan);
        savePlans(plans);
        log.info("[PlanMode] Created plan '{}' (tier: {})", plan.getId(), modelTier);
        return plan;
    }

    public synchronized List<Plan> getPlans() {
        List<Plan> cached = plansCache.get();
        if (cached == null) {
            cached = loadPlans();
            recoverRuntimeState(cached);
            plansCache.set(cached);
        }
        return cached;
    }

    public Optional<Plan> getPlan(String planId) {
        return getPlans().stream()
                .filter(p -> p.getId().equals(planId))
                .findFirst();
    }

    public Optional<Plan> getActivePlan() {
        if (activePlanId == null) {
            return Optional.empty();
        }
        return getPlan(activePlanId);
    }

    public void deletePlan(String planId) {
        List<Plan> plans = getPlans();
        boolean removed = plans.removeIf(p -> p.getId().equals(planId));
        if (!removed) {
            throw new IllegalArgumentException(PLAN_NOT_FOUND + planId);
        }
        if (planId.equals(activePlanId)) {
            deactivatePlanMode();
        }
        savePlans(plans);
        log.info("[PlanMode] Deleted plan '{}'", planId);
    }

    // ==================== Step management ====================

    public PlanStep addStep(String planId, String toolName, Map<String, Object> args, String description) {
        Plan plan = getPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException(PLAN_NOT_FOUND + planId));

        if (plan.getSteps().size() >= properties.getPlan().getMaxStepsPerPlan()) {
            throw new IllegalStateException(
                    "Maximum steps per plan reached: " + properties.getPlan().getMaxStepsPerPlan());
        }

        Instant now = Instant.now(clock);
        PlanStep step = PlanStep.builder()
                .id(UUID.randomUUID().toString())
                .planId(planId)
                .toolName(toolName)
                .description(description)
                .toolArguments(args)
                .order(plan.getSteps().size())
                .status(PlanStep.StepStatus.PENDING)
                .createdAt(now)
                .build();

        plan.getSteps().add(step);
        plan.setUpdatedAt(now);
        savePlans(getPlans());
        log.debug("[PlanMode] Added step '{}' ({}) to plan '{}'", step.getId(), toolName, planId);
        return step;
    }

    public Optional<PlanStep> getNextPendingStep(String planId) {
        return getPlan(planId)
                .map(Plan::getSteps)
                .orElse(List.of())
                .stream()
                .filter(s -> s.getStatus() == PlanStep.StepStatus.PENDING)
                .min(Comparator.comparingInt(PlanStep::getOrder));
    }

    // ==================== Plan lifecycle ====================

    public void finalizePlan(String planId) {
        Plan plan = getPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException(PLAN_NOT_FOUND + planId));

        if (plan.getStatus() != Plan.PlanStatus.COLLECTING) {
            throw new IllegalStateException(
                    "Can only finalize plans in COLLECTING state, current: " + plan.getStatus());
        }

        plan.setStatus(Plan.PlanStatus.READY);
        plan.setUpdatedAt(Instant.now(clock));
        deactivatePlanMode();
        savePlans(getPlans());
        log.info("[PlanMode] Plan '{}' finalized ({} steps)", planId, plan.getSteps().size());
    }

    public void approvePlan(String planId) {
        Plan plan = getPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException(PLAN_NOT_FOUND + planId));

        if (plan.getStatus() != Plan.PlanStatus.READY) {
            throw new IllegalStateException("Can only approve plans in READY state, current: " + plan.getStatus());
        }

        plan.setStatus(Plan.PlanStatus.APPROVED);
        plan.setUpdatedAt(Instant.now(clock));
        savePlans(getPlans());
        log.info("[PlanMode] Plan '{}' approved", planId);
    }

    public void cancelPlan(String planId) {
        Plan plan = getPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException(PLAN_NOT_FOUND + planId));

        plan.setStatus(Plan.PlanStatus.CANCELLED);
        plan.setUpdatedAt(Instant.now(clock));
        if (planId.equals(activePlanId)) {
            deactivatePlanMode();
        }
        savePlans(getPlans());
        log.info("[PlanMode] Plan '{}' cancelled", planId);
    }

    public void markPlanExecuting(String planId) {
        Plan plan = getPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException(PLAN_NOT_FOUND + planId));

        plan.setStatus(Plan.PlanStatus.EXECUTING);
        plan.setUpdatedAt(Instant.now(clock));
        savePlans(getPlans());
    }

    public void completePlan(String planId) {
        Plan plan = getPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException(PLAN_NOT_FOUND + planId));

        plan.setStatus(Plan.PlanStatus.COMPLETED);
        plan.setUpdatedAt(Instant.now(clock));
        savePlans(getPlans());
        log.info("[PlanMode] Plan '{}' completed ({}/{} steps)",
                planId, plan.getCompletedStepCount(), plan.getSteps().size());
    }

    public void markPlanPartiallyCompleted(String planId) {
        Plan plan = getPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException(PLAN_NOT_FOUND + planId));

        plan.setStatus(Plan.PlanStatus.PARTIALLY_COMPLETED);
        plan.setUpdatedAt(Instant.now(clock));
        savePlans(getPlans());
        log.info("[PlanMode] Plan '{}' partially completed ({}/{} steps, {} failed)",
                planId, plan.getCompletedStepCount(), plan.getSteps().size(), plan.getFailedStepCount());
    }

    // ==================== Step status ====================

    public void markStepInProgress(String planId, String stepId) {
        PlanStep step = findStep(planId, stepId);
        step.setStatus(PlanStep.StepStatus.IN_PROGRESS);
        savePlans(getPlans());
    }

    public void markStepCompleted(String planId, String stepId, String result) {
        PlanStep step = findStep(planId, stepId);
        step.setStatus(PlanStep.StepStatus.COMPLETED);
        step.setResult(result);
        step.setExecutedAt(Instant.now(clock));
        savePlans(getPlans());
    }

    public void markStepFailed(String planId, String stepId, String error) {
        PlanStep step = findStep(planId, stepId);
        step.setStatus(PlanStep.StepStatus.FAILED);
        step.setResult(error);
        step.setExecutedAt(Instant.now(clock));
        savePlans(getPlans());
    }

    private PlanStep findStep(String planId, String stepId) {
        Plan plan = getPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException(PLAN_NOT_FOUND + planId));
        return plan.getSteps().stream()
                .filter(s -> s.getId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Step not found: " + stepId));
    }

    // ==================== Context building ====================

    /**
     * Builds markdown context for system prompt injection during plan collection.
     */
    public String buildPlanContext() {
        Optional<Plan> activePlan = getActivePlan();
        if (activePlan.isEmpty()) {
            return null;
        }

        Plan plan = activePlan.get();
        StringBuilder sb = new StringBuilder();
        sb.append("# Plan Mode\n\n");
        sb.append("You are in PLAN MODE. Tool calls will be collected as plan steps, NOT executed immediately.\n");
        sb.append(
                "Continue proposing tool calls to build the plan. When done, respond with a summary of the plan.\n\n");

        if (!plan.getSteps().isEmpty()) {
            sb.append("## Collected Steps (").append(plan.getSteps().size()).append(")\n");
            for (int i = 0; i < plan.getSteps().size(); i++) {
                PlanStep step = plan.getSteps().get(i);
                sb.append(String.format("%d. **%s** — %s%n", i + 1, step.getToolName(),
                        step.getDescription() != null ? step.getDescription() : ""));
            }
            sb.append("\n");
        }

        sb.append("## Instructions\n");
        sb.append("1. Propose tool calls for the remaining steps of your plan\n");
        sb.append("2. Each tool call will be recorded but NOT executed\n");
        sb.append("3. When the plan is complete, respond with a text summary (no tool calls)\n");
        sb.append("4. The user will review and approve the plan before execution\n");

        return sb.toString();
    }

    private synchronized void recoverRuntimeState(List<Plan> plans) {
        recoverActivePlanMode(plans);

        boolean changed = false;
        for (Plan plan : plans) {
            if (plan.getStatus() == Plan.PlanStatus.EXECUTING) {
                recoverExecutingPlan(plan);
                changed = true;
            }
        }

        if (changed) {
            savePlans(plans);
        }
    }

    private void recoverActivePlanMode(List<Plan> plans) {
        if (activePlanId != null && planModeActive) {
            return;
        }
        plans.stream()
                .filter(p -> p.getStatus() == Plan.PlanStatus.COLLECTING)
                .reduce((first, second) -> second)
                .ifPresent(plan -> {
                    activePlanId = plan.getId();
                    planModeActive = true;
                    log.info("[PlanMode] Recovered active collecting plan '{}' after restart", activePlanId);
                });
    }

    private void recoverExecutingPlan(Plan plan) {
        plan.setStatus(Plan.PlanStatus.PARTIALLY_COMPLETED);
        plan.setUpdatedAt(Instant.now(clock));
        for (PlanStep step : plan.getSteps()) {
            recoverInterruptedStep(step);
        }
        log.warn("[PlanMode] Recovered stale EXECUTING plan '{}' as PARTIALLY_COMPLETED", plan.getId());
    }

    private void recoverInterruptedStep(PlanStep step) {
        if (step.getStatus() != PlanStep.StepStatus.IN_PROGRESS) {
            return;
        }
        step.setStatus(PlanStep.StepStatus.FAILED);
        if (step.getResult() == null || step.getResult().isBlank()) {
            step.setResult(RECOVERY_INTERRUPTED_MSG);
        }
        if (step.getExecutedAt() == null) {
            step.setExecutedAt(Instant.now(clock));
        }
    }

    // ==================== Persistence ====================

    private void savePlans(List<Plan> plans) {
        try {
            String json = objectMapper.writeValueAsString(plans);
            storagePort.putText(AUTO_DIR, PLANS_FILE, json).join();
            plansCache.set(plans);
        } catch (Exception e) {
            log.error("[PlanMode] Failed to save plans", e);
            throw new IllegalStateException("Failed to persist plans", e);
        }
    }

    private List<Plan> loadPlans() {
        try {
            String json = storagePort.getText(AUTO_DIR, PLANS_FILE).join();
            if (json != null && !json.isBlank()) {
                return new ArrayList<>(objectMapper.readValue(json, PLAN_LIST_TYPE_REF));
            }
        } catch (IOException | RuntimeException e) { // NOSONAR - intentionally catch all for fallback
            log.debug("[PlanMode] No plans found or failed to parse: {}", e.getMessage());
        }
        return new ArrayList<>();
    }
}
