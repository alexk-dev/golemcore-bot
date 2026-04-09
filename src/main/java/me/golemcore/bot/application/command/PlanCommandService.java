package me.golemcore.bot.application.command;

import java.util.List;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.service.PlanExecutionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlanCommandService {

    private final PlanService planService;
    private final PlanExecutionService planExecutionService;
    private final RuntimeConfigService runtimeConfigService;

    public boolean isFeatureEnabled() {
        return planService.isFeatureEnabled();
    }

    public void resetPlanMode(SessionIdentity sessionIdentity) {
        if (!planService.isFeatureEnabled()) {
            return;
        }
        if (sessionIdentity == null) {
            if (planService.isPlanModeActive()) {
                planService.getActivePlanIdOptional().ifPresent(planService::cancelPlan);
                planService.deactivatePlanMode();
            }
            return;
        }
        if (planService.isPlanModeActive(sessionIdentity)) {
            planService.getActivePlanIdOptional(sessionIdentity).ifPresent(planService::cancelPlan);
            planService.deactivatePlanMode(sessionIdentity);
        }
    }

    public PlanModeOutcome getModeStatus(SessionIdentity sessionIdentity) {
        if (!planService.isFeatureEnabled()) {
            return new FeatureUnavailable();
        }
        boolean active = sessionIdentity != null
                ? planService.isPlanModeActive(sessionIdentity)
                : planService.isPlanModeActive();
        return new ModeStatus(active);
    }

    public PlanModeOutcome enablePlanMode(SessionIdentity sessionIdentity, String transportChatId, String modelTier) {
        if (!planService.isFeatureEnabled()) {
            return new FeatureUnavailable();
        }
        if (sessionIdentity == null && planService.isPlanModeActive()) {
            return new AlreadyActive();
        }
        if (sessionIdentity != null && planService.isPlanModeActive(sessionIdentity)) {
            return new AlreadyActive();
        }

        try {
            if (sessionIdentity == null) {
                planService.activatePlanMode(transportChatId, modelTier);
            } else {
                planService.activatePlanMode(sessionIdentity, transportChatId, modelTier);
            }
            return new Enabled(modelTier);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return new PlanLimitReached(runtimeConfigService.getPlanMaxPlans());
        }
    }

    public PlanModeOutcome disablePlanMode(SessionIdentity sessionIdentity) {
        if (!planService.isFeatureEnabled()) {
            return new FeatureUnavailable();
        }
        if (sessionIdentity == null && !planService.isPlanModeActive()) {
            return new NotActive();
        }
        if (sessionIdentity != null && !planService.isPlanModeActive(sessionIdentity)) {
            return new NotActive();
        }

        if (sessionIdentity == null) {
            planService.deactivatePlanMode();
        } else {
            planService.deactivatePlanMode(sessionIdentity);
        }
        return new Disabled();
    }

    public PlanModeOutcome completePlanMode(SessionIdentity sessionIdentity) {
        if (!planService.isFeatureEnabled()) {
            return new FeatureUnavailable();
        }
        if (sessionIdentity == null && !planService.isPlanModeActive()) {
            return new NotActive();
        }
        if (sessionIdentity != null && !planService.isPlanModeActive(sessionIdentity)) {
            return new NotActive();
        }

        if (sessionIdentity == null) {
            planService.deactivatePlanMode();
        } else {
            planService.deactivatePlanMode(sessionIdentity);
        }
        return new Done();
    }

    public PlanActionOutcome approvePlan(SessionIdentity sessionIdentity, String planId) {
        if (!planService.isFeatureEnabled()) {
            return new FeatureUnavailable();
        }
        String resolvedPlanId = planId != null ? planId : findMostRecentReadyPlanId(sessionIdentity);
        if (resolvedPlanId == null) {
            return new NoReadyPlan();
        }

        try {
            if (sessionIdentity != null && planService.getPlan(resolvedPlanId, sessionIdentity).isEmpty()) {
                return new PlanNotFound(resolvedPlanId);
            }
            planService.approvePlan(resolvedPlanId);
            planExecutionService.executePlan(resolvedPlanId);
            return new Approved(resolvedPlanId);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return new Failure(exception.getMessage());
        }
    }

    public PlanActionOutcome cancelPlan(SessionIdentity sessionIdentity, String planId) {
        if (!planService.isFeatureEnabled()) {
            return new FeatureUnavailable();
        }
        String resolvedPlanId = planId != null ? planId : findMostRecentActivePlanId(sessionIdentity);
        if (resolvedPlanId == null) {
            return new NoActivePlan();
        }

        try {
            if (sessionIdentity != null && planService.getPlan(resolvedPlanId, sessionIdentity).isEmpty()) {
                return new PlanNotFound(resolvedPlanId);
            }
            planService.cancelPlan(resolvedPlanId);
            return new Cancelled(resolvedPlanId);
        } catch (IllegalArgumentException exception) {
            return new Failure(exception.getMessage());
        }
    }

    public PlanActionOutcome resumePlan(SessionIdentity sessionIdentity, String planId) {
        if (!planService.isFeatureEnabled()) {
            return new FeatureUnavailable();
        }
        String resolvedPlanId = planId != null ? planId : findMostRecentPartialPlanId(sessionIdentity);
        if (resolvedPlanId == null) {
            return new NoPartialPlan();
        }

        try {
            if (sessionIdentity != null && planService.getPlan(resolvedPlanId, sessionIdentity).isEmpty()) {
                return new PlanNotFound(resolvedPlanId);
            }
            planExecutionService.resumePlan(resolvedPlanId);
            return new Resumed(resolvedPlanId);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return new Failure(exception.getMessage());
        }
    }

    public PlanOverviewOutcome getPlanStatus(SessionIdentity sessionIdentity, String planId) {
        if (!planService.isFeatureEnabled()) {
            return new FeatureUnavailable();
        }
        String resolvedPlanId = planId != null ? planId : findMostRecentActivePlanId(sessionIdentity);
        if (resolvedPlanId == null) {
            List<Plan> plans = sessionIdentity != null
                    ? planService.getPlans(sessionIdentity)
                    : planService.getPlans();
            if (plans.isEmpty()) {
                return new EmptyPlans();
            }
            resolvedPlanId = plans.get(plans.size() - 1).getId();
        }

        return (sessionIdentity != null
                ? planService.getPlan(resolvedPlanId, sessionIdentity)
                : planService.getPlan(resolvedPlanId))
                .<PlanOverviewOutcome>map(PlanDetails::new)
                .orElse(new PlanNotFound(resolvedPlanId));
    }

    public PlanOverviewOutcome listPlans(SessionIdentity sessionIdentity) {
        if (!planService.isFeatureEnabled()) {
            return new FeatureUnavailable();
        }
        List<Plan> plans = sessionIdentity != null
                ? planService.getPlans(sessionIdentity)
                : planService.getPlans();
        if (plans.isEmpty()) {
            return new EmptyPlans();
        }
        return new PlansOverview(List.copyOf(plans));
    }

    private String findMostRecentReadyPlanId(SessionIdentity sessionIdentity) {
        List<Plan> plans = sessionIdentity != null
                ? planService.getPlans(sessionIdentity)
                : planService.getPlans();
        return plans.stream()
                .filter(plan -> plan.getStatus() == Plan.PlanStatus.READY)
                .reduce((first, second) -> second)
                .map(Plan::getId)
                .orElse(null);
    }

    private String findMostRecentActivePlanId(SessionIdentity sessionIdentity) {
        List<Plan> plans = sessionIdentity != null
                ? planService.getPlans(sessionIdentity)
                : planService.getPlans();
        return plans.stream()
                .filter(plan -> plan.getStatus() == Plan.PlanStatus.COLLECTING
                        || plan.getStatus() == Plan.PlanStatus.READY
                        || plan.getStatus() == Plan.PlanStatus.APPROVED
                        || plan.getStatus() == Plan.PlanStatus.EXECUTING)
                .reduce((first, second) -> second)
                .map(Plan::getId)
                .orElse(null);
    }

    private String findMostRecentPartialPlanId(SessionIdentity sessionIdentity) {
        List<Plan> plans = sessionIdentity != null
                ? planService.getPlans(sessionIdentity)
                : planService.getPlans();
        return plans.stream()
                .filter(plan -> plan.getStatus() == Plan.PlanStatus.PARTIALLY_COMPLETED)
                .reduce((first, second) -> second)
                .map(Plan::getId)
                .orElse(null);
    }

    public sealed

    interface PlanModeOutcome
    permits FeatureUnavailable, ModeStatus, AlreadyActive, NotActive, Enabled,
            Disabled, Done, PlanLimitReached
    {
        }

        public sealed

        interface PlanActionOutcome
        permits FeatureUnavailable, Approved, Cancelled, Resumed, NoReadyPlan,
            NoActivePlan, NoPartialPlan, PlanNotFound, Failure
        {
            }

            public sealed

            interface PlanOverviewOutcome
            permits FeatureUnavailable, EmptyPlans, PlanNotFound, PlanDetails,
                    PlansOverview
            {
    }

    public record FeatureUnavailable() implements PlanModeOutcome, PlanActionOutcome, PlanOverviewOutcome {
    }

    public record ModeStatus(boolean active) implements PlanModeOutcome {
    }

    public record AlreadyActive() implements PlanModeOutcome {
    }

    public record NotActive() implements PlanModeOutcome {
    }

    public record Enabled(String modelTier) implements PlanModeOutcome {
    }

    public record Disabled() implements PlanModeOutcome {
    }

    public record Done() implements PlanModeOutcome {
    }

    public record PlanLimitReached(int maxPlans) implements PlanModeOutcome {
    }

    public record Approved(String planId) implements PlanActionOutcome {
    }

    public record Cancelled(String planId) implements PlanActionOutcome {
    }

    public record Resumed(String planId) implements PlanActionOutcome {
    }

    public record NoReadyPlan() implements PlanActionOutcome {
    }

    public record NoActivePlan() implements PlanActionOutcome {
    }

    public record NoPartialPlan() implements PlanActionOutcome {
    }

    public record PlanNotFound(String planId) implements PlanActionOutcome, PlanOverviewOutcome {
    }

    public record Failure(String message) implements PlanActionOutcome {
    }

    public record EmptyPlans() implements PlanOverviewOutcome {
    }

    public record PlanDetails(Plan plan) implements PlanOverviewOutcome {
    }

    public record PlansOverview(List<Plan> plans) implements PlanOverviewOutcome {
    }
}
