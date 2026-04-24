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
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.SessionIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ephemeral plan-mode state.
 *
 * <p>
 * Plan mode is now an always-available, in-process planning aid. Durable work
 * tracking belongs to session-scoped goals/tasks, not to a separate plan store.
 */
@Service
@Slf4j
public class PlanService {

    private static final String PLAN_NOT_FOUND = "Plan not found: ";
    private static final String LEGACY_CHANNEL = "legacy";
    private static final String NO_ACTIVE_PLAN_ID = "";
    private static final String PLAN_FILE_DIRECTORY = ".golemcore/plans";

    private final Clock clock;
    private final RuntimeConfigService runtimeConfigService;
    private final Map<String, String> activePlanIdBySession = new HashMap<>();
    private final Map<String, String> executionPlanFileBySession = new HashMap<>();
    private final List<Plan> plans = new ArrayList<>();
    private volatile boolean legacyPlanWorkActive = false;
    private volatile String legacyActivePlanId = NO_ACTIVE_PLAN_ID;
    private volatile String legacyExecutionPlanFilePath;

    public PlanService(Clock clock) {
        this(clock, null);
    }

    @Autowired
    public PlanService(Clock clock, RuntimeConfigService runtimeConfigService) {
        this.clock = clock;
        this.runtimeConfigService = runtimeConfigService;
    }

    public boolean isFeatureEnabled() {
        return true;
    }

    public boolean isPlanModeActive() {
        return legacyPlanWorkActive;
    }

    public boolean isPlanModeActive(SessionIdentity sessionIdentity) {
        return getActivePlanIdOptional(sessionIdentity).isPresent();
    }

    public String getActivePlanId() {
        return normalizeLegacyActivePlanId(legacyActivePlanId);
    }

    public String getActivePlanId(SessionIdentity sessionIdentity) {
        return getActivePlanIdOptional(sessionIdentity).orElse(null);
    }

    public Optional<String> getActivePlanIdOptional() {
        return Optional.ofNullable(getActivePlanId());
    }

    public Optional<String> getActivePlanIdOptional(SessionIdentity sessionIdentity) {
        SessionIdentity normalized = normalizeSessionIdentityOrNull(sessionIdentity);
        if (normalized == null) {
            return Optional.empty();
        }
        synchronized (activePlanIdBySession) {
            return Optional.ofNullable(activePlanIdBySession.get(normalized.asKey()));
        }
    }

    public void activatePlanMode(String chatId, String modelTier) {
        Plan plan = createPlan(null, null, chatId, resolvePlanModelTier(modelTier));
        legacyActivePlanId = plan.getId();
        legacyPlanWorkActive = true;
        log.info("[PlanMode] Activated ephemeral legacy plan: {}", plan.getId());
    }

    public void activatePlanMode(SessionIdentity sessionIdentity, String transportChatId, String modelTier) {
        SessionIdentity normalized = requireSessionIdentity(sessionIdentity);
        Plan plan = createPlan(null, null, normalized, transportChatId, resolvePlanModelTier(modelTier));
        synchronized (activePlanIdBySession) {
            activePlanIdBySession.put(normalized.asKey(), plan.getId());
        }
        log.info("[PlanMode] Activated ephemeral session={} plan={}", normalized.asKey(), plan.getId());
    }

    public void deactivatePlanMode() {
        markPlanInactive(legacyActivePlanId);
        legacyPlanWorkActive = false;
        legacyActivePlanId = NO_ACTIVE_PLAN_ID;
        log.info("[PlanMode] Deactivated legacy plan mode");
    }

    public void deactivatePlanMode(SessionIdentity sessionIdentity) {
        SessionIdentity normalized = normalizeSessionIdentityOrNull(sessionIdentity);
        if (normalized == null) {
            return;
        }
        String removedPlanId;
        synchronized (activePlanIdBySession) {
            removedPlanId = activePlanIdBySession.remove(normalized.asKey());
        }
        markPlanInactive(removedPlanId);
        log.info("[PlanMode] Deactivated session={}", normalized.asKey());
    }

