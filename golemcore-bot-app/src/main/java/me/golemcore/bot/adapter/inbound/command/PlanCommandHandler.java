package me.golemcore.bot.adapter.inbound.command;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.application.command.PlanCommandService;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PlanStep;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.inbound.CommandPort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class PlanCommandHandler {

    private static final String MSG_PLAN_NOT_AVAILABLE = "command.plan.not-available";
    private static final String DOUBLE_NEWLINE = "\n\n";
    private static final String CMD_STATUS = "status";

    private final PlanCommandService planCommandService;
    private final UserPreferencesService preferencesService;

    boolean isFeatureEnabled() {
        return planCommandService.isFeatureEnabled();
    }

    void resetPlanMode(SessionIdentity sessionIdentity) {
        planCommandService.resetPlanMode(sessionIdentity);
    }

    CommandPort.CommandResult handlePlan(List<String> args, SessionIdentity sessionIdentity, String transportChatId) {
        if (!planCommandService.isFeatureEnabled()) {
            return CommandPort.CommandResult.success(msg(MSG_PLAN_NOT_AVAILABLE));
        }

        if (args.isEmpty()) {
            PlanCommandService.ModeStatus status = (PlanCommandService.ModeStatus) planCommandService
                    .getModeStatus(sessionIdentity);
            return CommandPort.CommandResult.success(msg("command.plan.status", status.active() ? "ON" : "OFF"));
        }

        String subcommand = args.get(0).toLowerCase(Locale.ROOT);
        return switch (subcommand) {
        case "on" -> handlePlanOn(args, sessionIdentity, transportChatId);
        case "off" -> handlePlanOff(sessionIdentity);
        case "done" -> handlePlanDone(sessionIdentity);
        case "approve" -> handlePlanApprove(args, sessionIdentity);
        case "cancel" -> handlePlanCancel(args, sessionIdentity);
        case "resume" -> handlePlanResume(args, sessionIdentity);
        case CMD_STATUS -> handlePlanStatus(args, sessionIdentity);
        default -> CommandPort.CommandResult.success(msg("command.plan.usage"));
        };
    }

    CommandPort.CommandResult handlePlans(SessionIdentity sessionIdentity) {
        if (!planCommandService.isFeatureEnabled()) {
            return CommandPort.CommandResult.success(msg(MSG_PLAN_NOT_AVAILABLE));
        }
        PlanCommandService.PlanOverviewOutcome outcome = planCommandService.listPlans(sessionIdentity);
        if (outcome instanceof PlanCommandService.EmptyPlans) {
            return CommandPort.CommandResult.success(msg("command.plans.empty"));
        }
        List<Plan> plans = ((PlanCommandService.PlansOverview) outcome).plans();

        StringBuilder builder = new StringBuilder();
        builder.append(msg("command.plans.title", plans.size())).append(DOUBLE_NEWLINE);

        for (Plan plan : plans) {
            long completed = plan.getCompletedStepCount();
            int total = plan.getSteps().size();
            String statusIcon = switch (plan.getStatus()) {
            case COLLECTING -> "✍️";
            case READY -> "⏳";
            case APPROVED -> "✅";
            case EXECUTING -> "▶️";
            case COMPLETED -> "✅";
            case PARTIALLY_COMPLETED -> "⚠️";
            case CANCELLED -> "❌";
            };
            builder.append(String.format("%s `%s` [%s] (%d/%d steps)%n",
                    statusIcon, plan.getId().substring(0, 8), plan.getStatus(), completed, total));
            if (plan.getTitle() != null) {
                builder.append("  ").append(plan.getTitle()).append("\n");
            }
        }

        return CommandPort.CommandResult.success(builder.toString());
    }

    private CommandPort.CommandResult handlePlanOn(
            List<String> args,
            SessionIdentity sessionIdentity,
            String transportChatId) {
        String modelTier = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : null;
        PlanCommandService.PlanModeOutcome outcome = planCommandService.enablePlanMode(sessionIdentity, transportChatId,
                modelTier);
        if (outcome instanceof PlanCommandService.AlreadyActive) {
            return CommandPort.CommandResult.success(msg("command.plan.already-active"));
        }
        if (outcome instanceof PlanCommandService.Enabled) {
            String tierMessage = modelTier != null ? " (tier: " + modelTier + ")" : "";
            return CommandPort.CommandResult.success(msg("command.plan.enabled") + tierMessage);
        }
        if (outcome instanceof PlanCommandService.PlanLimitReached limitReached) {
            return CommandPort.CommandResult.failure(msg("command.plan.limit", limitReached.maxPlans()));
        }
        return CommandPort.CommandResult.success(msg(MSG_PLAN_NOT_AVAILABLE));
    }

    private CommandPort.CommandResult handlePlanOff(SessionIdentity sessionIdentity) {
        PlanCommandService.PlanModeOutcome outcome = planCommandService.disablePlanMode(sessionIdentity);
        if (outcome instanceof PlanCommandService.NotActive) {
            return CommandPort.CommandResult.success(msg("command.plan.not-active"));
        }
        return CommandPort.CommandResult.success(msg("command.plan.disabled"));
    }

    private CommandPort.CommandResult handlePlanDone(SessionIdentity sessionIdentity) {
        PlanCommandService.PlanModeOutcome outcome = planCommandService.completePlanMode(sessionIdentity);
        if (outcome instanceof PlanCommandService.NotActive) {
            return CommandPort.CommandResult.success(msg("command.plan.not-active"));
        }
        return CommandPort.CommandResult.success(msg("command.plan.done"));
    }

    private CommandPort.CommandResult handlePlanApprove(List<String> args, SessionIdentity sessionIdentity) {
        PlanCommandService.PlanActionOutcome outcome = planCommandService.approvePlan(sessionIdentity,
                args.size() > 1 ? args.get(1) : null);
        if (outcome instanceof PlanCommandService.NoReadyPlan) {
            return CommandPort.CommandResult.failure(msg("command.plan.no-ready"));
        }
        if (outcome instanceof PlanCommandService.PlanNotFound planNotFound) {
            return CommandPort.CommandResult.failure(msg("command.plan.not-found", planNotFound.planId()));
        }
        if (outcome instanceof PlanCommandService.Approved approved) {
            return CommandPort.CommandResult.success(msg("command.plan.approved", approved.planId()));
        }
        if (outcome instanceof PlanCommandService.Failure failure) {
            return CommandPort.CommandResult.failure(failure.message());
        }
        return CommandPort.CommandResult.success(msg(MSG_PLAN_NOT_AVAILABLE));
    }

    private CommandPort.CommandResult handlePlanCancel(List<String> args, SessionIdentity sessionIdentity) {
        PlanCommandService.PlanActionOutcome outcome = planCommandService.cancelPlan(sessionIdentity,
                args.size() > 1 ? args.get(1) : null);
        if (outcome instanceof PlanCommandService.NoActivePlan) {
            return CommandPort.CommandResult.failure(msg("command.plan.no-active-plan"));
        }
        if (outcome instanceof PlanCommandService.PlanNotFound planNotFound) {
            return CommandPort.CommandResult.failure(msg("command.plan.not-found", planNotFound.planId()));
        }
        if (outcome instanceof PlanCommandService.Cancelled cancelled) {
            return CommandPort.CommandResult.success(msg("command.plan.cancelled", cancelled.planId()));
        }
        return CommandPort.CommandResult.failure(((PlanCommandService.Failure) outcome).message());
    }

    private CommandPort.CommandResult handlePlanResume(List<String> args, SessionIdentity sessionIdentity) {
        PlanCommandService.PlanActionOutcome outcome = planCommandService.resumePlan(sessionIdentity,
                args.size() > 1 ? args.get(1) : null);
        if (outcome instanceof PlanCommandService.NoPartialPlan) {
            return CommandPort.CommandResult.failure(msg("command.plan.no-partial"));
        }
        if (outcome instanceof PlanCommandService.PlanNotFound planNotFound) {
            return CommandPort.CommandResult.failure(msg("command.plan.not-found", planNotFound.planId()));
        }
        if (outcome instanceof PlanCommandService.Resumed resumed) {
            return CommandPort.CommandResult.success(msg("command.plan.resumed", resumed.planId()));
        }
        return CommandPort.CommandResult.failure(((PlanCommandService.Failure) outcome).message());
    }

    private CommandPort.CommandResult handlePlanStatus(List<String> args, SessionIdentity sessionIdentity) {
        PlanCommandService.PlanOverviewOutcome outcome = planCommandService.getPlanStatus(sessionIdentity,
                args.size() > 1 ? args.get(1) : null);
        if (outcome instanceof PlanCommandService.EmptyPlans) {
            return CommandPort.CommandResult.success(msg("command.plans.empty"));
        }
        if (outcome instanceof PlanCommandService.PlanNotFound planNotFound) {
            return CommandPort.CommandResult.failure(msg("command.plan.not-found", planNotFound.planId()));
        }
        return CommandPort.CommandResult.success(formatPlanStatus(((PlanCommandService.PlanDetails) outcome).plan()));
    }

    private String formatPlanStatus(Plan plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("**Plan** `").append(plan.getId().substring(0, 8)).append("`\n");
        builder.append("Status: ").append(plan.getStatus()).append("\n");
        if (plan.getModelTier() != null) {
            builder.append("Tier: ").append(plan.getModelTier()).append("\n");
        }
        builder.append("Steps: ").append(plan.getSteps().size()).append("\n\n");

        List<PlanStep> sortedSteps = plan.getSteps().stream()
                .sorted(Comparator.comparingInt(PlanStep::getOrder))
                .toList();

        for (PlanStep step : sortedSteps) {
            String icon = switch (step.getStatus()) {
            case PENDING -> "[ ]";
            case IN_PROGRESS -> "[>]";
            case COMPLETED -> "[x]";
            case FAILED -> "[!]";
            case SKIPPED -> "[-]";
            };
            builder.append("  ").append(icon).append(" `").append(step.getToolName()).append("`");
            if (step.getDescription() != null) {
                builder.append(" — ").append(step.getDescription());
            }
            builder.append("\n");
        }

        return builder.toString();
    }

    private String msg(String key, Object... args) {
        return preferencesService.getMessage(key, args);
    }
}
