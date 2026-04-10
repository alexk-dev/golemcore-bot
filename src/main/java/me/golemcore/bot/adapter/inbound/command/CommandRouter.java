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

package me.golemcore.bot.adapter.inbound.command;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.UsageStats;
import me.golemcore.bot.domain.service.CompactionOrchestrationService;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.SessionRunCoordinator;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.inbound.CommandPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import me.golemcore.bot.tools.ScheduleSessionActionTool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * Routes slash commands to appropriate handlers.
 *
 * <p>
 * The router is intentionally transport-focused: it extracts command context,
 * dispatches to command family handlers, and keeps only generic status/help
 * formatting locally.
 */
@Component
@Slf4j
public class CommandRouter implements CommandPort {

    private static final int DEFAULT_COMPACT_KEEP = 10;
    private static final int MAX_TOOL_DESC_LENGTH = 120;
    private static final long TOKEN_MILLION_THRESHOLD = 1_000_000;
    private static final long TOKEN_THOUSAND_THRESHOLD = 1_000;
    private static final String DOUBLE_NEWLINE = "\n\n";
    private static final String TABLE_SEPARATOR = " | ";
    private static final String CMD_HELP = "help";
    private static final String CMD_GOAL = "goal";
    private static final String CMD_PLAN = "plan";
    private static final String CMD_PLANS = "plans";
    private static final String CMD_LATER = "later";
    private static final String CMD_STATUS = "status";
    private static final String SUBCMD_RESET = "reset";

    private static final List<String> KNOWN_COMMANDS = List.of(
            "skills",
            "tools",
            CMD_STATUS,
            "new",
            SUBCMD_RESET,
            "compact",
            CMD_HELP,
            "tier",
            "model",
            "sessions",
            "auto",
            "goals",
            CMD_GOAL,
            "diary",
            "tasks",
            "schedule",
            CMD_LATER,
            CMD_PLAN,
            CMD_PLANS,
            "stop");

    private final SkillComponent skillComponent;
    private final List<ToolComponent> toolComponents;
    private final SessionPort sessionService;
    private final UsageTrackingPort usageTracker;
    private final UserPreferencesService preferencesService;
    private final CompactionOrchestrationService compactionOrchestrationService;
    private final AutomationCommandHandler automationCommandHandler;
    private final ModelSelectionCommandHandler modelSelectionCommandHandler;
    private final PlanCommandHandler planCommandHandler;
    private final DelayedActionPolicyService delayedActionPolicyService;
    private final SessionRunCoordinator runCoordinator;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;

    public CommandRouter(
            SkillComponent skillComponent,
            List<ToolComponent> toolComponents,
            SessionPort sessionService,
            UsageTrackingPort usageTracker,
            UserPreferencesService preferencesService,
            CompactionOrchestrationService compactionOrchestrationService,
            AutomationCommandHandler automationCommandHandler,
            ModelSelectionCommandHandler modelSelectionCommandHandler,
            PlanCommandHandler planCommandHandler,
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
        this.modelSelectionCommandHandler = modelSelectionCommandHandler;
        this.planCommandHandler = planCommandHandler;
        this.delayedActionPolicyService = delayedActionPolicyService;
        this.runCoordinator = runCoordinator;
        this.buildPropertiesProvider = buildPropertiesProvider;
        log.info("CommandRouter initialized with commands: {}", KNOWN_COMMANDS);
    }

    @Override
    public CompletableFuture<CommandResult> execute(String command, List<String> args, Map<String, Object> context) {
        return CompletableFuture.supplyAsync(() -> {
            String sessionId = (String) context.get("sessionId");
            String channelType = (String) context.get("channelType");
            String chatId = (String) context.get("chatId");
            String sessionChatId = resolveContextString(context, "sessionChatId", chatId);
            String conversationKey = resolveContextString(context, "conversationKey", sessionChatId);
            String transportChatId = resolveContextString(context, "transportChatId", chatId);
            boolean explicitSessionRouting = hasExplicitContextString(context, "sessionChatId")
                    || hasExplicitContextString(context, "conversationKey");
            SessionIdentity sessionIdentity = explicitSessionRouting
                    ? SessionIdentitySupport.resolveSessionIdentity(channelType, sessionChatId)
                    : null;
            String autoSessionChatId = explicitSessionRouting ? sessionChatId : transportChatId;
            log.debug("Executing command: /{} (session={})", command, sessionId);

            return switch (command) {
            case "skills" -> handleSkills();
            case "tools" -> handleTools(channelType);
            case CMD_STATUS -> handleStatus(sessionId);
            case "new" -> handleNew();
            case "reset" -> handleReset(sessionId, sessionIdentity);
            case "compact" -> handleCompact(sessionId, args);
            case CMD_HELP -> handleHelp();
            case "tier" -> modelSelectionCommandHandler.handleTier(args);
            case "model" -> modelSelectionCommandHandler.handleModel(args);
            case "sessions" -> handleSessions(channelType);
            case "auto" -> automationCommandHandler.handleAuto(args, channelType, autoSessionChatId, transportChatId);
            case "goals" -> automationCommandHandler.handleGoals();
            case CMD_GOAL -> automationCommandHandler.handleGoal(args);
            case "diary" -> automationCommandHandler.handleDiary(args);
            case "tasks" -> automationCommandHandler.handleTasks();
            case "schedule" -> automationCommandHandler.handleSchedule(args);
            case CMD_LATER -> automationCommandHandler.handleLater(args, channelType, conversationKey);
            case CMD_PLAN -> planCommandHandler.handlePlan(args, sessionIdentity, transportChatId);
            case CMD_PLANS -> planCommandHandler.handlePlans(sessionIdentity);
            case "stop" -> handleStop(channelType, sessionChatId);
            default -> CommandResult.failure(msg("command.unknown", command));
            };
        });
    }