    public void completePlanMode() {
        String planFilePath = getActivePlanFilePath().orElse(null);
        deactivatePlanMode();
        legacyExecutionPlanFilePath = planFilePath;
    }

    public void completePlanMode(SessionIdentity sessionIdentity) {
        SessionIdentity normalized = normalizeSessionIdentityOrNull(sessionIdentity);
        if (normalized == null) {
            return;
        }
        String planFilePath = getActivePlanFilePath(normalized).orElse(null);
        deactivatePlanMode(normalized);
        if (!StringValueSupport.isBlank(planFilePath)) {
            synchronized (executionPlanFileBySession) {
                executionPlanFileBySession.put(normalized.asKey(), planFilePath);
            }
        }
    }

    public Plan createPlan(String title, String description, String chatId, String modelTier) {
        Instant now = Instant.now(clock);
        Plan plan = Plan.builder()
                .id(UUID.randomUUID().toString())
                .title(title)
                .description(description)
                .channelType(LEGACY_CHANNEL)
                .chatId(chatId)
                .transportChatId(chatId)
                .modelTier(resolvePlanModelTier(modelTier))
                .status(Plan.PlanStatus.COLLECTING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        synchronized (plans) {
            plans.add(plan);
        }
        return plan;
    }

    public Plan createPlan(String title, String description, SessionIdentity sessionIdentity,
            String transportChatId, String modelTier) {
        SessionIdentity normalized = requireSessionIdentity(sessionIdentity);
        Instant now = Instant.now(clock);
        String resolvedTransportChatId = !StringValueSupport.isBlank(transportChatId)
                ? transportChatId
                : normalized.conversationKey();

        Plan plan = Plan.builder()
                .id(UUID.randomUUID().toString())
                .title(title)
                .description(description)
                .channelType(normalized.channelType())
                .chatId(normalized.conversationKey())
                .transportChatId(resolvedTransportChatId)
                .modelTier(resolvePlanModelTier(modelTier))
                .status(Plan.PlanStatus.COLLECTING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        synchronized (plans) {
            plans.add(plan);
        }
        return plan;
    }

    public List<Plan> getPlans() {
        synchronized (plans) {
            return List.copyOf(plans);
        }
    }

    public List<Plan> getPlans(SessionIdentity sessionIdentity) {
        SessionIdentity normalized = normalizeSessionIdentityOrNull(sessionIdentity);
        if (normalized == null) {
            return List.of();
        }
        return getPlans().stream()
                .filter(plan -> belongsToSession(plan, normalized))
                .toList();
    }

    public Optional<Plan> getPlan(String planId) {
        if (StringValueSupport.isBlank(planId)) {
            return Optional.empty();
        }
        return getPlans().stream()
                .filter(plan -> planId.equals(plan.getId()))
                .findFirst();
    }

    public Optional<Plan> getPlan(String planId, SessionIdentity sessionIdentity) {
        SessionIdentity normalized = normalizeSessionIdentityOrNull(sessionIdentity);
        if (normalized == null) {
            return Optional.empty();
        }
        return getPlan(planId).filter(plan -> belongsToSession(plan, normalized));
    }

    public Optional<Plan> getActivePlan() {
        String activePlanId = getActivePlanId();
        if (!legacyPlanWorkActive || activePlanId == null) {
            return Optional.empty();
        }
        return getPlan(activePlanId);
    }

    public Optional<Plan> getActivePlan(SessionIdentity sessionIdentity) {
        return getActivePlanIdOptional(sessionIdentity).flatMap(planId -> getPlan(planId, sessionIdentity));
    }

    public Optional<String> getActivePlanFilePath() {
        return getActivePlanIdOptional().map(this::planFilePath);
    }

    public Optional<String> getActivePlanFilePath(SessionIdentity sessionIdentity) {
        return getActivePlanIdOptional(sessionIdentity).map(this::planFilePath);
    }

    public boolean hasPendingExecutionContext() {
        return !StringValueSupport.isBlank(legacyExecutionPlanFilePath);
    }

    public boolean hasPendingExecutionContext(SessionIdentity sessionIdentity) {
        SessionIdentity normalized = normalizeSessionIdentityOrNull(sessionIdentity);
        if (normalized == null) {
            return false;
        }
        synchronized (executionPlanFileBySession) {
            return executionPlanFileBySession.containsKey(normalized.asKey());
        }
    }

    public String peekExecutionContext() {
        return buildExecutionContext(legacyExecutionPlanFilePath);
    }

    public String peekExecutionContext(SessionIdentity sessionIdentity) {
        SessionIdentity normalized = normalizeSessionIdentityOrNull(sessionIdentity);
        if (normalized == null) {
            return null;
        }
        String planFilePath;
        synchronized (executionPlanFileBySession) {
            planFilePath = executionPlanFileBySession.get(normalized.asKey());
        }
        return buildExecutionContext(planFilePath);
    }

    public String consumeExecutionContext() {
        String planFilePath = legacyExecutionPlanFilePath;
        legacyExecutionPlanFilePath = NO_ACTIVE_PLAN_ID;
        return buildExecutionContext(planFilePath);
    }

    public String consumeExecutionContext(SessionIdentity sessionIdentity) {
        SessionIdentity normalized = normalizeSessionIdentityOrNull(sessionIdentity);
        if (normalized == null) {
            return null;
        }
        String planFilePath;
        synchronized (executionPlanFileBySession) {
            planFilePath = executionPlanFileBySession.remove(normalized.asKey());
        }
        return buildExecutionContext(planFilePath);
    }

    public boolean hasActivePlans(SessionIdentity sessionIdentity) {
        SessionIdentity normalized = normalizeSessionIdentityOrNull(sessionIdentity);
        if (normalized == null) {
            return false;
        }
        return getPlans(normalized).stream().anyMatch(this::isActivePlanStatus);
    }

    public void deletePlan(String planId) {
        boolean removed;
        synchronized (plans) {
            removed = plans.removeIf(plan -> planId != null && planId.equals(plan.getId()));
        }
        if (!removed) {
            throw new IllegalArgumentException(PLAN_NOT_FOUND + planId);
        }
        clearActiveMappingForPlan(planId);
    }

    public void cancelPlan(String planId) {
        Plan plan = getPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException(PLAN_NOT_FOUND + planId));
        plan.setStatus(Plan.PlanStatus.CANCELLED);
        plan.setUpdatedAt(Instant.now(clock));
        clearActiveMappingForPlan(planId);
    }

    public String buildPlanContext() {
        return legacyPlanWorkActive ? buildEphemeralPlanContext(getActivePlanFilePath().orElse(null)) : null;
    }

    public String buildPlanContext(SessionIdentity sessionIdentity) {
        return isPlanModeActive(sessionIdentity)
                ? buildEphemeralPlanContext(getActivePlanFilePath(sessionIdentity).orElse(null))
                : null;
    }

    private String buildEphemeralPlanContext(String activePlanFilePath) {
        String planFilePath = !StringValueSupport.isBlank(activePlanFilePath)
                ? activePlanFilePath
                : PLAN_FILE_DIRECTORY + "/current.md";
        return String.join(
                System.lineSeparator(),
                "# Plan Mode",
                "",
                "Plan mode is ACTIVE for this conversation.",
                "- Inspect, reason, and present a concise execution plan before implementation.",
                "- Do not run shell commands or mutate workspace files while Plan Mode is active.",
                "- If a plan note file is available, use `%s`; otherwise keep the plan in the conversation."
                        .formatted(planFilePath),
                "- Do not persist plan state in Plan Mode; it is an ephemeral reasoning mode for the current runtime only.",
                "- Use session goals/tasks when durable tracking, task status, diary/history, or follow-up automation is needed.",
                "- Do not rely on separate plan storage tools; Plan Mode has no separate plan storage.",
                "- Ask a clarifying question when requirements or tradeoffs are still ambiguous.",
                "- When the plan is ready, call `plan_exit` as the final tool call for the turn.",
                "- Include the complete user-visible plan in the `plan_markdown` argument of `plan_exit`.")
                .trim();
    }

    private void clearActiveMappingForPlan(String planId) {
        synchronized (activePlanIdBySession) {
            activePlanIdBySession.entrySet().removeIf(entry -> planId != null && planId.equals(entry.getValue()));
        }
        if (planId != null && planId.equals(getActivePlanId())) {
            deactivatePlanMode();
        }
    }

    private void markPlanInactive(String planId) {
        if (StringValueSupport.isBlank(planId)) {
            return;
        }
        getPlan(planId).ifPresent(plan -> {
            if (isActivePlanStatus(plan)) {
                plan.setStatus(Plan.PlanStatus.CANCELLED);
                plan.setUpdatedAt(Instant.now(clock));
            }
        });
    }

    private boolean isActivePlanStatus(Plan plan) {
        if (plan == null) {
            return false;
        }
        return plan.getStatus() == Plan.PlanStatus.COLLECTING
                || plan.getStatus() == Plan.PlanStatus.READY
                || plan.getStatus() == Plan.PlanStatus.APPROVED
                || plan.getStatus() == Plan.PlanStatus.EXECUTING;
    }

    private SessionIdentity resolvePlanSessionIdentity(Plan plan) {
        if (plan == null || StringValueSupport.isBlank(plan.getChatId())) {
            return null;
        }
        String channelType = StringValueSupport.isBlank(plan.getChannelType())
                ? LEGACY_CHANNEL
                : plan.getChannelType();
        return SessionIdentitySupport.resolveSessionIdentity(channelType, plan.getChatId());
    }

    private boolean belongsToSession(Plan plan, SessionIdentity sessionIdentity) {
        SessionIdentity planIdentity = resolvePlanSessionIdentity(plan);
        if (planIdentity == null) {
            return false;
        }
        if (!sessionIdentity.conversationKey().equals(planIdentity.conversationKey())) {
            return false;
        }
        return LEGACY_CHANNEL.equals(planIdentity.channelType())
                || sessionIdentity.channelType().equals(planIdentity.channelType());
    }

    private SessionIdentity requireSessionIdentity(SessionIdentity sessionIdentity) {
        SessionIdentity normalized = normalizeSessionIdentityOrNull(sessionIdentity);
        if (normalized == null) {
            throw new IllegalArgumentException("Session identity is required");
        }
        return normalized;
    }

    private SessionIdentity normalizeSessionIdentityOrNull(SessionIdentity sessionIdentity) {
        if (sessionIdentity == null
                || StringValueSupport.isBlank(sessionIdentity.channelType())
                || StringValueSupport.isBlank(sessionIdentity.conversationKey())) {
            return null;
        }
        return SessionIdentitySupport.resolveSessionIdentity(
                sessionIdentity.channelType().toLowerCase(Locale.ROOT),
                sessionIdentity.conversationKey());
    }

    private String normalizeLegacyActivePlanId(String planId) {
        return StringValueSupport.isBlank(planId) ? null : planId;
    }

    private String resolvePlanModelTier(String requestedModelTier) {
        String explicitTier = normalizePlanModelTier(requestedModelTier);
        if (explicitTier != null) {
            return explicitTier;
        }
        return runtimeConfigService != null ? runtimeConfigService.getPlanModelTier() : null;
    }

    private String normalizePlanModelTier(String modelTier) {
        String normalizedTier = ModelTierCatalog.normalizeTierId(modelTier);
        if (normalizedTier == null || "default".equals(normalizedTier)) {
            return null;
        }
        return normalizedTier;
    }

    private String planFilePath(String planId) {
        return PLAN_FILE_DIRECTORY + "/" + planId + ".md";
    }

    private String buildExecutionContext(String planFilePath) {
        if (StringValueSupport.isBlank(planFilePath)) {
            return null;
        }
        return String.join(
                System.lineSeparator(),
                "# Plan Mode Complete",
                "",
                "Plan mode has ended. The requested plan file path was `%s`.".formatted(planFilePath),
                "If the user asks to execute the plan, read that file first if it exists; otherwise use the"
                        + " conversation context and proceed in normal execution mode.")
                .trim();
    }
}
