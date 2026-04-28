package me.golemcore.bot.adapter.inbound.command;

import java.util.List;
import java.util.Locale;
import me.golemcore.bot.application.command.PlanCommandService;
import me.golemcore.bot.domain.command.CommandInvocation;
import me.golemcore.bot.domain.command.CommandOutcome;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.inbound.CommandPort;
import org.springframework.stereotype.Component;

@Component
class PlanCommandHandler implements CommandHandler {

    private static final String CMD_STATUS = "status";
    private static final List<String> COMMAND_NAMES = List.of("plan");

    private final PlanCommandService planCommandService;
    private final UserPreferencesService preferencesService;

    public PlanCommandHandler(
            PlanCommandService planCommandService,
            UserPreferencesService preferencesService) {
        this.planCommandService = planCommandService;
        this.preferencesService = preferencesService;
    }

    boolean isFeatureEnabled() {
        return true;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public List<String> commandNames() {
        return COMMAND_NAMES;
    }

    @Override
    public List<CommandPort.CommandDefinition> listCommands() {
        if (!isFeatureEnabled()) {
            return List.of();
        }
        return List.of(new CommandPort.CommandDefinition("plan", "Ephemeral plan mode",
                "/plan [on|off|done|status]"));
    }

    @Override
    public CommandOutcome handle(CommandInvocation invocation) {
        String channelType = invocation.context().channelType();
        String conversationKey = invocation.context().effectiveConversationKey();
        SessionIdentity sessionIdentity = null;
        if (channelType != null && !channelType.isBlank()
                && conversationKey != null && !conversationKey.isBlank()) {
            sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(channelType, conversationKey);
        }
        return handlePlan(invocation.args(), sessionIdentity, invocation.context().effectiveTransportChatId());
    }

    void resetPlanMode(SessionIdentity sessionIdentity) {
        planCommandService.resetPlanMode(sessionIdentity);
    }

    CommandOutcome handlePlan(List<String> args, SessionIdentity sessionIdentity, String transportChatId) {
        if (args.isEmpty()) {
            return handlePlanStatus(sessionIdentity);
        }

        String subcommand = args.get(0).toLowerCase(Locale.ROOT);
        return switch (subcommand) {
        case "on" -> handlePlanOn(sessionIdentity, transportChatId);
        case "off" -> handlePlanOff(sessionIdentity);
        case "done" -> handlePlanDone(sessionIdentity);
        case CMD_STATUS -> handlePlanStatus(sessionIdentity);
        default -> CommandOutcome.success(msg("command.plan.usage"));
        };
    }

    private CommandOutcome handlePlanOn(SessionIdentity sessionIdentity, String transportChatId) {
        PlanCommandService.PlanModeOutcome outcome = planCommandService.enablePlanMode(sessionIdentity,
                transportChatId);
        if (outcome instanceof PlanCommandService.AlreadyActive) {
            return CommandOutcome.success(msg("command.plan.already-active"));
        }
        return CommandOutcome.success(msg("command.plan.enabled"));
    }

    private CommandOutcome handlePlanOff(SessionIdentity sessionIdentity) {
        PlanCommandService.PlanModeOutcome outcome = planCommandService.disablePlanMode(sessionIdentity);
        if (outcome instanceof PlanCommandService.NotActive) {
            return CommandOutcome.success(msg("command.plan.not-active"));
        }
        return CommandOutcome.success(msg("command.plan.disabled"));
    }

    private CommandOutcome handlePlanDone(SessionIdentity sessionIdentity) {
        PlanCommandService.PlanModeOutcome outcome = planCommandService.completePlanMode(sessionIdentity);
        if (outcome instanceof PlanCommandService.NotActive) {
            return CommandOutcome.success(msg("command.plan.not-active"));
        }
        return CommandOutcome.success(msg("command.plan.done"));
    }

    private CommandOutcome handlePlanStatus(SessionIdentity sessionIdentity) {
        PlanCommandService.ModeStatus status = (PlanCommandService.ModeStatus) planCommandService
                .getModeStatus(sessionIdentity);
        return CommandOutcome.success(msg("command.plan.status", status.active() ? "ON" : "OFF"));
    }

    private String msg(String key, Object... args) {
        return preferencesService.getMessage(key, args);
    }
}
