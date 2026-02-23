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
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PlanStep;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.UsageStats;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.CompactionService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanExecutionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.SessionRunCoordinator;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.CorePortResolver;
import me.golemcore.bot.port.outbound.ToolCatalogPort;
import me.golemcore.bot.port.inbound.CommandPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final String CMD_STATUS = "status";
    private static final int MIN_SCHEDULE_ARGS = 2;
    private static final int MIN_CRON_PARTS_FOR_REPEAT_CHECK = 1;
    private static final int MIN_REASONING_ARGS = 2;
    private static final String SUBCMD_LIST = "list";
    private static final String SUBCMD_RESET = "reset";
    private static final String SUBCMD_REASONING = "reasoning";
    private static final String ERR_PROVIDER_NOT_CONFIGURED = "provider.not.configured";
    private static final String ERR_NO_REASONING = "no.reasoning";
    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final String CHANNEL_WEB = "web";

    private final SkillComponent skillComponent;
    private final ToolCatalogPort pluginToolCatalog;
    private final CorePortResolver pluginPortResolver;
    private final SessionPort sessionService;
    private final UserPreferencesService preferencesService;
    private final CompactionService compactionService;
    private final AutoModeService autoModeService;
    private final ModelSelectionService modelSelectionService;
    private final PlanService planService;
    private final PlanExecutionService planExecutionService;
    private final ScheduleService scheduleService;
    private final SessionRunCoordinator runCoordinator;
    private final ApplicationEventPublisher eventPublisher;
    private final BotProperties properties;
    private final RuntimeConfigService runtimeConfigService;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;

    private static final List<String> KNOWN_COMMANDS = List.of(
            "skills", "tools", CMD_STATUS, "new", SUBCMD_RESET, "compact", CMD_HELP,
            "tier", "model", "sessions", "auto", "goals", CMD_GOAL, "diary", "tasks", "schedule",
            CMD_PLAN, CMD_PLANS, "stop");

    private static final Set<String> KNOWN_COMMAND_SET = Set.copyOf(KNOWN_COMMANDS);
    private static final Set<String> TELEGRAM_COMMANDS = Set.copyOf(KNOWN_COMMANDS);
    private static final Set<String> WEB_COMMANDS = Set.of(
            "skills", "tools", CMD_STATUS, "new", SUBCMD_RESET, "compact", CMD_HELP,
            "tier", "model", "auto", "goals", CMD_GOAL, "diary", "tasks", "schedule",
            CMD_PLAN, CMD_PLANS, "stop");

    private static final Set<String> VALID_TIERS = Set.of(
            "balanced", "smart", "coding", "deep");

    public CommandRouter(
            SkillComponent skillComponent,
            ToolCatalogPort pluginToolCatalog,
            CorePortResolver pluginPortResolver,
            SessionPort sessionService,
            UserPreferencesService preferencesService,
            CompactionService compactionService,
            AutoModeService autoModeService,
            ModelSelectionService modelSelectionService,
            PlanService planService,
            PlanExecutionService planExecutionService,
            ScheduleService scheduleService,
            SessionRunCoordinator runCoordinator,
            ApplicationEventPublisher eventPublisher,
            BotProperties properties,
            RuntimeConfigService runtimeConfigService,
            ObjectProvider<BuildProperties> buildPropertiesProvider) {
        this.skillComponent = skillComponent;
        this.pluginToolCatalog = pluginToolCatalog;
        this.pluginPortResolver = pluginPortResolver;
        this.sessionService = sessionService;
        this.preferencesService = preferencesService;
        this.compactionService = compactionService;
        this.autoModeService = autoModeService;
        this.modelSelectionService = modelSelectionService;
        this.planService = planService;
        this.planExecutionService = planExecutionService;
        this.scheduleService = scheduleService;
        this.runCoordinator = runCoordinator;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
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
            String transportChatId = resolveContextString(context, "transportChatId", chatId);
            boolean explicitSessionRouting = hasExplicitContextString(context, "sessionChatId")
                    || hasExplicitContextString(context, "conversationKey");
            SessionIdentity sessionIdentity = explicitSessionRouting
                    ? SessionIdentitySupport.resolveSessionIdentity(channelType, sessionChatId)
                    : null;
            String autoSessionChatId = explicitSessionRouting ? sessionChatId : transportChatId;
            log.debug("Executing command: /{} (session={})", command, sessionId);
            if (!hasCommand(command, channelType)) {
                return CommandResult.failure(msg("command.unknown", command));
            }

            return switch (command) {
            case "skills" -> handleSkills();
            case "tools" -> handleTools();
            case CMD_STATUS -> handleStatus(sessionId);
            case "new" -> handleNew();
            case "reset" -> handleReset(sessionId, sessionIdentity);
            case "compact" -> handleCompact(sessionId, args);
            case CMD_HELP -> handleHelp(channelType);
            case "tier" -> handleTier(args);
            case "model" -> handleModel(args);
            case "sessions" -> handleSessions(channelType);
            case "auto" -> handleAuto(args, channelType, autoSessionChatId, transportChatId);
            case "goals" -> handleGoals();
            case CMD_GOAL -> handleGoal(args);
            case "diary" -> handleDiary(args);
            case "tasks" -> handleTasks();
            case "schedule" -> handleSchedule(args);
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

    @Override
    public boolean hasCommand(String command) {
        return KNOWN_COMMAND_SET.contains(command);
    }

    @Override
    public boolean hasCommand(String command, String channelType) {
        return resolveAllowedCommands(channelType).contains(command);
    }

    @Override
    public List<CommandDefinition> listCommands() {
        return listCommands(CHANNEL_TELEGRAM);
    }

    @Override
    public List<CommandDefinition> listCommands(String channelType) {
        List<CommandDefinition> commands = new ArrayList<>(List.of(
                new CommandDefinition("skills", "List available skills", "/skills"),
                new CommandDefinition("tools", "List enabled tools", "/tools"),
                new CommandDefinition(CMD_STATUS, "Show session status", "/status"),
                new CommandDefinition("new", "Start a new conversation", "/new"),
                new CommandDefinition(SUBCMD_RESET, "Reset conversation", "/reset"),
                new CommandDefinition("compact", "Compact conversation history", "/compact [keep]"),
                new CommandDefinition(CMD_HELP, "Show available commands", "/help"),
                new CommandDefinition("tier", "Set model tier", "/tier [balanced|smart|coding|deep] [force]"),
                new CommandDefinition("model", "Per-tier model selection",
                        "/model [list|<tier> <model>|<tier> reasoning <level>|<tier> reset]"),
                new CommandDefinition("sessions", "Open session switcher menu (Telegram)", "/sessions"),
                new CommandDefinition("stop", "Stop current run", "/stop")));
        if (autoModeService.isFeatureEnabled()) {
            commands.add(new CommandDefinition("auto", "Toggle auto mode", "/auto [on|off]"));
            commands.add(new CommandDefinition("goals", "List goals", "/goals"));
            commands.add(new CommandDefinition(CMD_GOAL, "Create a goal", "/goal <description>"));
            commands.add(new CommandDefinition("tasks", "List tasks", "/tasks"));
            commands.add(new CommandDefinition("diary", "Show diary entries", "/diary [count]"));
            commands.add(new CommandDefinition("schedule", "Manage schedules", "/schedule [help]"));
        }
        if (planService.isFeatureEnabled()) {
            commands.add(new CommandDefinition(CMD_PLAN, "Plan work control",
                    "/plan [on|off|done|status|approve|cancel|resume]"));
            commands.add(new CommandDefinition(CMD_PLANS, "List plans", "/plans"));
        }
        Set<String> allowedCommands = resolveAllowedCommands(channelType);
        return commands.stream()
                .filter(command -> allowedCommands.contains(command.name()))
                .toList();
    }

    private Set<String> resolveAllowedCommands(String channelType) {
        if (CHANNEL_WEB.equals(channelType)) {
            return WEB_COMMANDS;
        }
        if (CHANNEL_TELEGRAM.equals(channelType)) {
            return TELEGRAM_COMMANDS;
        }
        return KNOWN_COMMAND_SET;
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

    private CommandResult handleTools() {
        List<ToolComponent> enabledTools = pluginToolCatalog.getAllTools().stream()
                .filter(ToolComponent::isEnabled)
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
            UsageTrackingPort usageTracker = pluginPortResolver.requireUsageTrackingPort();
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

        if (autoModeService.isFeatureEnabled()) {
            sb.append("\n");
            if (autoModeService.isAutoModeEnabled()) {
                sb.append(msg("command.status.auto.title")).append("\n");
                List<Goal> activeGoals = autoModeService.getActiveGoals();
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
        if (!planService.isFeatureEnabled()) {
            return CommandResult.success(msg("command.reset.done"));
        }

        if (sessionIdentity == null) {
            if (planService.isPlanModeActive()) {
                planService.getActivePlanIdOptional().ifPresent(planService::cancelPlan);
                planService.deactivatePlanMode();
            }
            return CommandResult.success(msg("command.reset.done"));
        }

        if (planService.isPlanModeActive(sessionIdentity)) {
            planService.getActivePlanIdOptional(sessionIdentity).ifPresent(planService::cancelPlan);
            planService.deactivatePlanMode(sessionIdentity);
        }
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

    private CommandResult handleHelp(String channelType) {
        List<CommandDefinition> commands = listCommands(channelType);
        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.help.header")).append("\n");
        for (CommandDefinition command : commands) {
            sb.append(command.usage()).append(" - ").append(command.description()).append("\n");
        }
        return CommandResult.success(sb.toString().trim());
    }

    private CommandResult handleSessions(String channelType) {
        if (!CHANNEL_TELEGRAM.equals(channelType)) {
            return CommandResult.success(msg("command.sessions.not-available"));
        }
        return CommandResult.success(msg("command.sessions.use-menu"));
    }

    // ==================== Tier Command ====================

    private CommandResult handleTier(List<String> args) {
        UserPreferences prefs = preferencesService.getPreferences();

        if (args.isEmpty()) {
            String tier = prefs.getModelTier() != null ? prefs.getModelTier() : "balanced";
            String force = prefs.isTierForce() ? "on" : "off";
            return CommandResult.success(msg("command.tier.current", tier, force));
        }

        String tierArg = args.get(0).toLowerCase(Locale.ROOT);
        if (!VALID_TIERS.contains(tierArg)) {
            return CommandResult.success(msg("command.tier.invalid"));
        }

        boolean force = args.size() > 1 && "force".equalsIgnoreCase(args.get(1));

        prefs.setModelTier(tierArg);
        prefs.setTierForce(force);
        preferencesService.savePreferences(prefs);

        if (force) {
            return CommandResult.success(msg("command.tier.set.force", tierArg));
        }
        return CommandResult.success(msg("command.tier.set", tierArg));
    }

    // ==================== Model Selection Command ====================

    private CommandResult handleModel(List<String> args) {
        if (args.isEmpty()) {
            return handleModelShow();
        }

        String subcommand = args.get(0).toLowerCase(Locale.ROOT);
        if (SUBCMD_LIST.equals(subcommand)) {
            return handleModelList();
        }

        // Tier-based subcommands: /model <tier> ...
        if (!VALID_TIERS.contains(subcommand)) {
            return CommandResult.success(msg("command.model.invalid.tier"));
        }

        String tier = subcommand;
        List<String> subArgs = args.subList(1, args.size());

        if (subArgs.isEmpty()) {
            return CommandResult.success(msg("command.model.usage"));
        }

        String action = subArgs.get(0).toLowerCase(Locale.ROOT);
        if (SUBCMD_RESET.equals(action)) {
            return handleModelReset(tier);
        }
        if (SUBCMD_REASONING.equals(action)) {
            if (subArgs.size() < MIN_REASONING_ARGS) {
                return CommandResult.success(msg("command.model.usage"));
            }
            return handleModelSetReasoning(tier, subArgs.get(1).toLowerCase(Locale.ROOT));
        }

        // /model <tier> <provider/model>
        String modelSpec = subArgs.get(0);
        return handleModelSet(tier, modelSpec);
    }

    private CommandResult handleModelShow() {
        UserPreferences prefs = preferencesService.getPreferences();
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(msg("command.model.show.title")).append("**\n\n");

        for (String tier : List.of("balanced", "smart", "coding", "deep")) {
            ModelSelectionService.ModelSelection selection = modelSelectionService.resolveForTier(tier);
            String model = selection.model() != null ? selection.model() : "—";
            String reasoning = selection.reasoning() != null ? selection.reasoning() : "—";

            boolean hasOverride = prefs.getTierOverrides() != null
                    && prefs.getTierOverrides().containsKey(tier);
            String msgKey = hasOverride ? "command.model.show.tier.override" : "command.model.show.tier.default";
            sb.append(msg(msgKey, tier, model, reasoning)).append("\n");
        }

        return CommandResult.success(sb.toString());
    }

    private CommandResult handleModelList() {
        Map<String, List<ModelSelectionService.AvailableModel>> grouped = modelSelectionService
                .getAvailableModelsGrouped();
        if (grouped.isEmpty()) {
            return CommandResult.success(msg("command.model.list.title") + "\n\nNo models available.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(msg("command.model.list.title")).append("**\n\n");

        for (Map.Entry<String, List<ModelSelectionService.AvailableModel>> entry : grouped.entrySet()) {
            sb.append(msg("command.model.list.provider", entry.getKey())).append("\n");
            for (ModelSelectionService.AvailableModel model : entry.getValue()) {
                String reasoningInfo = model.hasReasoning()
                        ? " [reasoning: " + String.join(", ", model.reasoningLevels()) + "]"
                        : "";
                sb.append(msg("command.model.list.model", model.id(), model.displayName(), reasoningInfo)).append("\n");
            }
            sb.append("\n");
        }

        return CommandResult.success(sb.toString());
    }

    private CommandResult handleModelSet(String tier, String modelSpec) {
        ModelSelectionService.ValidationResult validation = modelSelectionService.validateModel(modelSpec);
        if (!validation.valid()) {
            if (ERR_PROVIDER_NOT_CONFIGURED.equals(validation.error())) {
                String configuredStr = String.join(", ",
                        runtimeConfigService.getConfiguredLlmProviders());
                return CommandResult.success(msg("command.model.invalid.provider", modelSpec, configuredStr));
            }
            return CommandResult.success(msg("command.model.invalid.model", modelSpec));
        }

        UserPreferences prefs = preferencesService.getPreferences();
        String defaultReasoning = findDefaultReasoning(modelSpec);

        UserPreferences.TierOverride override = new UserPreferences.TierOverride(modelSpec, defaultReasoning);
        prefs.getTierOverrides().put(tier, override);
        preferencesService.savePreferences(prefs);

        String displayReasoning = defaultReasoning != null ? " (reasoning: " + defaultReasoning + ")" : "";
        return CommandResult.success(msg("command.model.set", tier, modelSpec) + displayReasoning);
    }

    private CommandResult handleModelSetReasoning(String tier, String level) {
        UserPreferences prefs = preferencesService.getPreferences();
        UserPreferences.TierOverride existing = prefs.getTierOverrides() != null
                ? prefs.getTierOverrides().get(tier)
                : null;

        if (existing == null || existing.getModel() == null) {
            return CommandResult.success(msg("command.model.no.override", tier));
        }

        ModelSelectionService.ValidationResult validation = modelSelectionService.validateReasoning(
                existing.getModel(), level);
        if (!validation.valid()) {
            if (ERR_NO_REASONING.equals(validation.error())) {
                return CommandResult.success(msg("command.model.no.reasoning", existing.getModel()));
            }
            List<String> available = modelSelectionService.getAvailableModels().stream()
                    .filter(m -> m.id().equals(existing.getModel())
                            || existing.getModel().endsWith("/" + m.id()))
                    .flatMap(m -> m.reasoningLevels().stream())
                    .toList();
            return CommandResult.success(msg("command.model.invalid.reasoning", level, String.join(", ", available)));
        }

        existing.setReasoning(level);
        preferencesService.savePreferences(prefs);
        return CommandResult.success(msg("command.model.set.reasoning", tier, level));
    }

    private CommandResult handleModelReset(String tier) {
        UserPreferences prefs = preferencesService.getPreferences();
        if (prefs.getTierOverrides() != null) {
            prefs.getTierOverrides().remove(tier);
            preferencesService.savePreferences(prefs);
        }
        return CommandResult.success(msg("command.model.reset", tier));
    }

    private String findDefaultReasoning(String modelSpec) {
        // Extract from models.json
        List<ModelSelectionService.AvailableModel> models = modelSelectionService.getAvailableModels();
        for (ModelSelectionService.AvailableModel model : models) {
            if (model.id().equals(modelSpec) || modelSpec.endsWith("/" + model.id())) {
                if (model.hasReasoning() && !model.reasoningLevels().isEmpty()) {
                    List<String> levels = model.reasoningLevels();
                    return levels.contains("medium") ? "medium" : levels.get(0);
                }
            }
        }
        return null;
    }

    // ==================== Auto Mode Commands ====================

    private CommandResult handleAuto(List<String> args, String channelType, String sessionChatId, String transportChatId) {
        if (!autoModeService.isFeatureEnabled()) {
            return CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
        }

        if (args.isEmpty()) {
            boolean enabled = autoModeService.isAutoModeEnabled();
            return CommandResult.success(msg("command.auto.status", enabled ? "ON" : "OFF"));
        }

        String subcommand = args.get(0).toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "on" -> {
                autoModeService.enableAutoMode();
                if (channelType != null && sessionChatId != null) {
                    eventPublisher.publishEvent(new AutoModeChannelRegisteredEvent(
                            channelType,
                            sessionChatId,
                            transportChatId != null ? transportChatId : sessionChatId));
                }
                yield CommandResult.success(msg("command.auto.enabled"));
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
            return CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
        }

        List<Goal> goals = autoModeService.getGoals();
        if (goals.isEmpty()) {
            return CommandResult.success(msg("command.goals.empty"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.goals.title", goals.size())).append(DOUBLE_NEWLINE);

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
            return CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
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
                    runtimeConfigService.getAutoMaxGoals()));
        }
    }

    private CommandResult handleTasks() {
        if (!autoModeService.isFeatureEnabled()) {
            return CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
        }

        List<Goal> goals = autoModeService.getGoals();
        boolean hasTasks = goals.stream().anyMatch(g -> !g.getTasks().isEmpty());
        if (goals.isEmpty() || !hasTasks) {
            return CommandResult.success(msg("command.tasks.empty"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.tasks.title")).append(DOUBLE_NEWLINE);

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
            return CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
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
        if (!planService.isFeatureEnabled()) {
            return CommandResult.success(msg(MSG_PLAN_NOT_AVAILABLE));
        }

        if (args.isEmpty()) {
            boolean active = sessionIdentity != null
                    ? planService.isPlanModeActive(sessionIdentity)
                    : planService.isPlanModeActive();
            return CommandResult.success(msg("command.plan.status", active ? "ON" : "OFF"));
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
        if (sessionIdentity == null && planService.isPlanModeActive()) {
            return CommandResult.success(msg("command.plan.already-active"));
        }
        if (sessionIdentity != null && planService.isPlanModeActive(sessionIdentity)) {
            return CommandResult.success(msg("command.plan.already-active"));
        }

        String modelTier = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : null;
        try {
            if (sessionIdentity == null) {
                planService.activatePlanMode(transportChatId, modelTier);
            } else {
                planService.activatePlanMode(sessionIdentity, transportChatId, modelTier);
            }
            String tierMsg = modelTier != null ? " (tier: " + modelTier + ")" : "";
            return CommandResult.success(msg("command.plan.enabled") + tierMsg);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return CommandResult.failure(msg("command.plan.limit", properties.getPlan().getMaxPlans()));
        }
    }

    private CommandResult handlePlanOff(SessionIdentity sessionIdentity) {
        if (sessionIdentity == null && !planService.isPlanModeActive()) {
            return CommandResult.success(msg("command.plan.not-active"));
        }
        if (sessionIdentity != null && !planService.isPlanModeActive(sessionIdentity)) {
            return CommandResult.success(msg("command.plan.not-active"));
        }

        if (sessionIdentity == null) {
            planService.deactivatePlanMode();
        } else {
            planService.deactivatePlanMode(sessionIdentity);
        }
        return CommandResult.success(msg("command.plan.disabled"));
    }

    private CommandResult handlePlanDone(SessionIdentity sessionIdentity) {
        if (sessionIdentity == null && !planService.isPlanModeActive()) {
            return CommandResult.success(msg("command.plan.not-active"));
        }
        if (sessionIdentity != null && !planService.isPlanModeActive(sessionIdentity)) {
            return CommandResult.success(msg("command.plan.not-active"));
        }

        // "Done" means: stop plan drafting mode.
        // The plan itself is finalized via the plan_set_content tool.
        if (sessionIdentity == null) {
            planService.deactivatePlanMode();
        } else {
            planService.deactivatePlanMode(sessionIdentity);
        }
        return CommandResult.success(msg("command.plan.done"));
    }

    private CommandResult handlePlanApprove(List<String> args, SessionIdentity sessionIdentity) {
        String planId = args.size() > 1 ? args.get(1) : findMostRecentReadyPlanId(sessionIdentity);
        if (planId == null) {
            return CommandResult.failure(msg("command.plan.no-ready"));
        }

        try {
            if (sessionIdentity != null && planService.getPlan(planId, sessionIdentity).isEmpty()) {
                return CommandResult.failure(msg("command.plan.not-found", planId));
            }
            planService.approvePlan(planId);
            planExecutionService.executePlan(planId);
            return CommandResult.success(msg("command.plan.approved", planId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return CommandResult.failure(e.getMessage());
        }
    }

    private CommandResult handlePlanCancel(List<String> args, SessionIdentity sessionIdentity) {
        String planId = args.size() > 1 ? args.get(1) : findMostRecentActivePlanId(sessionIdentity);
        if (planId == null) {
            return CommandResult.failure(msg("command.plan.no-active-plan"));
        }

        try {
            if (sessionIdentity != null && planService.getPlan(planId, sessionIdentity).isEmpty()) {
                return CommandResult.failure(msg("command.plan.not-found", planId));
            }
            planService.cancelPlan(planId);
            return CommandResult.success(msg("command.plan.cancelled", planId));
        } catch (IllegalArgumentException e) {
            return CommandResult.failure(e.getMessage());
        }
    }

    private CommandResult handlePlanResume(List<String> args, SessionIdentity sessionIdentity) {
        String planId = args.size() > 1 ? args.get(1) : findMostRecentPartialPlanId(sessionIdentity);
        if (planId == null) {
            return CommandResult.failure(msg("command.plan.no-partial"));
        }

        try {
            if (sessionIdentity != null && planService.getPlan(planId, sessionIdentity).isEmpty()) {
                return CommandResult.failure(msg("command.plan.not-found", planId));
            }
            planExecutionService.resumePlan(planId);
            return CommandResult.success(msg("command.plan.resumed", planId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return CommandResult.failure(e.getMessage());
        }
    }

    private CommandResult handlePlanStatus(List<String> args, SessionIdentity sessionIdentity) {
        String planId = args.size() > 1 ? args.get(1) : findMostRecentActivePlanId(sessionIdentity);
        if (planId == null) {
            // Show most recent plan of any status
            List<Plan> plans = sessionIdentity != null
                    ? planService.getPlans(sessionIdentity)
                    : planService.getPlans();
            if (plans.isEmpty()) {
                return CommandResult.success(msg("command.plans.empty"));
            }
            planId = plans.get(plans.size() - 1).getId();
        }

        Optional<Plan> plan = sessionIdentity != null
                ? planService.getPlan(planId, sessionIdentity)
                : planService.getPlan(planId);
        return plan
                .map(this::formatPlanStatus)
                .map(CommandResult::success)
                .orElse(CommandResult.failure(msg("command.plan.not-found", planId)));
    }

    private CommandResult handlePlans(SessionIdentity sessionIdentity) {
        if (!planService.isFeatureEnabled()) {
            return CommandResult.success(msg(MSG_PLAN_NOT_AVAILABLE));
        }

        List<Plan> plans = sessionIdentity != null
                ? planService.getPlans(sessionIdentity)
                : planService.getPlans();
        if (plans.isEmpty()) {
            return CommandResult.success(msg("command.plans.empty"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.plans.title", plans.size())).append(DOUBLE_NEWLINE);

        for (Plan plan : plans) {
            long completed = plan.getCompletedStepCount();
            int total = plan.getSteps().size();
            String statusIcon = switch (plan.getStatus()) {
            case COLLECTING -> "\u270D\uFE0F";
            case READY -> "\u23F3";
            case APPROVED -> "\u2705";
            case EXECUTING -> "\u25B6\uFE0F";
            case COMPLETED -> "\u2705";
            case PARTIALLY_COMPLETED -> "\u26A0\uFE0F";
            case CANCELLED -> "\u274C";
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

    private String findMostRecentReadyPlanId(SessionIdentity sessionIdentity) {
        List<Plan> plans = sessionIdentity != null
                ? planService.getPlans(sessionIdentity)
                : planService.getPlans();
        return plans.stream()
                .filter(p -> p.getStatus() == Plan.PlanStatus.READY)
                .reduce((first, second) -> second)
                .map(Plan::getId)
                .orElse(null);
    }

    private String findMostRecentActivePlanId(SessionIdentity sessionIdentity) {
        List<Plan> plans = sessionIdentity != null
                ? planService.getPlans(sessionIdentity)
                : planService.getPlans();
        return plans.stream()
                .filter(p -> p.getStatus() == Plan.PlanStatus.COLLECTING
                        || p.getStatus() == Plan.PlanStatus.READY
                        || p.getStatus() == Plan.PlanStatus.APPROVED
                        || p.getStatus() == Plan.PlanStatus.EXECUTING)
                .reduce((first, second) -> second)
                .map(Plan::getId)
                .orElse(null);
    }

    private String findMostRecentPartialPlanId(SessionIdentity sessionIdentity) {
        List<Plan> plans = sessionIdentity != null
                ? planService.getPlans(sessionIdentity)
                : planService.getPlans();
        return plans.stream()
                .filter(p -> p.getStatus() == Plan.PlanStatus.PARTIALLY_COMPLETED)
                .reduce((first, second) -> second)
                .map(Plan::getId)
                .orElse(null);
    }

    // ==================== Schedule Commands ====================

    private CommandResult handleSchedule(List<String> args) {
        if (!autoModeService.isFeatureEnabled()) {
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
        if (args.size() < MIN_SCHEDULE_ARGS) {
            return CommandResult.success(msg("command.schedule.goal.usage"));
        }

        String goalId = args.get(0);
        if (autoModeService.getGoal(goalId).isEmpty()) {
            return CommandResult.failure(msg("command.schedule.goal.not-found", goalId));
        }

        return createScheduleFromArgs(ScheduleEntry.ScheduleType.GOAL, goalId, args.subList(1, args.size()));
    }

    private CommandResult handleScheduleTask(List<String> args) {
        if (args.size() < MIN_SCHEDULE_ARGS) {
            return CommandResult.success(msg("command.schedule.task.usage"));
        }

        String taskId = args.get(0);
        if (autoModeService.findGoalForTask(taskId).isEmpty()) {
            return CommandResult.failure(msg("command.schedule.task.not-found", taskId));
        }

        return createScheduleFromArgs(ScheduleEntry.ScheduleType.TASK, taskId, args.subList(1, args.size()));
    }

    private CommandResult createScheduleFromArgs(ScheduleEntry.ScheduleType type,
            String targetId, List<String> cronAndRepeat) {
        int maxExecutions = -1;

        // Check if last arg is a numeric repeat count
        List<String> cronParts = new ArrayList<>(cronAndRepeat);
        if (cronParts.size() > MIN_CRON_PARTS_FOR_REPEAT_CHECK) {
            String lastArg = cronParts.get(cronParts.size() - 1);
            try {
                maxExecutions = Integer.parseInt(lastArg);
                cronParts = cronParts.subList(0, cronParts.size() - 1);
            } catch (NumberFormatException ignored) {
                // Last arg is part of cron expression
            }
        }

        String cronExpression = String.join(" ", cronParts);

        try {
            ScheduleEntry entry = scheduleService.createSchedule(type, targetId, cronExpression, maxExecutions);
            return CommandResult.success(msg("command.schedule.created",
                    entry.getId(), entry.getCronExpression()));
        } catch (IllegalArgumentException e) {
            return CommandResult.failure(msg("command.schedule.invalid-cron", e.getMessage()));
        }
    }

    private CommandResult handleScheduleList() {
        List<ScheduleEntry> schedules = scheduleService.getSchedules();
        if (schedules.isEmpty()) {
            return CommandResult.success(msg("command.schedule.list.empty"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(msg("command.schedule.list.title", schedules.size())).append(DOUBLE_NEWLINE);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneOffset.UTC);

        for (ScheduleEntry entry : schedules) {
            String enabledIcon = entry.isEnabled() ? "\u2705" : "\u274C";
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
        if (args.isEmpty()) {
            return CommandResult.success(msg("command.schedule.delete.usage"));
        }

        String scheduleId = args.get(0);
        try {
            scheduleService.deleteSchedule(scheduleId);
            return CommandResult.success(msg("command.schedule.deleted", scheduleId));
        } catch (IllegalArgumentException e) {
            return CommandResult.failure(msg("command.schedule.not-found", scheduleId));
        }
    }

    private String msg(String key, Object... args) {
        return preferencesService.getMessage(key, args);
    }
}
