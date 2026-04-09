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

import me.golemcore.bot.application.command.AutomationCommandService;
import me.golemcore.bot.application.command.ModelSelectionCommandService;
import me.golemcore.bot.application.command.PlanCommandService;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.AutoModeChannelRegisteredEvent;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PlanStep;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.UsageStats;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.CompactionOrchestrationService;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.SessionRunCoordinator;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.inbound.CommandPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import me.golemcore.bot.tools.ScheduleSessionActionTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * <li>/goal &lt;desc&gt; - Create goal (if auto mode enabled)
 * <li>/tasks - List tasks (if auto mode enabled)
 * <li>/diary [N] - Show diary entries (if auto mode enabled)
 * <li>/schedule - Manage cron-based schedules (if auto mode enabled)
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
    private static final int MAX_TOOL_DESC_LENGTH = 120;
    private static final long TOKEN_MILLION_THRESHOLD = 1_000_000;
    private static final long TOKEN_THOUSAND_THRESHOLD = 1_000;
    private static final String DOUBLE_NEWLINE = "\n\n";
    private static final String TABLE_SEPARATOR = " | ";
    private static final String MSG_AUTO_NOT_AVAILABLE = "command.auto.not-available";
    private static final String MSG_PLAN_NOT_AVAILABLE = "command.plan.not-available";
    private static final String CMD_HELP = "help";
    private static final String CMD_GOAL = "goal";
    private static final String CMD_PLAN = "plan";
    private static final String CMD_PLANS = "plans";
    private static final String CMD_LATER = "later";
    private static final String CMD_STATUS = "status";
    private static final int MIN_REASONING_ARGS = 2;
    private static final String SUBCMD_LIST = "list";
    private static final DateTimeFormatter LATER_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");
    private static final String SUBCMD_RESET = "reset";
    private static final String SUBCMD_REASONING = "reasoning";

    private final SkillComponent skillComponent;
    private final List<ToolComponent> toolComponents;
    private final SessionPort sessionService;
    private final UsageTrackingPort usageTracker;
    private final UserPreferencesService preferencesService;
    private final CompactionOrchestrationService compactionOrchestrationService;
    private final AutomationCommandService automationCommandService;
    private final ModelSelectionCommandService modelSelectionCommandService;
    private final PlanCommandService planCommandService;
    private final DelayedActionPolicyService delayedActionPolicyService;
    private final SessionRunCoordinator runCoordinator;
    private final ApplicationEventPublisher eventPublisher;
    private final RuntimeConfigService runtimeConfigService;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;

    private static final List<String> KNOWN_COMMANDS = List.of(
            "skills", "tools", CMD_STATUS, "new", SUBCMD_RESET, "compact", CMD_HELP,
            "tier", "model", "sessions", "auto", "goals", CMD_GOAL, "diary", "tasks", "schedule", CMD_LATER,
            CMD_PLAN, CMD_PLANS, "stop");

    public CommandRouter(
            SkillComponent skillComponent,
            List<ToolComponent> toolComponents,
            SessionPort sessionService,
            UsageTrackingPort usageTracker,
            UserPreferencesService preferencesService,
            CompactionOrchestrationService compactionOrchestrationService,
            AutomationCommandService automationCommandService,
            ModelSelectionCommandService modelSelectionCommandService,
            PlanCommandService planCommandService,
            DelayedActionPolicyService delayedActionPolicyService,
            SessionRunCoordinator runCoordinator,
            ApplicationEventPublisher eventPublisher,
            RuntimeConfigService runtimeConfigService,
            ObjectProvider<BuildProperties> buildPropertiesProvider) {
        this.skillComponent = skillComponent;
        this.toolComponents = toolComponents;
        this.sessionService = sessionService;
        this.usageTracker = usageTracker;
        this.preferencesService = preferencesService;
        this.compactionOrchestrationService = compactionOrchestrationService;
        this.automationCommandService = automationCommandService;
        this.modelSelectionCommandService = modelSelectionCommandService;
        this.planCommandService = planCommandService;
        this.delayedActionPolicyService = delayedActionPolicyService;
        this.runCoordinator = runCoordinator;
        this.eventPublisher = eventPublisher;
        this.runtimeConfigService = runtimeConfigService;
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
            case "tier" -> handleTier(args);
            case "model" -> handleModel(args);
            case "sessions" -> handleSessions(channelType);
            case "auto" -> handleAuto(args, channelType, autoSessionChatId, transportChatId);
            case "goals" -> handleGoals();
            case CMD_GOAL -> handleGoal(args);
            case "diary" -> handleDiary(args);
            case "tasks" -> handleTasks();
            case "schedule" -> handleSchedule(args);
            case CMD_LATER -> handleLater(args, channelType, conversationKey);
            case CMD_PLAN -> handlePlan(args, sessionIdentity, transportChatId);
            case CMD_PLANS -> handlePlans(sessionIdentity);
            case "stop" -> handleStop(channelType, sessionChatId);
            default -> CommandResult.failure(msg("command.unknown", command));
            };
        });
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

    private CommandResult handleTier(List<String> args) {
        if (args.isEmpty()) {
            return renderTierOutcome(
                    modelSelectionCommandService.handleTier(new ModelSelectionCommandService.ShowTierStatus()));
        }

        String tier = ModelTierCatalog.normalizeTierId(args.get(0));
        boolean force = args.size() > 1 && "force".equalsIgnoreCase(args.get(1));
        return renderTierOutcome(modelSelectionCommandService.handleTier(
                new ModelSelectionCommandService.SetTierSelection(tier, force)));
    }

    private CommandResult handleModel(List<String> args) {
        if (args.isEmpty()) {
            return renderModelOutcome(
                    modelSelectionCommandService.handleModel(new ModelSelectionCommandService.ShowModelSelection()));
        }

        String subcommand = ModelTierCatalog.normalizeTierId(args.get(0));
        if (SUBCMD_LIST.equals(subcommand)) {
            return renderModelOutcome(
                    modelSelectionCommandService.handleModel(new ModelSelectionCommandService.ListAvailableModels()));
        }
        if (!ModelTierCatalog.isExplicitSelectableTier(subcommand)) {
            return CommandResult.success(msg("command.model.invalid.tier"));
        }

        List<String> subArgs = args.subList(1, args.size());
        if (subArgs.isEmpty()) {
            return CommandResult.success(msg("command.model.usage"));
        }

        String action = subArgs.get(0).toLowerCase(Locale.ROOT);
        if (SUBCMD_RESET.equals(action)) {
            return renderModelOutcome(modelSelectionCommandService.handleModel(
                    new ModelSelectionCommandService.ResetModelOverride(subcommand)));
        }
        if (SUBCMD_REASONING.equals(action)) {
            if (subArgs.size() < MIN_REASONING_ARGS) {
                return CommandResult.success(msg("command.model.usage"));
            }
            return renderModelOutcome(modelSelectionCommandService.handleModel(
                    new ModelSelectionCommandService.SetReasoningLevel(subcommand,
                            subArgs.get(1).toLowerCase(Locale.ROOT))));
        }

        return renderModelOutcome(modelSelectionCommandService.handleModel(
                new ModelSelectionCommandService.SetModelOverride(subcommand, subArgs.get(0))));
    }

    private CommandResult renderTierOutcome(ModelSelectionCommandService.TierOutcome outcome) {
        if (outcome instanceof ModelSelectionCommandService.CurrentTier currentTier) {
            String force = currentTier.force() ? "on" : "off";
            return CommandResult.success(msg("command.tier.current", currentTier.tier(), force));
        }
        if (outcome instanceof ModelSelectionCommandService.TierUpdated tierUpdated) {
            if (tierUpdated.force()) {
                return CommandResult.success(msg("command.tier.set.force", tierUpdated.tier()));
            }
            return CommandResult.success(msg("command.tier.set", tierUpdated.tier()));
        }
        return CommandResult.success(msg("command.tier.invalid"));
    }

    private CommandResult renderModelOutcome(ModelSelectionCommandService.ModelOutcome outcome) {
        if (outcome instanceof ModelSelectionCommandService.ModelSelectionOverview overview) {
            return CommandResult.success(renderModelOverview(overview));
        }
        if (outcome instanceof ModelSelectionCommandService.AvailableModels availableModels) {
            return CommandResult.success(renderAvailableModels(availableModels));
        }
        if (outcome instanceof ModelSelectionCommandService.InvalidModelTier) {
            return CommandResult.success(msg("command.model.invalid.tier"));
        }
        if (outcome instanceof ModelSelectionCommandService.ModelOverrideSet modelOverrideSet) {
            String displayReasoning = modelOverrideSet.defaultReasoning() != null
                    ? " (reasoning: " + modelOverrideSet.defaultReasoning() + ")"
                    : "";
            return CommandResult.success(
                    msg("command.model.set", modelOverrideSet.tier(), modelOverrideSet.modelSpec()) + displayReasoning);
        }
        if (outcome instanceof ModelSelectionCommandService.ProviderNotConfigured providerNotConfigured) {
            return CommandResult.success(msg(
                    "command.model.invalid.provider",
                    providerNotConfigured.modelSpec(),
                    String.join(", ", providerNotConfigured.configuredProviders())));
        }
        if (outcome instanceof ModelSelectionCommandService.InvalidModel invalidModel) {
            return CommandResult.success(msg("command.model.invalid.model", invalidModel.modelSpec()));
        }
        if (outcome instanceof ModelSelectionCommandService.MissingModelOverride missingModelOverride) {
            return CommandResult.success(msg("command.model.no.override", missingModelOverride.tier()));
        }
        if (outcome instanceof ModelSelectionCommandService.MissingReasoningSupport missingReasoningSupport) {
            return CommandResult.success(msg("command.model.no.reasoning", missingReasoningSupport.modelSpec()));
        }
        if (outcome instanceof ModelSelectionCommandService.InvalidReasoningLevel invalidReasoningLevel) {
            return CommandResult.success(msg(
                    "command.model.invalid.reasoning",
                    invalidReasoningLevel.requestedLevel(),
                    String.join(", ", invalidReasoningLevel.availableLevels())));
        }
        if (outcome instanceof ModelSelectionCommandService.ModelReasoningSet modelReasoningSet) {
            return CommandResult.success(msg(
                    "command.model.set.reasoning",
                    modelReasoningSet.tier(),
                    modelReasoningSet.level()));
        }
        ModelSelectionCommandService.ModelOverrideReset modelOverrideReset = (ModelSelectionCommandService.ModelOverrideReset) outcome;
        return CommandResult.success(msg("command.model.reset", modelOverrideReset.tier()));
    }

    private String renderModelOverview(ModelSelectionCommandService.ModelSelectionOverview overview) {
        StringBuilder builder = new StringBuilder();
        builder.append("**").append(msg("command.model.show.title")).append("**\n\n");

        for (ModelSelectionCommandService.TierSelection tierSelection : overview.tiers()) {
            String model = tierSelection.model() != null ? tierSelection.model() : "—";
            String reasoning = tierSelection.reasoning() != null ? tierSelection.reasoning() : "—";
            String messageKey = tierSelection.hasOverride()
                    ? "command.model.show.tier.override"
                    : "command.model.show.tier.default";
            builder.append(msg(messageKey, tierSelection.tier(), model, reasoning)).append("\n");
        }

        return builder.toString();
    }

    private String renderAvailableModels(ModelSelectionCommandService.AvailableModels availableModels) {
        if (availableModels.modelsByProvider().isEmpty()) {
            return msg("command.model.list.title") + "\n\nNo models available.";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("**").append(msg("command.model.list.title")).append("**\n\n");

        for (Map.Entry<String, List<ModelSelectionCommandService.AvailableModelOption>> entry : availableModels
                .modelsByProvider().entrySet()) {
            builder.append(msg("command.model.list.provider", entry.getKey())).append("\n");
            for (ModelSelectionCommandService.AvailableModelOption model : entry.getValue()) {
                String reasoningInfo = model.hasReasoning()
                        ? " [reasoning: " + String.join(", ", model.reasoningLevels()) + "]"
                        : "";
                builder.append(msg("command.model.list.model", model.id(), model.displayName(), reasoningInfo))
                        .append("\n");
            }
            builder.append("\n");
        }
        return builder.toString();
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
                new CommandDefinition("tier", "Set model tier",
                        "/tier [" + String.join("|", ModelTierCatalog.orderedExplicitTiers()) + "] [force]"),
                new CommandDefinition("model", "Per-tier model selection",
                        "/model [list|<tier> <model>|<tier> reasoning <level>|<tier> reset]"),
                new CommandDefinition("sessions", "Open session switcher menu (Telegram)", "/sessions"),
                new CommandDefinition("stop", "Stop current run", "/stop")));
        if (automationCommandService.isLaterFeatureEnabled()) {
            commands.add(new CommandDefinition(CMD_LATER, "Reminders and follow-ups", "/later [list|cancel|now|help]"));
        }
        if (automationCommandService.isAutoFeatureEnabled()) {
            commands.add(new CommandDefinition("auto", "Toggle auto mode", "/auto [on|off]"));
            commands.add(new CommandDefinition("goals", "List goals", "/goals"));
            commands.add(new CommandDefinition(CMD_GOAL, "Create a goal", "/goal <description>"));
            commands.add(new CommandDefinition("tasks", "List tasks", "/tasks"));
            commands.add(new CommandDefinition("diary", "Show diary entries", "/diary [count]"));
            commands.add(new CommandDefinition("schedule", "Manage schedules", "/schedule [help]"));
        }
        if (planCommandService.isFeatureEnabled()) {
            commands.add(new CommandDefinition(CMD_PLAN, "Plan work control",
                    "/plan [on|off|done|status|approve|cancel|resume]"));
            commands.add(new CommandDefinition(CMD_PLANS, "List plans", "/plans"));
        }
        return commands;
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

    private CommandResult handleTools(String channelType) {
        List<ToolComponent> enabledTools = toolComponents.stream()
                .filter(ToolComponent::isEnabled)
                .filter(tool -> isToolVisibleForChannel(tool, channelType))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.tools.title", enabledTools.size()));
        sb.append("\n");

        for (ToolComponent t : enabledTools) {
            String name = t.getDefinition().getName();
            String desc = t.getDefinition().getDescription().trim()
                    .replace('\n', ' ').replace('\t', ' ');
            int dotIdx = desc.indexOf(". ");
            if (dotIdx > 0 && dotIdx < MAX_TOOL_DESC_LENGTH) {
                desc = desc.substring(0, dotIdx + 1);
            } else if (desc.length() > MAX_TOOL_DESC_LENGTH) {
                desc = desc.substring(0, MAX_TOOL_DESC_LENGTH) + "...";
            }
            sb.append("\n`").append(name).append("`\n");
            sb.append(desc).append("\n");
        }

        return CommandResult.success(sb.toString());
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
        StringBuilder sb = new StringBuilder();

        BuildProperties buildProps = buildPropertiesProvider.getIfAvailable();
        if (buildProps != null) {
            sb.append(msg("command.status.version", buildProps.getVersion())).append(DOUBLE_NEWLINE);
        }

        sb.append("**").append(msg("command.status.title")).append("**").append(DOUBLE_NEWLINE);

        int messageCount = sessionService.getMessageCount(sessionId);
        sb.append(msg("command.status.messages", messageCount)).append(DOUBLE_NEWLINE);

        try {
            Map<String, UsageStats> statsByModel = usageTracker.getStatsByModel(Duration.ofHours(24));
            if (statsByModel == null || statsByModel.isEmpty()) {
                sb.append(msg("command.status.usage.empty"));
            } else {
                sb.append(msg("command.status.usage.title")).append(DOUBLE_NEWLINE);
                sb.append(renderUsageTable(statsByModel));
            }
        } catch (Exception e) {
            log.debug("Failed to get usage stats", e);
        }

        if (automationCommandService.isAutoFeatureEnabled()) {
            sb.append("\n");
            if (automationCommandService.isAutoModeEnabled()) {
                sb.append(msg("command.status.auto.title")).append("\n");
                List<Goal> activeGoals = automationCommandService.getActiveGoals();
                sb.append(msg("command.status.auto.goals", activeGoals.size())).append("\n");
                automationCommandService.getNextPendingTask()
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
        sb.append("| ").append(colModel).append(TABLE_SEPARATOR).append(colReq).append(TABLE_SEPARATOR)
                .append(colTokens).append(" |\n");
        sb.append("|---|---|---|\n");

        for (Map.Entry<String, UsageStats> entry : sorted) {
            String key = entry.getKey();
            String modelName = key.contains("/") ? key.substring(key.indexOf('/') + 1) : key;
            UsageStats stats = entry.getValue();
            totalReq += stats.getTotalRequests();
            totalTokens += stats.getTotalTokens();
            sb.append("| ").append(modelName)
                    .append(TABLE_SEPARATOR).append(stats.getTotalRequests())
                    .append(TABLE_SEPARATOR).append(formatTokens(stats.getTotalTokens()))
                    .append(" |\n");
        }

        sb.append("| **").append(totalLabel).append("**").append(TABLE_SEPARATOR).append("**").append(totalReq)
                .append("**").append(TABLE_SEPARATOR).append("**").append(formatTokens(totalTokens)).append("** |\n");

        return sb.toString();
    }

    static String formatTokens(long tokens) {
        if (tokens >= TOKEN_MILLION_THRESHOLD) {
            return String.format("%.1fM", tokens / (double) TOKEN_MILLION_THRESHOLD);
        } else if (tokens >= TOKEN_THOUSAND_THRESHOLD) {
            return String.format("%.1fK", tokens / (double) TOKEN_THOUSAND_THRESHOLD);
        }
        return String.valueOf(tokens);
    }

    private CommandResult handleNew() {
        // /new semantics are channel-specific:
        // - Web creates a new conversation on client side before WS send.
        // - Telegram creates/activates a new conversation in TelegramAdapter.
        // Command router keeps backward-compatible acknowledgement and does not
        // mutate current session history.
        return CommandResult.success(msg("command.new.done"));
    }

    private CommandResult handleReset(String sessionId, SessionIdentity sessionIdentity) {
        sessionService.clearMessages(sessionId);
        if (!planCommandService.isFeatureEnabled()) {
            return CommandResult.success(msg("command.reset.done"));
        }
        planCommandService.resetPlanMode(sessionIdentity);
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

    // ==================== Auto Mode Commands ====================

    private CommandResult handleAuto(List<String> args, String channelType, String sessionChatId, String transportChatId) {
        if (!automationCommandService.isAutoFeatureEnabled()) {
            return CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
        }

        if (args.isEmpty()) {
            AutomationCommandService.AutoOutcome outcome = automationCommandService.getAutoStatus();
            AutomationCommandService.AutoStatus status = (AutomationCommandService.AutoStatus) outcome;
            return CommandResult.success(msg("command.auto.status", status.enabled() ? "ON" : "OFF"));
        }

        String subcommand = args.get(0).toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "on" -> {
                automationCommandService.enableAutoMode();
                if (channelType != null && sessionChatId != null) {
                    eventPublisher.publishEvent(new AutoModeChannelRegisteredEvent(
                            channelType,
                            sessionChatId,
                            transportChatId != null ? transportChatId : sessionChatId));
                }
                yield CommandResult.success(msg("command.auto.enabled"));
            }
            case "off" -> {
                automationCommandService.disableAutoMode();
                yield CommandResult.success(msg("command.auto.disabled"));
            }
            default -> CommandResult.success(msg("command.auto.usage"));
        };
    }

    private CommandResult handleGoals() {
        AutomationCommandService.GoalsOutcome outcome = automationCommandService.getGoals();
        if (outcome instanceof AutomationCommandService.AutoFeatureUnavailable) {
            return CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
        }
        if (outcome instanceof AutomationCommandService.EmptyGoals) {
            return CommandResult.success(msg("command.goals.empty"));
        }
        List<Goal> goals = ((AutomationCommandService.GoalsOverview) outcome).goals();

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.goals.title", goals.size())).append(DOUBLE_NEWLINE);

        for (Goal goal : goals) {
            long completed = goal.getCompletedTaskCount();
            int total = goal.getTasks().size();
            String statusIcon = switch (goal.getStatus()) {
            case ACTIVE -> "▶️";
            case COMPLETED -> "✅";
            case PAUSED -> "⏸️";
            case CANCELLED -> "❌";
            };
            sb.append(String.format("%s **%s** [%s] (%d/%d tasks) `goal_id: %s`%n",
                    statusIcon, goal.getTitle(), goal.getStatus(), completed, total, goal.getId()));
            if (goal.getDescription() != null && !goal.getDescription().isBlank()) {
                sb.append("  ").append(goal.getDescription()).append("\n");
            }
        }

        return CommandResult.success(sb.toString());
    }

    private CommandResult handleGoal(List<String> args) {
        if (args.isEmpty()) {
            return CommandResult.success(msg("command.goals.empty"));
        }

        AutomationCommandService.GoalCreationOutcome outcome = automationCommandService
                .createGoal(String.join(" ", args));
        if (outcome instanceof AutomationCommandService.AutoFeatureUnavailable) {
            return CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
        }
        if (outcome instanceof AutomationCommandService.GoalCreated created) {
            return CommandResult.success(msg("command.goal.created", created.goal().getTitle()));
        }
        if (outcome instanceof AutomationCommandService.GoalLimitReached limitReached) {
            return CommandResult.failure(msg("command.goal.limit",
                    limitReached.maxGoals()));
        }
        return CommandResult.success(msg("command.goals.empty"));
    }

    private CommandResult handleTasks() {
        AutomationCommandService.TasksOutcome outcome = automationCommandService.getTasks();
        if (outcome instanceof AutomationCommandService.AutoFeatureUnavailable) {
            return CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
        }
        if (outcome instanceof AutomationCommandService.EmptyTasks) {
            return CommandResult.success(msg("command.tasks.empty"));
        }
        List<Goal> goals = ((AutomationCommandService.TasksOverview) outcome).goals();

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.tasks.title")).append(DOUBLE_NEWLINE);

        for (Goal goal : goals) {
            if (goal.getTasks().isEmpty())
                continue;

            sb.append(msg("command.tasks.goal", goal.getTitle()));
            sb.append(" [").append(goal.getStatus()).append("] `goal_id: ").append(goal.getId()).append("`\n");

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
                sb.append("  ").append(icon).append(" ").append(task.getTitle())
                        .append(" `task_id: ").append(task.getId()).append("`\n");
            }
            sb.append("\n");
        }

        return CommandResult.success(sb.toString().trim());
    }

    private CommandResult handleDiary(List<String> args) {
        int count = 10;
        if (!args.isEmpty()) {
            try {
                count = Integer.parseInt(args.get(0));
                count = Math.max(1, Math.min(count, 50));
            } catch (NumberFormatException ignored) {
            }
        }

        AutomationCommandService.DiaryOutcome outcome = automationCommandService.getDiary(count);
        if (outcome instanceof AutomationCommandService.AutoFeatureUnavailable) {
            return CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
        }
        if (outcome instanceof AutomationCommandService.EmptyDiary) {
            return CommandResult.success(msg("command.diary.empty"));
        }
        List<DiaryEntry> entries = ((AutomationCommandService.DiaryOverview) outcome).entries();

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.diary.title", entries.size())).append(DOUBLE_NEWLINE);

        DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneOffset.UTC);

        for (DiaryEntry entry : entries) {
            sb.append("[").append(timeFormat.format(entry.getTimestamp())).append("] ");
            sb.append("**").append(entry.getType()).append("**: ");
            sb.append(entry.getContent()).append("\n");
        }

        return CommandResult.success(sb.toString());
    }

    // ==================== Plan Mode Commands ====================

    private CommandResult handlePlan(List<String> args, SessionIdentity sessionIdentity, String transportChatId) {
        if (!planCommandService.isFeatureEnabled()) {
            return CommandResult.success(msg(MSG_PLAN_NOT_AVAILABLE));
        }

        if (args.isEmpty()) {
            PlanCommandService.ModeStatus status = (PlanCommandService.ModeStatus) planCommandService
                    .getModeStatus(sessionIdentity);
            return CommandResult.success(msg("command.plan.status", status.active() ? "ON" : "OFF"));
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
        default -> CommandResult.success(msg("command.plan.usage"));
        };
    }

    private CommandResult handlePlanOn(List<String> args, SessionIdentity sessionIdentity, String transportChatId) {
        String modelTier = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : null;
        PlanCommandService.PlanModeOutcome outcome = planCommandService.enablePlanMode(
                sessionIdentity,
                transportChatId,
                modelTier);
        if (outcome instanceof PlanCommandService.AlreadyActive) {
            return CommandResult.success(msg("command.plan.already-active"));
        }
        if (outcome instanceof PlanCommandService.Enabled) {
            String tierMsg = modelTier != null ? " (tier: " + modelTier + ")" : "";
            return CommandResult.success(msg("command.plan.enabled") + tierMsg);
        }
        if (outcome instanceof PlanCommandService.PlanLimitReached) {
            return CommandResult.failure(msg("command.plan.limit", runtimeConfigService.getPlanMaxPlans()));
        }
        return CommandResult.success(msg(MSG_PLAN_NOT_AVAILABLE));
    }

    private CommandResult handlePlanOff(SessionIdentity sessionIdentity) {
        PlanCommandService.PlanModeOutcome outcome = planCommandService.disablePlanMode(sessionIdentity);
        if (outcome instanceof PlanCommandService.NotActive) {
            return CommandResult.success(msg("command.plan.not-active"));
        }
        return CommandResult.success(msg("command.plan.disabled"));
    }

    private CommandResult handlePlanDone(SessionIdentity sessionIdentity) {
        PlanCommandService.PlanModeOutcome outcome = planCommandService.completePlanMode(sessionIdentity);
        if (outcome instanceof PlanCommandService.NotActive) {
            return CommandResult.success(msg("command.plan.not-active"));
        }
        return CommandResult.success(msg("command.plan.done"));
    }

    private CommandResult handlePlanApprove(List<String> args, SessionIdentity sessionIdentity) {
        PlanCommandService.PlanActionOutcome outcome = planCommandService.approvePlan(
                sessionIdentity,
                args.size() > 1 ? args.get(1) : null);
        if (outcome instanceof PlanCommandService.NoReadyPlan) {
            return CommandResult.failure(msg("command.plan.no-ready"));
        }
        if (outcome instanceof PlanCommandService.PlanNotFound planNotFound) {
            return CommandResult.failure(msg("command.plan.not-found", planNotFound.planId()));
        }
        if (outcome instanceof PlanCommandService.Approved approved) {
            return CommandResult.success(msg("command.plan.approved", approved.planId()));
        }
        if (outcome instanceof PlanCommandService.Failure failure) {
            return CommandResult.failure(failure.message());
        }
        return CommandResult.success(msg(MSG_PLAN_NOT_AVAILABLE));
    }

    private CommandResult handlePlanCancel(List<String> args, SessionIdentity sessionIdentity) {
        PlanCommandService.PlanActionOutcome outcome = planCommandService.cancelPlan(
                sessionIdentity,
                args.size() > 1 ? args.get(1) : null);
        if (outcome instanceof PlanCommandService.NoActivePlan) {
            return CommandResult.failure(msg("command.plan.no-active-plan"));
        }
        if (outcome instanceof PlanCommandService.PlanNotFound planNotFound) {
            return CommandResult.failure(msg("command.plan.not-found", planNotFound.planId()));
        }
        if (outcome instanceof PlanCommandService.Cancelled cancelled) {
            return CommandResult.success(msg("command.plan.cancelled", cancelled.planId()));
        }
        return CommandResult.failure(((PlanCommandService.Failure) outcome).message());
    }

    private CommandResult handlePlanResume(List<String> args, SessionIdentity sessionIdentity) {
        PlanCommandService.PlanActionOutcome outcome = planCommandService.resumePlan(
                sessionIdentity,
                args.size() > 1 ? args.get(1) : null);
        if (outcome instanceof PlanCommandService.NoPartialPlan) {
            return CommandResult.failure(msg("command.plan.no-partial"));
        }
        if (outcome instanceof PlanCommandService.PlanNotFound planNotFound) {
            return CommandResult.failure(msg("command.plan.not-found", planNotFound.planId()));
        }
        if (outcome instanceof PlanCommandService.Resumed resumed) {
            return CommandResult.success(msg("command.plan.resumed", resumed.planId()));
        }
        return CommandResult.failure(((PlanCommandService.Failure) outcome).message());
    }

    private CommandResult handlePlanStatus(List<String> args, SessionIdentity sessionIdentity) {
        PlanCommandService.PlanOverviewOutcome outcome = planCommandService.getPlanStatus(
                sessionIdentity,
                args.size() > 1 ? args.get(1) : null);
        if (outcome instanceof PlanCommandService.EmptyPlans) {
            return CommandResult.success(msg("command.plans.empty"));
        }
        if (outcome instanceof PlanCommandService.PlanNotFound planNotFound) {
            return CommandResult.failure(msg("command.plan.not-found", planNotFound.planId()));
        }
        return CommandResult.success(formatPlanStatus(((PlanCommandService.PlanDetails) outcome).plan()));
    }

    private CommandResult handlePlans(SessionIdentity sessionIdentity) {
        if (!planCommandService.isFeatureEnabled()) {
            return CommandResult.success(msg(MSG_PLAN_NOT_AVAILABLE));
        }
        PlanCommandService.PlanOverviewOutcome outcome = planCommandService.listPlans(sessionIdentity);
        if (outcome instanceof PlanCommandService.EmptyPlans) {
            return CommandResult.success(msg("command.plans.empty"));
        }
        List<Plan> plans = ((PlanCommandService.PlansOverview) outcome).plans();

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.plans.title", plans.size())).append(DOUBLE_NEWLINE);

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
            sb.append(String.format("%s `%s` [%s] (%d/%d steps)%n",
                    statusIcon, plan.getId().substring(0, 8), plan.getStatus(), completed, total));
            if (plan.getTitle() != null) {
                sb.append("  ").append(plan.getTitle()).append("\n");
            }
        }

        return CommandResult.success(sb.toString());
    }

    private String formatPlanStatus(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Plan** `").append(plan.getId().substring(0, 8)).append("`\n");
        sb.append("Status: ").append(plan.getStatus()).append("\n");
        if (plan.getModelTier() != null) {
            sb.append("Tier: ").append(plan.getModelTier()).append("\n");
        }
        sb.append("Steps: ").append(plan.getSteps().size()).append("\n\n");

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
            sb.append("  ").append(icon).append(" `").append(step.getToolName()).append("`");
            if (step.getDescription() != null) {
                sb.append(" — ").append(step.getDescription());
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    // ==================== Schedule Commands ====================

    private CommandResult handleSchedule(List<String> args) {
        if (!automationCommandService.isAutoFeatureEnabled()) {
            return CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
        }

        if (args.isEmpty()) {
            return handleScheduleList();
        }

        String subcommand = args.get(0).toLowerCase(Locale.ROOT);
        List<String> subArgs = args.subList(1, args.size());

        return switch (subcommand) {
        case CMD_GOAL -> handleScheduleGoal(subArgs);
        case "task" -> handleScheduleTask(subArgs);
        case "list" -> handleScheduleList();
        case "delete" -> handleScheduleDelete(subArgs);
        case CMD_HELP -> CommandResult.success(msg("command.schedule.help.text"));
        default -> CommandResult.success(msg("command.schedule.usage"));
        };
    }

    private CommandResult handleScheduleGoal(List<String> args) {
        if (args.isEmpty()) {
            return CommandResult.success(msg("command.schedule.goal.usage"));
        }
        String goalId = args.get(0);
        AutomationCommandService.ScheduleOutcome outcome = automationCommandService.createGoalSchedule(goalId, args);
        return renderScheduleOutcome(outcome, goalId);
    }

    private CommandResult handleScheduleTask(List<String> args) {
        if (args.isEmpty()) {
            return CommandResult.success(msg("command.schedule.task.usage"));
        }
        String taskId = args.get(0);
        AutomationCommandService.ScheduleOutcome outcome = automationCommandService.createTaskSchedule(taskId, args);
        return renderScheduleOutcome(outcome, taskId);
    }

    private CommandResult handleScheduleList() {
        AutomationCommandService.ScheduleOutcome outcome = automationCommandService.listSchedules();
        if (outcome instanceof AutomationCommandService.EmptySchedules) {
            return CommandResult.success(msg("command.schedule.list.empty"));
        }
        List<ScheduleEntry> schedules = ((AutomationCommandService.SchedulesOverview) outcome).schedules();

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.schedule.list.title", schedules.size())).append(DOUBLE_NEWLINE);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneOffset.UTC);

        for (ScheduleEntry entry : schedules) {
            String enabledIcon = entry.isEnabled() ? "✅" : "❌";
            sb.append(String.format("%s `%s` [%s] -> %s%n",
                    enabledIcon, entry.getId(), entry.getType(), entry.getTargetId()));
            sb.append("  Cron: `").append(entry.getCronExpression()).append("`");
            if (entry.getMaxExecutions() > 0) {
                sb.append(String.format(" (%d/%d runs)", entry.getExecutionCount(), entry.getMaxExecutions()));
            } else {
                sb.append(String.format(" (%d runs)", entry.getExecutionCount()));
            }
            sb.append("\n");
            if (entry.getNextExecutionAt() != null) {
                sb.append("  Next: ").append(formatter.format(entry.getNextExecutionAt())).append(" UTC\n");
            }
        }

        return CommandResult.success(sb.toString());
    }

    private CommandResult handleScheduleDelete(List<String> args) {
        AutomationCommandService.ScheduleOutcome outcome = automationCommandService.deleteSchedule(
                args.isEmpty() ? null : args.get(0));
        if (outcome instanceof AutomationCommandService.DeleteScheduleUsage) {
            return CommandResult.success(msg("command.schedule.delete.usage"));
        }
        if (outcome instanceof AutomationCommandService.ScheduleDeleted deleted) {
            return CommandResult.success(msg("command.schedule.deleted", deleted.scheduleId()));
        }
        AutomationCommandService.ScheduleNotFound scheduleNotFound = (AutomationCommandService.ScheduleNotFound) outcome;
        return CommandResult.failure(msg("command.schedule.not-found", scheduleNotFound.scheduleId()));
    }

    private CommandResult handleLater(List<String> args, String channelType, String conversationKey) {
        if (!automationCommandService.isLaterFeatureEnabled()) {
            return CommandResult.success(msg("command.later.not-available"));
        }
        if (args.isEmpty()) {
            return CommandResult.success(msg("command.later.usage"));
        }

        String subcommand = args.get(0).toLowerCase(Locale.ROOT);
        return switch (subcommand) {
        case SUBCMD_LIST -> handleLaterList(channelType, conversationKey);
        case "cancel" -> handleLaterCancel(args.subList(1, args.size()), channelType, conversationKey);
        case "now" -> handleLaterRunNow(args.subList(1, args.size()), channelType, conversationKey);
        case CMD_HELP -> CommandResult.success(msg("command.later.help.text"));
        default -> CommandResult.success(msg("command.later.usage"));
        };
    }

    private CommandResult handleLaterList(String channelType, String conversationKey) {
        AutomationCommandService.LaterOutcome outcome = automationCommandService.listLaterActions(
                channelType,
                conversationKey);
        if (outcome instanceof AutomationCommandService.LaterUnavailable) {
            return CommandResult.success(msg("command.later.not-available"));
        }
        if (outcome instanceof AutomationCommandService.EmptyLaterActions) {
            return CommandResult.success(msg("command.later.list.empty"));
        }
        List<DelayedSessionAction> actions = ((AutomationCommandService.LaterActionsOverview) outcome).actions();

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.later.list.title", actions.size())).append(DOUBLE_NEWLINE);
        for (DelayedSessionAction action : actions) {
            sb.append("`").append(action.getId()).append("` ");
            sb.append(resolveLaterSummary(action)).append("\n");
            sb.append(msg("command.later.list.status")).append(": ");
            sb.append(resolveLaterStatus(action)).append("\n");
            sb.append(msg("command.later.list.next-check")).append(": ");
            sb.append(formatLaterRunAt(action));
            if (action.isCancelOnUserActivity()) {
                sb.append("\n").append(msg("command.later.list.cancel-on-activity"));
            }
            sb.append(DOUBLE_NEWLINE);
        }
        return CommandResult.success(sb.toString().stripTrailing());
    }

    private CommandResult handleLaterCancel(List<String> args, String channelType, String conversationKey) {
        AutomationCommandService.LaterActionOutcome outcome = automationCommandService.cancelLaterAction(
                args.isEmpty() ? null : args.get(0),
                channelType,
                conversationKey);
        if (outcome instanceof AutomationCommandService.LaterUnavailable) {
            return CommandResult.success(msg("command.later.not-available"));
        }
        if (outcome instanceof AutomationCommandService.LaterCancelUsage) {
            return CommandResult.success(msg("command.later.cancel.usage"));
        }
        if (outcome instanceof AutomationCommandService.LaterCancelled cancelled) {
            return CommandResult.success(msg("command.later.cancelled", cancelled.actionId()));
        }
        return CommandResult.failure(msg(
                "command.later.not-found",
                ((AutomationCommandService.LaterNotFound) outcome).actionId()));
    }

    private CommandResult handleLaterRunNow(List<String> args, String channelType, String conversationKey) {
        AutomationCommandService.LaterActionOutcome outcome = automationCommandService.runLaterActionNow(
                args.isEmpty() ? null : args.get(0),
                channelType,
                conversationKey);
        if (outcome instanceof AutomationCommandService.LaterUnavailable) {
            return CommandResult.success(msg("command.later.not-available"));
        }
        if (outcome instanceof AutomationCommandService.LaterRunNowUsage) {
            return CommandResult.success(msg("command.later.now.usage"));
        }
        if (outcome instanceof AutomationCommandService.LaterRunNow runNow) {
            return CommandResult.success(msg("command.later.now.done", runNow.actionId()));
        }
        return CommandResult.failure(msg(
                "command.later.not-found",
                ((AutomationCommandService.LaterNotFound) outcome).actionId()));
    }

    private CommandResult renderScheduleOutcome(AutomationCommandService.ScheduleOutcome outcome, String targetId) {
        if (outcome instanceof AutomationCommandService.GoalScheduleUsage) {
            return CommandResult.success(msg("command.schedule.goal.usage"));
        }
        if (outcome instanceof AutomationCommandService.TaskScheduleUsage) {
            return CommandResult.success(msg("command.schedule.task.usage"));
        }
        if (outcome instanceof AutomationCommandService.GoalNotFound goalNotFound) {
            return CommandResult.failure(msg("command.schedule.goal.not-found", goalNotFound.goalId()));
        }
        if (outcome instanceof AutomationCommandService.TaskNotFound taskNotFound) {
            return CommandResult.failure(msg("command.schedule.task.not-found", taskNotFound.taskId()));
        }
        if (outcome instanceof AutomationCommandService.InvalidCron invalidCron) {
            return CommandResult.failure(msg("command.schedule.invalid-cron", invalidCron.message()));
        }
        AutomationCommandService.ScheduleCreated created = (AutomationCommandService.ScheduleCreated) outcome;
        return CommandResult.success(msg("command.schedule.created",
                created.scheduleId(), created.cronExpression()));
    }

    private String msg(String key, Object... args) {
        return preferencesService.getMessage(key, args);
    }

    private String resolveLaterSummary(DelayedSessionAction action) {
        String humanSummary = payloadString(action, "humanSummary");
        if (humanSummary != null) {
            return humanSummary;
        }
        String message = payloadString(action, "message");
        String originalSummary = payloadString(action, "originalSummary");
        if (action.getKind() == null) {
            return msg("command.later.kind.default");
        }
        return switch (action.getKind()) {
        case REMIND_LATER -> message != null ? msg("command.later.kind.reminder.with-message", message)
                : msg("command.later.kind.reminder");
        case RUN_LATER -> originalSummary != null ? msg("command.later.kind.check-back.with-summary", originalSummary)
                : msg("command.later.kind.check-back");
        case NOTIFY_JOB_READY -> message != null ? message : msg("command.later.kind.job-result");
        };
    }

    private String resolveLaterStatus(DelayedSessionAction action) {
        if (action == null || action.getStatus() == null) {
            return msg("command.later.status.unknown");
        }
        return switch (action.getStatus()) {
        case SCHEDULED -> msg("command.later.status.scheduled");
        case LEASED -> msg("command.later.status.leased");
        case COMPLETED -> msg("command.later.status.completed");
        case CANCELLED -> msg("command.later.status.cancelled");
        case DEAD_LETTER -> msg("command.later.status.dead-letter");
        };
    }

    private String formatLaterRunAt(DelayedSessionAction action) {
        if (action == null || action.getRunAt() == null) {
            return msg("command.later.list.no-time");
        }
        ZoneId zoneId = resolveUserZoneId();
        return LATER_TIME_FORMATTER.format(action.getRunAt().atZone(zoneId));
    }

    private ZoneId resolveUserZoneId() {
        try {
            UserPreferences preferences = preferencesService.getPreferences();
            if (preferences != null && preferences.getTimezone() != null && !preferences.getTimezone().isBlank()) {
                return ZoneId.of(preferences.getTimezone().trim());
            }
        } catch (RuntimeException e) {
            log.debug("Falling back to UTC for delayed action time formatting: {}", e.getMessage());
        }
        return ZoneOffset.UTC;
    }

    private String payloadString(DelayedSessionAction action, String key) {
        if (action == null || action.getPayload() == null) {
            return null;
        }
        Object value = action.getPayload().get(key);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue.trim() : null;
    }
}
