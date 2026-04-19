package me.golemcore.bot.adapter.inbound.command;

import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.application.command.PlanCommandService;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.inbound.CommandPort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class PlanCommandHandler {

    private static final String CMD_STATUS = "status";

    private final PlanCommandService planCommandService;
    private final UserPreferencesService preferencesService;

    boolean isFeatureEnabled() {
        return true;
    }

    void resetPlanMode(SessionIdentity sessionIdentity) {
        planCommandService.resetPlanMode(sessionIdentity);
    }

    CommandPort.CommandResult handlePlan(List<String> args, SessionIdentity sessionIdentity, String transportChatId) {
        if (args.isEmpty()) {
            return handlePlanStatus(sessionIdentity);
        }

        String subcommand = args.get(0).toLowerCase(Locale.ROOT);
        return switch (subcommand) {
        case "on" -> handlePlanOn(sessionIdentity, transportChatId);
        case "off" -> handlePlanOff(sessionIdentity);
        case "done" -> handlePlanDone(sessionIdentity);
        case CMD_STATUS -> handlePlanStatus(sessionIdentity);
        default -> CommandPort.CommandResult.success(msg("command.plan.usage"));
        };
    }

    private CommandPort.CommandResult handlePlanOn(SessionIdentity sessionIdentity, String transportChatId) {
        PlanCommandService.PlanModeOutcome outcome = planCommandService.enablePlanMode(sessionIdentity,
                transportChatId);
        if (outcome instanceof PlanCommandService.AlreadyActive) {
            return CommandPort.CommandResult.success(msg("command.plan.already-active"));
        }
        return CommandPort.CommandResult.success(msg("command.plan.enabled"));
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

    private CommandPort.CommandResult handlePlanStatus(SessionIdentity sessionIdentity) {
        PlanCommandService.ModeStatus status = (PlanCommandService.ModeStatus) planCommandService
                .getModeStatus(sessionIdentity);
        return CommandPort.CommandResult.success(msg("command.plan.status", status.active() ? "ON" : "OFF"));
    }

    private String msg(String key, Object... args) {
        return preferencesService.getMessage(key, args);
    }
}
