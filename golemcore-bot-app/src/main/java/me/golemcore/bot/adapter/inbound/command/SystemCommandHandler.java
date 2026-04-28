package me.golemcore.bot.adapter.inbound.command;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.application.command.PlanCommandService;
import me.golemcore.bot.domain.command.CommandExecutionContext;
import me.golemcore.bot.domain.command.CommandInvocation;
import me.golemcore.bot.domain.command.CommandOutcome;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.context.compaction.CompactionOrchestrationService;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.UsageStats;
import me.golemcore.bot.domain.scheduling.DelayedActionPolicyService;
import me.golemcore.bot.domain.identity.SessionIdentitySupport;
import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;
import me.golemcore.bot.domain.session.SessionRunCoordinator;
import me.golemcore.bot.port.inbound.CommandPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import me.golemcore.bot.tools.ScheduleSessionActionTool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * Handles built-in runtime commands that do not belong to a feature-specific
 * command family.
 */
@Component
@Slf4j
class SystemCommandHandler implements CommandHandler {

    private static final int DEFAULT_COMPACT_KEEP = 10;
    private static final int MAX_TOOL_DESC_LENGTH = 120;
    private static final long TOKEN_MILLION_THRESHOLD = 1_000_000;
    private static final long TOKEN_THOUSAND_THRESHOLD = 1_000;
    private static final String DOUBLE_NEWLINE = "\n\n";
    private static final String TABLE_SEPARATOR = " | ";
    private static final String CMD_HELP = "help";
    private static final String CMD_STATUS = "status";
    private static final String SUBCMD_RESET = "reset";

    private static final List<String> COMMAND_NAMES = List.of(
            "skills",
            "tools",
            CMD_STATUS,
            "new",
            SUBCMD_RESET,
            "compact",
            CMD_HELP,
            "sessions",
            "stop");

    private final SkillComponent skillComponent;
    private final List<ToolComponent> toolComponents;
    private final SessionPort sessionService;
    private final UsageTrackingPort usageTracker;
    private final UserPreferencesService preferencesService;
    private final CompactionOrchestrationService compactionOrchestrationService;
    private final AutomationCommandHandler automationCommandHandler;
    private final PlanCommandService planCommandService;
    private final DelayedActionPolicyService delayedActionPolicyService;
    private final SessionRunCoordinator runCoordinator;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;

    @SuppressWarnings("java:S107")
    SystemCommandHandler(
            SkillComponent skillComponent,
            List<ToolComponent> toolComponents,
            SessionPort sessionService,
            UsageTrackingPort usageTracker,
            UserPreferencesService preferencesService,
            CompactionOrchestrationService compactionOrchestrationService,
            AutomationCommandHandler automationCommandHandler,
            PlanCommandService planCommandService,
            DelayedActionPolicyService delayedActionPolicyService,
            SessionRunCoordinator runCoordinator,
            ObjectProvider<BuildProperties> buildPropertiesProvider) {
        this.skillComponent = skillComponent;
        this.toolComponents = toolComponents;
        this.sessionService = sessionService;
        this.usageTracker = usageTracker;
        this.preferencesService = preferencesService;
        this.compactionOrchestrationService = compactionOrchestrationService;
        this.automationCommandHandler = automationCommandHandler;
        this.planCommandService = planCommandService;
        this.delayedActionPolicyService = delayedActionPolicyService;
        this.runCoordinator = runCoordinator;
        this.buildPropertiesProvider = buildPropertiesProvider;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public List<String> commandNames() {
        return COMMAND_NAMES;
    }

    @Override
    public List<CommandPort.CommandDefinition> listCommands() {
        return List.of(
                new CommandPort.CommandDefinition("skills", "List available skills", "/skills"),
                new CommandPort.CommandDefinition("tools", "List enabled tools", "/tools"),
                new CommandPort.CommandDefinition(CMD_STATUS, "Show session status", "/status"),
                new CommandPort.CommandDefinition("new", "Start a new conversation", "/new"),
                new CommandPort.CommandDefinition(SUBCMD_RESET, "Reset conversation", "/reset"),
                new CommandPort.CommandDefinition("compact", "Compact conversation history", "/compact [keep]"),
                new CommandPort.CommandDefinition(CMD_HELP, "Show available commands", "/help"),
                new CommandPort.CommandDefinition("sessions", "Open session switcher menu (Telegram)", "/sessions"),
                new CommandPort.CommandDefinition("stop", "Stop current run", "/stop"));
    }

    @Override
    public CommandOutcome handle(CommandInvocation invocation) {
        CommandExecutionContext context = invocation.context();
        String sessionId = context.sessionId();
        SessionIdentity sessionIdentity = resolveSessionIdentity(context.channelType(),
                context.effectiveConversationKey());
        return switch (invocation.command()) {
        case "skills" -> handleSkills();
        case "tools" -> handleTools(context.channelType());
        case CMD_STATUS -> handleStatus(sessionId);
        case "new" -> handleNew();
        case SUBCMD_RESET -> handleReset(sessionId, sessionIdentity);
        case "compact" -> handleCompact(sessionId, invocation.args());
        case CMD_HELP -> handleHelp();
        case "sessions" -> handleSessions(context.channelType());
        case "stop" -> handleStop(context.channelType(), context.effectiveSessionChatId());
        default -> CommandOutcome.failure(msg("command.unknown", invocation.command()));
        };
    }

    private SessionIdentity resolveSessionIdentity(String channelType, String conversationKey) {
        if (channelType == null || channelType.isBlank() || conversationKey == null || conversationKey.isBlank()) {
            return null;
        }
        return SessionIdentitySupport.resolveSessionIdentity(channelType, conversationKey);
    }

    private CommandOutcome handleStop(String channelType, String chatId) {
        if (channelType == null || chatId == null) {
            return CommandOutcome.failure(msg("command.stop.notAvailable"));
        }
        runCoordinator.requestStop(channelType, chatId);
        return CommandOutcome.success(msg("command.stop.ack"));
    }

    private CommandOutcome handleSkills() {
        List<Skill> skills = skillComponent.getAvailableSkills();
        if (skills.isEmpty()) {
            return CommandOutcome.success(msg("command.skills.empty"));
        }

        StringBuilder builder = new StringBuilder();
        builder.append(msg("command.skills.title", skills.size()));
        builder.append("\n");

        for (Skill skill : skills) {
            builder.append("\n`").append(skill.getName()).append("`");
            if (!skill.isAvailable()) {
                builder.append(" _(unavailable)_");
            }
            builder.append("\n");
            if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
                builder.append(skill.getDescription()).append("\n");
            }
        }

        return CommandOutcome.success(builder.toString());
    }

