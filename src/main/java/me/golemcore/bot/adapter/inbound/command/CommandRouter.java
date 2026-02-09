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

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.AutoModeChannelRegisteredEvent;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.CompactionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.CommandPort;
import me.golemcore.bot.domain.model.UsageStats;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Routes slash commands to appropriate handlers.
 *
 * <p>
 * This adapter implements the command handling interface and provides execution
 * logic for all bot slash commands:
 *
 * <ul>
 * <li>/skills - List available skills
 * <li>/tools - List enabled tools
 * <li>/status - Show session info and 24h usage stats
 * <li>/new, /reset - Start new conversation
 * <li>/compact [N] - Summarize old messages via LLM, keep last N
 * <li>/help - Show available commands
 * <li>/auto [on|off] - Toggle auto mode (if enabled)
 * <li>/goals - List goals (if auto mode enabled)
 * <li>/goal <desc> - Create goal (if auto mode enabled)
 * <li>/tasks - List tasks (if auto mode enabled)
 * <li>/diary [N] - Show diary entries (if auto mode enabled)
 * </ul>
 *
 * <p>
 * Commands are routed before the AgentLoop by channel adapters (e.g.,
 * TelegramAdapter).
 *
 * @see me.golemcore.bot.port.inbound.CommandPort
 */
@Component
@Slf4j
public class CommandRouter implements CommandPort {

    private static final int DEFAULT_COMPACT_KEEP = 10;

    private final SkillComponent skillComponent;
    private final List<ToolComponent> toolComponents;
    private final SessionPort sessionService;
    private final UsageTrackingPort usageTracker;
    private final UserPreferencesService preferencesService;
    private final CompactionService compactionService;
    private final AutoModeService autoModeService;
    private final ApplicationEventPublisher eventPublisher;
    private final BotProperties properties;

    private static final List<String> KNOWN_COMMANDS = List.of(
            "skills", "tools", "status", "new", "reset", "compact", "help",
            "auto", "goals", "goal", "diary", "tasks");

    public CommandRouter(
            SkillComponent skillComponent,
            List<ToolComponent> toolComponents,
            SessionPort sessionService,
            UsageTrackingPort usageTracker,
            UserPreferencesService preferencesService,
            CompactionService compactionService,
            AutoModeService autoModeService,
            ApplicationEventPublisher eventPublisher,
            BotProperties properties) {
        this.skillComponent = skillComponent;
        this.toolComponents = toolComponents;
        this.sessionService = sessionService;
        this.usageTracker = usageTracker;
        this.preferencesService = preferencesService;
        this.compactionService = compactionService;
        this.autoModeService = autoModeService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        log.info("CommandRouter initialized with commands: {}", KNOWN_COMMANDS);
    }