    @Override
    public boolean hasCommand(String command) {
        return KNOWN_COMMANDS.contains(command);
    }

    @Override
    public List<CommandDefinition> listCommands() {
        List<CommandDefinition> commands = new ArrayList<>(List.of(
                new CommandDefinition("skills", "List available skills", "/skills"),
                new CommandDefinition("tools", "List enabled tools", "/tools"),
                new CommandDefinition(CMD_STATUS, "Show session status", "/status"),
                new CommandDefinition("new", "Start a new conversation", "/new"),
                new CommandDefinition(SUBCMD_RESET, "Reset conversation", "/reset"),
                new CommandDefinition("compact", "Compact conversation history", "/compact [keep]"),
                new CommandDefinition(CMD_HELP, "Show available commands", "/help"),
                new CommandDefinition(
                        "tier",
                        "Set model tier",
                        "/tier [" + String.join("|", ModelTierCatalog.orderedExplicitTiers()) + "] [force]"),
                new CommandDefinition(
                        "model",
                        "Per-tier model selection",
                        "/model [list|<tier> <model>|<tier> reasoning <level>|<tier> reset]"),
                new CommandDefinition("sessions", "Open session switcher menu (Telegram)", "/sessions"),
                new CommandDefinition("stop", "Stop current run", "/stop")));
        if (automationCommandHandler.isLaterFeatureEnabled()) {
            commands.add(new CommandDefinition(CMD_LATER, "Reminders and follow-ups", "/later [list|cancel|now|help]"));
        }
        if (automationCommandHandler.isAutoFeatureEnabled()) {
            commands.add(new CommandDefinition("auto", "Toggle auto mode", "/auto [on|off]"));
            commands.add(new CommandDefinition("goals", "List goals", "/goals"));
            commands.add(new CommandDefinition(CMD_GOAL, "Create a goal", "/goal <description>"));
            commands.add(new CommandDefinition("tasks", "List tasks", "/tasks"));
            commands.add(new CommandDefinition("diary", "Show diary entries", "/diary [count]"));
            commands.add(new CommandDefinition("schedule", "Manage schedules", "/schedule [help]"));
        }
        if (planCommandHandler.isFeatureEnabled()) {
            commands.add(new CommandDefinition(CMD_PLAN, "Plan work control",
                    "/plan [on|off|done|status|approve|cancel|resume]"));
            commands.add(new CommandDefinition(CMD_PLANS, "List plans", "/plans"));
        }
        return commands;
    }

    private String resolveContextString(Map<String, Object> context, String key, String fallback) {
        Object value = context.get(key);
        if (value instanceof String && !((String) value).isBlank()) {
            return (String) value;
        }
        return fallback;
    }

    private boolean hasExplicitContextString(Map<String, Object> context, String key) {
        Object value = context.get(key);
        return value instanceof String && !((String) value).isBlank();
    }

    private CommandResult handleStop(String channelType, String chatId) {
        if (channelType == null || chatId == null) {
            return CommandResult.failure(msg("command.stop.notAvailable"));
        }
        runCoordinator.requestStop(channelType, chatId);
        return CommandResult.success(msg("command.stop.ack"));
    }

    private CommandResult handleSkills() {
        List<Skill> skills = skillComponent.getAvailableSkills();
        if (skills.isEmpty()) {
            return CommandResult.success(msg("command.skills.empty"));
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

        return CommandResult.success(builder.toString());
    }

    private CommandResult handleTools(String channelType) {
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

        return CommandResult.success(builder.toString());
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

    private CommandResult handleStatus(String sessionId) {
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
                List<Goal> activeGoals = automationCommandHandler.getActiveGoals();
                builder.append(msg("command.status.auto.goals", activeGoals.size())).append("\n");
                automationCommandHandler.getNextPendingTask()
                        .ifPresent(
                                task -> builder.append(msg("command.status.auto.task", task.getTitle())).append("\n"));
            } else {
                builder.append(msg("command.status.auto.off")).append("\n");
            }
        }

        return CommandResult.success(builder.toString());
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

    private CommandResult handleNew() {
        return CommandResult.success(msg("command.new.done"));
    }

    private CommandResult handleReset(String sessionId, SessionIdentity sessionIdentity) {
        sessionService.clearMessages(sessionId);
        planCommandHandler.resetPlanMode(sessionIdentity);
        return CommandResult.success(msg("command.reset.done"));
    }

    private CommandResult handleCompact(String sessionId, List<String> args) {
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
            return CommandResult.success(msg("command.compact.nothing", count));
        }

        if (result.usedSummary()) {
            log.info("[Compact] Summarized {} messages for session {}", result.removed(), sessionId);
        } else {
            log.info("[Compact] Truncated {} messages (no LLM) for session {}", result.removed(), sessionId);
        }

        String resultKey = result.usedSummary() ? "command.compact.done.summary" : "command.compact.done";
        return CommandResult.success(msg(resultKey, result.removed(), keepLast));
    }

    private CommandResult handleHelp() {
        return CommandResult.success(msg("command.help.text"));
    }

    private CommandResult handleSessions(String channelType) {
        if (!"telegram".equals(channelType)) {
            return CommandResult.success(msg("command.sessions.not-available"));
        }
        return CommandResult.success(msg("command.sessions.use-menu"));
    }

    private String msg(String key, Object... args) {
        return preferencesService.getMessage(key, args);
    }
}
