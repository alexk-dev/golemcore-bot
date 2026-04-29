package me.golemcore.bot.application.command;

import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.planning.PlanService;

public class PlanCommandService {

    private final PlanService planService;

    public PlanCommandService(PlanService planService) {
        this.planService = planService;
    }

    public boolean isFeatureEnabled() {
        return true;
    }

    public void resetPlanMode(SessionIdentity sessionIdentity) {
        if (sessionIdentity == null) {
            planService.deactivatePlanMode();
        } else {
            planService.deactivatePlanMode(sessionIdentity);
        }
    }

    public PlanModeOutcome getModeStatus(SessionIdentity sessionIdentity) {
        boolean active = sessionIdentity != null
                ? planService.isPlanModeActive(sessionIdentity)
                : planService.isPlanModeActive();
        return new ModeStatus(active);
    }

    public PlanModeOutcome enablePlanMode(SessionIdentity sessionIdentity, String transportChatId) {
        if (sessionIdentity == null) {
            if (planService.isPlanModeActive()) {
                return new AlreadyActive();
            }
            planService.activatePlanMode(transportChatId, null);
        } else {
            if (planService.isPlanModeActive(sessionIdentity)) {
                return new AlreadyActive();
            }
            planService.activatePlanMode(sessionIdentity, transportChatId, null);
        }
        return new Enabled();
    }

    public PlanModeOutcome disablePlanMode(SessionIdentity sessionIdentity) {
        if (sessionIdentity == null && !planService.isPlanModeActive()) {
            return new NotActive();
        }
        if (sessionIdentity != null && !planService.isPlanModeActive(sessionIdentity)) {
            return new NotActive();
        }
        resetPlanMode(sessionIdentity);
        return new Disabled();
    }

    public PlanModeOutcome completePlanMode(SessionIdentity sessionIdentity) {
        if (sessionIdentity == null && !planService.isPlanModeActive()) {
            return new NotActive();
        }
        if (sessionIdentity != null && !planService.isPlanModeActive(sessionIdentity)) {
            return new NotActive();
        }
        if (sessionIdentity == null) {
            planService.completePlanMode();
        } else {
            planService.completePlanMode(sessionIdentity);
        }
        return new Done();
    }

    public sealed

    interface PlanModeOutcome
    permits ModeStatus, AlreadyActive, NotActive, Enabled, Disabled, Done
    {
    }

    public record ModeStatus(boolean active) implements PlanModeOutcome {
    }

    public record AlreadyActive() implements PlanModeOutcome {
    }

    public record NotActive() implements PlanModeOutcome {
    }

    public record Enabled() implements PlanModeOutcome {
    }

    public record Disabled() implements PlanModeOutcome {
    }

    public record Done() implements PlanModeOutcome {
    }
}