    @Override
    public CompletableFuture<CommandResult> execute(String command, List<String> args, Map<String, Object> context) {
        return CompletableFuture.supplyAsync(() -> {
            String sessionId = (String) context.get("sessionId");
            String channelType = (String) context.get("channelType");
            String chatId = (String) context.get("chatId");
            log.debug("Executing command: /{} (session={})", command, sessionId);

            return switch (command) {
            case "skills" -> handleSkills();
            case "tools" -> handleTools();
            case "status" -> handleStatus(sessionId);
            case "new", "reset" -> handleNew(sessionId);
            case "compact" -> handleCompact(sessionId, args);
            case "help" -> handleHelp();
            case "auto" -> handleAuto(args, channelType, chatId);
            case "goals" -> handleGoals();
            case "goal" -> handleGoal(args);
            case "diary" -> handleDiary(args);
            case "tasks" -> handleTasks();
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
                new CommandDefinition("status", "Show session status", "/status"),
                new CommandDefinition("new", "Start a new conversation", "/new"),
                new CommandDefinition("reset", "Reset conversation", "/reset"),
                new CommandDefinition("compact", "Compact conversation history", "/compact [keep]"),
                new CommandDefinition("help", "Show available commands", "/help")));
        if (autoModeService.isFeatureEnabled()) {
            commands.add(new CommandDefinition("auto", "Toggle auto mode", "/auto [on|off]"));
            commands.add(new CommandDefinition("goals", "List goals", "/goals"));
            commands.add(new CommandDefinition("goal", "Create a goal", "/goal <description>"));
            commands.add(new CommandDefinition("tasks", "List tasks", "/tasks"));
            commands.add(new CommandDefinition("diary", "Show diary entries", "/diary [count]"));
        }
        return commands;
    }

    private CommandResult handleSkills() {
        List<Skill> skills = skillComponent.getAvailableSkills();
        if (skills.isEmpty()) {
            return CommandResult.success(msg("command.skills.empty"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.skills.title", skills.size()));
        sb.append("\n");

        for (Skill skill : skills) {
            sb.append("\n`").append(skill.getName()).append("`");
            if (!skill.isAvailable()) {
                sb.append(" _(unavailable)_");
            }
            sb.append("\n");
            if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
                sb.append(skill.getDescription()).append("\n");
            }
        }

        return CommandResult.success(sb.toString());
    }

    private CommandResult handleTools() {
        List<ToolComponent> enabledTools = toolComponents.stream()
                .filter(ToolComponent::isEnabled)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.tools.title", enabledTools.size()));
        sb.append("\n");

        for (ToolComponent t : enabledTools) {
            String name = t.getDefinition().getName();
            String desc = t.getDefinition().getDescription().trim()
                    .replaceAll("\\s*\\n\\s*", " ");
            int dotIdx = desc.indexOf(". ");
            if (dotIdx > 0 && dotIdx < 120) {
                desc = desc.substring(0, dotIdx + 1);
            } else if (desc.length() > 120) {
                desc = desc.substring(0, 120) + "...";
            }
            sb.append("\n`").append(name).append("`\n");
            sb.append(desc).append("\n");
        }

        return CommandResult.success(sb.toString());
    }

    private CommandResult handleStatus(String sessionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(msg("command.status.title")).append("**\n\n");

        int messageCount = sessionService.getMessageCount(sessionId);
        sb.append(msg("command.status.messages", messageCount)).append("\n\n");

        try {
            Map<String, UsageStats> statsByModel = usageTracker.getStatsByModel(Duration.ofHours(24));
            if (statsByModel == null || statsByModel.isEmpty()) {
                sb.append(msg("command.status.usage.empty"));
            } else {
                sb.append(msg("command.status.usage.title")).append("\n\n");
                sb.append(renderUsageTable(statsByModel));
            }
        } catch (Exception e) {
            log.debug("Failed to get usage stats", e);
        }

        if (autoModeService.isFeatureEnabled()) {
            sb.append("\n");
            if (autoModeService.isAutoModeEnabled()) {
                sb.append(msg("command.status.auto.title")).append("\n");
                var activeGoals = autoModeService.getActiveGoals();
                sb.append(msg("command.status.auto.goals", activeGoals.size())).append("\n");
                autoModeService.getNextPendingTask()
                        .ifPresent(task -> sb.append(msg("command.status.auto.task", task.getTitle())).append("\n"));
            } else {
                sb.append(msg("command.status.auto.off")).append("\n");
            }
        }

        return CommandResult.success(sb.toString());
    }

    private String renderUsageTable(Map<String, UsageStats> statsByModel) {
        String colModel = msg("command.status.usage.col.model");
        String colReq = msg("command.status.usage.col.requests");
        String colTokens = msg("command.status.usage.col.tokens");
        String totalLabel = msg("command.status.usage.total");

        List<Map.Entry<String, UsageStats>> sorted = statsByModel.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, UsageStats>>comparingLong(e -> e.getValue().getTotalTokens())
                        .reversed())
                .toList();

        long totalReq = 0;
        long totalTokens = 0;

        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(colModel).append(" | ").append(colReq).append(" | ").append(colTokens).append(" |\n");
        sb.append("|---|---|---|\n");

        for (Map.Entry<String, UsageStats> entry : sorted) {
            String key = entry.getKey();
            String modelName = key.contains("/") ? key.substring(key.indexOf('/') + 1) : key;
            UsageStats stats = entry.getValue();
            totalReq += stats.getTotalRequests();
            totalTokens += stats.getTotalTokens();
            sb.append("| ").append(modelName)
                    .append(" | ").append(stats.getTotalRequests())
                    .append(" | ").append(formatTokens(stats.getTotalTokens()))
                    .append(" |\n");
        }

        sb.append("| **").append(totalLabel).append("** | **").append(totalReq)
                .append("** | **").append(formatTokens(totalTokens)).append("** |\n");

        return sb.toString();
    }

    static String formatTokens(long tokens) {
        if (tokens >= 1_000_000) {
            return String.format("%.1fM", tokens / 1_000_000.0);
        } else if (tokens >= 1_000) {
            return String.format("%.1fK", tokens / 1_000.0);
        }
        return String.valueOf(tokens);
    }

    private CommandResult handleNew(String sessionId) {
        sessionService.clearMessages(sessionId);
        return CommandResult.success(msg("command.new.done"));
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

        List<Message> toSummarize = sessionService.getMessagesToCompact(sessionId, keepLast);
        if (toSummarize.isEmpty()) {
            int count = sessionService.getMessageCount(sessionId);
            return CommandResult.success(msg("command.compact.nothing", count));
        }

        String summary = compactionService.summarize(toSummarize);
        int removed;
        if (summary != null) {
            Message summaryMsg = compactionService.createSummaryMessage(summary);
            removed = sessionService.compactWithSummary(sessionId, keepLast, summaryMsg);
            log.info("[Compact] Summarized {} messages for session {}", removed, sessionId);
        } else {
            removed = sessionService.compactMessages(sessionId, keepLast);
            log.info("[Compact] Truncated {} messages (no LLM) for session {}", removed, sessionId);
        }

        if (removed <= 0) {
            int count = sessionService.getMessageCount(sessionId);
            return CommandResult.success(msg("command.compact.nothing", count));
        }

        String resultKey = summary != null ? "command.compact.done.summary" : "command.compact.done";
        return CommandResult.success(msg(resultKey, removed, keepLast));
    }

    private CommandResult handleHelp() {
        return CommandResult.success(msg("command.help.text"));
    }

    // ==================== Auto Mode Commands ====================

    private CommandResult handleAuto(List<String> args, String channelType, String chatId) {
        if (!autoModeService.isFeatureEnabled()) {
            return CommandResult.success(msg("command.auto.not-available"));
        }

        if (args.isEmpty()) {
            boolean enabled = autoModeService.isAutoModeEnabled();
            return CommandResult.success(msg("command.auto.status", enabled ? "ON" : "OFF"));
        }

        String subcommand = args.get(0).toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "on" -> {
                autoModeService.enableAutoMode();
                if (channelType != null && chatId != null) {
                    eventPublisher.publishEvent(new AutoModeChannelRegisteredEvent(channelType, chatId));
                }
                int interval = properties.getAuto().getIntervalMinutes();
                yield CommandResult.success(msg("command.auto.enabled", interval));
            }
            case "off" -> {
                autoModeService.disableAutoMode();
                yield CommandResult.success(msg("command.auto.disabled"));
            }
            default -> CommandResult.success(msg("command.auto.usage"));
        };
    }

    private CommandResult handleGoals() {
        if (!autoModeService.isFeatureEnabled()) {
            return CommandResult.success(msg("command.auto.not-available"));
        }

        List<Goal> goals = autoModeService.getGoals();
        if (goals.isEmpty()) {
            return CommandResult.success(msg("command.goals.empty"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.goals.title", goals.size())).append("\n\n");

        for (Goal goal : goals) {
            long completed = goal.getCompletedTaskCount();
            int total = goal.getTasks().size();
            String statusIcon = switch (goal.getStatus()) {
            case ACTIVE -> "\u25B6\uFE0F";
            case COMPLETED -> "\u2705";
            case PAUSED -> "\u23F8\uFE0F";
            case CANCELLED -> "\u274C";
            };
            sb.append(String.format("%s **%s** [%s] (%d/%d tasks)%n",
                    statusIcon, goal.getTitle(), goal.getStatus(), completed, total));
            if (goal.getDescription() != null && !goal.getDescription().isBlank()) {
                sb.append("  ").append(goal.getDescription()).append("\n");
            }
        }

        return CommandResult.success(sb.toString());
    }

    private CommandResult handleGoal(List<String> args) {
        if (!autoModeService.isFeatureEnabled()) {
            return CommandResult.success(msg("command.auto.not-available"));
        }

        if (args.isEmpty()) {
            return CommandResult.success(msg("command.goals.empty"));
        }

        String description = String.join(" ", args);
        try {
            Goal goal = autoModeService.createGoal(description, null);
            return CommandResult.success(msg("command.goal.created", goal.getTitle()));
        } catch (IllegalStateException e) {
            return CommandResult.failure(msg("command.goal.limit",
                    properties.getAuto().getMaxGoals()));
        }
    }

    private CommandResult handleTasks() {
        if (!autoModeService.isFeatureEnabled()) {
            return CommandResult.success(msg("command.auto.not-available"));
        }

        List<Goal> goals = autoModeService.getGoals();
        boolean hasTasks = goals.stream().anyMatch(g -> !g.getTasks().isEmpty());
        if (goals.isEmpty() || !hasTasks) {
            return CommandResult.success(msg("command.tasks.empty"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.tasks.title")).append("\n\n");

        for (Goal goal : goals) {
            if (goal.getTasks().isEmpty())
                continue;

            sb.append(msg("command.tasks.goal", goal.getTitle()));
            sb.append(" [").append(goal.getStatus()).append("]\n");

            List<AutoTask> sortedTasks = goal.getTasks().stream()
                    .sorted(Comparator.comparingInt(AutoTask::getOrder))
                    .toList();

            for (AutoTask task : sortedTasks) {
                String icon = switch (task.getStatus()) {
                case PENDING -> "[ ]";
                case IN_PROGRESS -> "[>]";
                case COMPLETED -> "[x]";
                case FAILED -> "[!]";
                case SKIPPED -> "[-]";
                };
                sb.append("  ").append(icon).append(" ").append(task.getTitle()).append("\n");
            }
            sb.append("\n");
        }

        return CommandResult.success(sb.toString().trim());
    }

    private CommandResult handleDiary(List<String> args) {
        if (!autoModeService.isFeatureEnabled()) {
            return CommandResult.success(msg("command.auto.not-available"));
        }

        int count = 10;
        if (!args.isEmpty()) {
            try {
                count = Integer.parseInt(args.get(0));
                count = Math.max(1, Math.min(count, 50));
            } catch (NumberFormatException ignored) {
            }
        }

        List<DiaryEntry> entries = autoModeService.getRecentDiary(count);
        if (entries.isEmpty()) {
            return CommandResult.success(msg("command.diary.empty"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.diary.title", entries.size())).append("\n\n");

        java.time.format.DateTimeFormatter timeFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                .withZone(java.time.ZoneOffset.UTC);

        for (DiaryEntry entry : entries) {
            sb.append("[").append(timeFormat.format(entry.getTimestamp())).append("] ");
            sb.append("**").append(entry.getType()).append("**: ");
            sb.append(entry.getContent()).append("\n");
        }

        return CommandResult.success(sb.toString());
    }

    private String msg(String key, Object... args) {
        return preferencesService.getMessage(key, args);
    }
}