    private CommandOutcome handleTools(String channelType) {
        List<ToolComponent> enabledTools = toolComponents.stream()
                .filter(ToolComponent::isEnabled)
                .filter(tool -> isToolVisibleForChannel(tool, channelType))
                .toList();

        StringBuilder builder = new StringBuilder();
        builder.append(msg("command.tools.title", enabledTools.size()));
        builder.append("\n");

        for (ToolComponent tool : enabledTools) {
            String name = tool.getDefinition().getName();
            String desc = tool.getDefinition().getDescription().trim()
                    .replace('\n', ' ')
                    .replace('\t', ' ');
            int dotIdx = desc.indexOf(". ");
            if (dotIdx > 0 && dotIdx < MAX_TOOL_DESC_LENGTH) {
                desc = desc.substring(0, dotIdx + 1);
            } else if (desc.length() > MAX_TOOL_DESC_LENGTH) {
                desc = desc.substring(0, MAX_TOOL_DESC_LENGTH) + "...";
            }
            builder.append("\n`").append(name).append("`\n");
            builder.append(desc).append("\n");
        }

        return CommandOutcome.success(builder.toString());
    }

    private boolean isToolVisibleForChannel(ToolComponent tool, String channelType) {
        if (tool == null) {
            return false;
        }
        if (!ScheduleSessionActionTool.TOOL_NAME.equals(tool.getToolName())) {
            return true;
        }
        if (delayedActionPolicyService == null) {
            return true;
        }
        return delayedActionPolicyService.canScheduleActions(channelType);
    }

    private CommandOutcome handleStatus(String sessionId) {
        StringBuilder builder = new StringBuilder();

        BuildProperties buildProps = buildPropertiesProvider.getIfAvailable();
        if (buildProps != null) {
            builder.append(msg("command.status.version", buildProps.getVersion())).append(DOUBLE_NEWLINE);
        }

        builder.append("**").append(msg("command.status.title")).append("**").append(DOUBLE_NEWLINE);

        int messageCount = sessionService.getMessageCount(sessionId);
        builder.append(msg("command.status.messages", messageCount)).append(DOUBLE_NEWLINE);

        try {
            Map<String, UsageStats> statsByModel = usageTracker.getStatsByModel(Duration.ofHours(24));
            if (statsByModel == null || statsByModel.isEmpty()) {
                builder.append(msg("command.status.usage.empty"));
            } else {
                builder.append(msg("command.status.usage.title")).append(DOUBLE_NEWLINE);
                builder.append(renderUsageTable(statsByModel));
            }
        } catch (Exception exception) {
            log.debug("Failed to get usage stats", exception);
        }

        if (automationCommandHandler.isAutoFeatureEnabled()) {
            builder.append("\n");
            if (automationCommandHandler.isAutoModeEnabled()) {
                builder.append(msg("command.status.auto.title")).append("\n");
                List<Goal> activeGoals = automationCommandHandler.getActiveGoals(sessionId);
                builder.append(msg("command.status.auto.goals", activeGoals.size())).append("\n");
                automationCommandHandler.getNextPendingTask(sessionId)
                        .ifPresent(
                                task -> builder.append(msg("command.status.auto.task", task.getTitle())).append("\n"));
            } else {
                builder.append(msg("command.status.auto.off")).append("\n");
            }
        }

        return CommandOutcome.success(builder.toString());
    }

    private String renderUsageTable(Map<String, UsageStats> statsByModel) {
        String colModel = msg("command.status.usage.col.model");
        String colReq = msg("command.status.usage.col.requests");
        String colTokens = msg("command.status.usage.col.tokens");
        String totalLabel = msg("command.status.usage.total");

        List<Map.Entry<String, UsageStats>> sorted = statsByModel.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, UsageStats>>comparingLong(
                        entry -> entry.getValue().getTotalTokens())
                        .reversed())
                .toList();

        long totalReq = 0;
        long totalTokens = 0;

        StringBuilder builder = new StringBuilder();
        builder.append("| ").append(colModel).append(TABLE_SEPARATOR).append(colReq).append(TABLE_SEPARATOR)
                .append(colTokens).append(" |\n");
        builder.append("|---|---|---|\n");

        for (Map.Entry<String, UsageStats> entry : sorted) {
            String key = entry.getKey();
            String modelName = key.contains("/") ? key.substring(key.indexOf('/') + 1) : key;
            UsageStats stats = entry.getValue();
            totalReq += stats.getTotalRequests();
            totalTokens += stats.getTotalTokens();
            builder.append("| ").append(modelName)
                    .append(TABLE_SEPARATOR).append(stats.getTotalRequests())
                    .append(TABLE_SEPARATOR).append(formatTokens(stats.getTotalTokens()))
                    .append(" |\n");
        }

        builder.append("| **").append(totalLabel).append("**").append(TABLE_SEPARATOR).append("**").append(totalReq)
                .append("**").append(TABLE_SEPARATOR).append("**").append(formatTokens(totalTokens)).append("** |\n");

        return builder.toString();
    }

    static String formatTokens(long tokens) {
        if (tokens >= TOKEN_MILLION_THRESHOLD) {
            return String.format("%.1fM", tokens / (double) TOKEN_MILLION_THRESHOLD);
        }
        if (tokens >= TOKEN_THOUSAND_THRESHOLD) {
            return String.format("%.1fK", tokens / (double) TOKEN_THOUSAND_THRESHOLD);
        }
        return String.valueOf(tokens);
    }

    private CommandOutcome handleNew() {
        return CommandOutcome.success(msg("command.new.done"));
    }

    private CommandOutcome handleReset(String sessionId, SessionIdentity sessionIdentity) {
        sessionService.clearMessages(sessionId);
        planCommandService.resetPlanMode(sessionIdentity);
        return CommandOutcome.success(msg("command.reset.done"));
    }

    private CommandOutcome handleCompact(String sessionId, List<String> args) {
        int keepLast = DEFAULT_COMPACT_KEEP;
        if (!args.isEmpty()) {
            try {
                keepLast = Integer.parseInt(args.get(0));
                keepLast = Math.max(1, Math.min(keepLast, 100));
            } catch (NumberFormatException ignored) {
            }
        }

        CompactionResult result = compactionOrchestrationService.compact(
                sessionId,
                CompactionReason.MANUAL_COMMAND,
                keepLast);

        if (result.removed() <= 0) {
            int count = sessionService.getMessageCount(sessionId);
            return CommandOutcome.success(msg("command.compact.nothing", count));
        }

        if (result.usedSummary()) {
            log.info("[Compact] Summarized {} messages for session {}", result.removed(), sessionId);
        } else {
            log.info("[Compact] Truncated {} messages (no LLM) for session {}", result.removed(), sessionId);
        }

        String resultKey = result.usedSummary() ? "command.compact.done.summary" : "command.compact.done";
        return CommandOutcome.success(msg(resultKey, result.removed(), keepLast));
    }

    private CommandOutcome handleHelp() {
        return CommandOutcome.success(msg("command.help.text"));
    }

    private CommandOutcome handleSessions(String channelType) {
        if (!"telegram".equals(channelType)) {
            return CommandOutcome.success(msg("command.sessions.not-available"));
        }
        return CommandOutcome.success(msg("command.sessions.use-menu"));
    }

    private String msg(String key, Object... args) {
        return preferencesService.getMessage(key, args);
    }
}
