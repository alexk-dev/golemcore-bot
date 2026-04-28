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
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.UsageStats;
import me.golemcore.bot.domain.auto.AutoModeService;
import me.golemcore.bot.domain.context.compaction.CompactionOrchestrationService;
import me.golemcore.bot.domain.scheduling.DelayedActionPolicyService;
import me.golemcore.bot.domain.planning.PlanService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.domain.scheduling.ScheduleService;
import me.golemcore.bot.domain.session.SessionRunCoordinator;
import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;
import me.golemcore.bot.port.inbound.CommandPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings({ "PMD.AvoidDuplicateLiterals", "unchecked" })
class CommandRouterTest {

    private static final String SESSION_ID = "telegram:12345";
    private static final String CHANNEL_TYPE_TELEGRAM = "telegram";
    private static final String CHAT_ID = "12345";
    private static final String GET_MESSAGE_METHOD = "getMessage";
    private static final int ONE = 1;
    private static final String CMD_COMPACT = "compact";
    private static final String CMD_AUTO = "auto";
    private static final String CMD_GOALS = "goals";
    private static final String CMD_GOAL = "goal";
    private static final String CMD_DIARY = "diary";
    private static final String CMD_SCHEDULE = "schedule";
    private static final String TEST_GOAL_ID = "goal-abc";
    private static final String TEST_GOAL_TITLE = "Test";
    private static final String TEST_TASK_ID = "task-xyz";
    private static final String TEST_SCHED_ID = "sched-goal-abc";
    private static final String TOOL_FILESYSTEM = "filesystem";
    private static final String TOOL_SHELL = "shell";
    private static final String CMD_TIER = "tier";
    private static final String TIER_SMART = "smart";
    private static final String SUB_STATUS = "status";
    private static final String SUB_OFF = "off";
    private static final SessionIdentity TELEGRAM_SESSION_IDENTITY = new SessionIdentity(CHANNEL_TYPE_TELEGRAM,
            CHAT_ID);

    private SkillComponent skillComponent;
    private SessionPort sessionService;
    private UsageTrackingPort usageTracker;
    private UserPreferencesService preferencesService;
    private CompactionOrchestrationService compactionService;
    private AutoModeService autoModeService;
    private AutomationCommandService automationCommandService;
    private AutomationCommandHandler automationCommandHandler;
    private ModelSelectionCommandService modelSelectionCommandService;
    private ModelSelectionCommandHandler modelSelectionCommandHandler;
    private PlanService planService;
    private PlanCommandService planCommandService;
    private PlanCommandHandler planCommandHandler;
    private RuntimeConfigService runtimeConfigService;
    private ScheduleService scheduleService;
    private DelayedActionPolicyService delayedActionPolicyService;
    private SessionRunCoordinator runCoordinator;
    private ApplicationEventPublisher eventPublisher;
    private ObjectProvider<BuildProperties> buildPropertiesProvider;
    private CommandRouter router;

    private static final Map<String, Object> CTX = Map.of(
            "sessionId", SESSION_ID);

    @BeforeEach
    void setUp() {
        skillComponent = mock(SkillComponent.class);
        sessionService = mock(SessionPort.class);
        usageTracker = mock(UsageTrackingPort.class);
        compactionService = mock(CompactionOrchestrationService.class);

        // Default: pass through message key + args for easy assertion.
        preferencesService = mock(UserPreferencesService.class, inv -> {
            if (GET_MESSAGE_METHOD.equals(inv.getMethod().getName())) {
                Object[] allArgs = inv.getArguments();
                String key = (String) allArgs[0];
                if (allArgs.length > ONE) {
                    StringBuilder sb = new StringBuilder(key);
                    Object vararg = allArgs[1];
                    if (vararg instanceof Object[] arr) {
                        for (Object a : arr) {
                            sb.append(" ").append(a);
                        }
                    } else {
                        for (int i = 1; i < allArgs.length; i++) {
                            sb.append(" ").append(allArgs[i]);
                        }
                    }
                    String result = sb.toString();
                    return result.equals(key) ? key : result;
                }
                return key;
            }
            return RETURNS_DEFAULTS.answer(inv);
        });

        autoModeService = mock(AutoModeService.class);
        when(autoModeService.getGoals(SESSION_ID)).thenAnswer(invocation -> autoModeService.getGoals());
        when(autoModeService.getActiveGoals(SESSION_ID)).thenAnswer(invocation -> autoModeService.getActiveGoals());
        when(autoModeService.getNextPendingTask(SESSION_ID))
                .thenAnswer(invocation -> autoModeService.getNextPendingTask());
        when(autoModeService.getRecentDiary(eq(SESSION_ID), anyInt()))
                .thenAnswer(invocation -> autoModeService.getRecentDiary(invocation.getArgument(1, Integer.class)));
        when(autoModeService.createGoal(eq(SESSION_ID), anyString(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> autoModeService.createGoal(
                        invocation.getArgument(1, String.class),
                        invocation.getArgument(2, String.class)));
        modelSelectionCommandService = mock(ModelSelectionCommandService.class);
        planService = mock(PlanService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        scheduleService = mock(ScheduleService.class);
        delayedActionPolicyService = mock(DelayedActionPolicyService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        automationCommandService = new AutomationCommandService(
                autoModeService,
                runtimeConfigService,
                scheduleService,
                delayedActionPolicyService,
                null);
        automationCommandHandler = new AutomationCommandHandler(
                automationCommandService,
                preferencesService,
                eventPublisher);
        modelSelectionCommandHandler = new ModelSelectionCommandHandler(
                modelSelectionCommandService,
                preferencesService);
        planCommandService = new PlanCommandService(planService);
        planCommandHandler = new PlanCommandHandler(planCommandService, preferencesService);
        runCoordinator = mock(SessionRunCoordinator.class);

        ToolComponent tool1 = mockTool(TOOL_FILESYSTEM, "File system operations", true);
        ToolComponent tool2 = mockTool(TOOL_SHELL, "Shell command execution", true);
        ToolComponent tool3 = mockTool("disabled-tool", "Disabled tool", false);

        buildPropertiesProvider = mock(ObjectProvider.class);

        router = new CommandRouter(new CommandDispatcher(List.of(
                new SystemCommandHandler(
                        skillComponent,
                        List.of(tool1, tool2, tool3),
                        sessionService,
                        usageTracker,
                        preferencesService,
                        compactionService,
                        automationCommandHandler,
                        planCommandService,
                        delayedActionPolicyService,
                        runCoordinator,
                        buildPropertiesProvider),
                automationCommandHandler,
                modelSelectionCommandHandler,
                planCommandHandler), preferencesService));
    }

    private ToolComponent mockTool(String name, String description, boolean enabled) {
        ToolComponent tool = mock(ToolComponent.class);
        when(tool.getDefinition()).thenReturn(ToolDefinition.builder()
                .name(name)
                .description(description)
                .build());
        when(tool.isEnabled()).thenReturn(enabled);
        return tool;
    }

    @Test
    void hasCommand() {
        assertTrue(router.hasCommand("skills"));
        assertTrue(router.hasCommand("tools"));
        assertTrue(router.hasCommand(SUB_STATUS));
        assertTrue(router.hasCommand("new"));
        assertTrue(router.hasCommand("reset"));
        assertTrue(router.hasCommand(CMD_COMPACT));
        assertTrue(router.hasCommand("help"));
        assertTrue(router.hasCommand("sessions"));
        assertTrue(router.hasCommand(CMD_AUTO));
        assertTrue(router.hasCommand(CMD_GOALS));
        assertTrue(router.hasCommand(CMD_GOAL));
        assertTrue(router.hasCommand(CMD_DIARY));
        assertTrue(router.hasCommand("tasks"));
        assertTrue(router.hasCommand(CMD_SCHEDULE));
        assertTrue(router.hasCommand("plan"));
        assertTrue(router.hasCommand("stop"));
        assertFalse(router.hasCommand("unknown"));
        assertFalse(router.hasCommand("settings"));
    }

    @Test
    void listCommands() {
        List<CommandPort.CommandDefinition> commands = router.listCommands();
        assertEquals(12, commands.size());
    }

    @Test
    void skillsCommand() throws Exception {
        when(skillComponent.getAvailableSkills()).thenReturn(List.of(
                Skill.builder().name("greeting").description("Greets users").available(true).build(),
                Skill.builder().name("code-review").description("Reviews code").available(true).build()));

        CommandPort.CommandResult result = router.execute("skills", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("greeting"));
        assertTrue(result.output().contains("code-review"));
    }

    @Test
    void skillsCommandEmpty() throws Exception {
        when(skillComponent.getAvailableSkills()).thenReturn(List.of());

        CommandPort.CommandResult result = router.execute("skills", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.skills.empty"));
    }

    @Test
    void toolsCommand() throws Exception {
        CommandPort.CommandResult result = router.execute("tools", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains(TOOL_FILESYSTEM));
        assertTrue(result.output().contains(TOOL_SHELL));
        assertFalse(result.output().contains("disabled-tool"));
    }

    @Test
    void statusCommand() throws Exception {
        when(sessionService.getMessageCount(SESSION_ID)).thenReturn(42);

        Map<String, UsageStats> byModel = new LinkedHashMap<>();
        byModel.put("langchain4j/gpt-5.1", UsageStats.builder()
                .totalRequests(8).totalTokens(12300).build());
        byModel.put("langchain4j/gpt-5.2", UsageStats.builder()
                .totalRequests(2).totalTokens(8700).build());
        when(usageTracker.getStatsByModel(any(Duration.class)))
                .thenReturn(byModel);

        CommandPort.CommandResult result = router.execute(SUB_STATUS, List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("42"));
        assertTrue(result.output().contains("gpt-5.1"));
        assertTrue(result.output().contains("gpt-5.2"));
        assertTrue(result.output().contains("12.3K"));
        assertTrue(result.output().contains("8.7K"));
        assertTrue(result.output().contains("21.0K"));
    }

    @Test
    void statusCommandNoUsage() throws Exception {
        when(sessionService.getMessageCount(SESSION_ID)).thenReturn(3);
        when(usageTracker.getStatsByModel(any(Duration.class)))
                .thenReturn(Collections.emptyMap());

        CommandPort.CommandResult result = router.execute(SUB_STATUS, List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("3"));
        assertTrue(result.output().contains("command.status.usage.empty"));
    }

    @Test
    void statusCommandShowsVersionWhenBuildPropertiesAvailable() throws Exception {
        Properties props = new Properties();
        props.setProperty("version", "1.2.3");
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(new BuildProperties(props));
        when(sessionService.getMessageCount(SESSION_ID)).thenReturn(0);
        when(usageTracker.getStatsByModel(any(Duration.class))).thenReturn(Collections.emptyMap());

        CommandPort.CommandResult result = router.execute(SUB_STATUS, List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.status.version"));
        assertTrue(result.output().contains("1.2.3"));
    }

    @Test
    void statusCommandOmitsVersionWhenBuildPropertiesAbsent() throws Exception {
        when(sessionService.getMessageCount(SESSION_ID)).thenReturn(0);
        when(usageTracker.getStatsByModel(any(Duration.class))).thenReturn(Collections.emptyMap());

        CommandPort.CommandResult result = router.execute(SUB_STATUS, List.of(), CTX).get();
        assertTrue(result.success());
        assertFalse(result.output().contains("command.status.version"));
    }

    @Test
    void newCommand() throws Exception {
        CommandPort.CommandResult result = router.execute("new", List.of(), CTX).get();
        assertTrue(result.success());
        verify(sessionService, never()).clearMessages(SESSION_ID);
    }

    @Test
    void resetCommand() throws Exception {
        when(planService.isPlanModeActive()).thenReturn(true);

        CommandPort.CommandResult result = router.execute("reset", List.of(), CTX).get();

        assertTrue(result.success());
        verify(sessionService).clearMessages(SESSION_ID);
        verify(planService).deactivatePlanMode();
    }

    @Test
    void compactWithSummary() throws Exception {
        when(compactionService.compact(SESSION_ID, CompactionReason.MANUAL_COMMAND, 10))
                .thenReturn(CompactionResult.builder().removed(20).usedSummary(true).build());

        CommandPort.CommandResult result = router.execute(CMD_COMPACT, List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("20"));
        assertTrue(result.output().contains("command.compact.done.summary"));
        verify(compactionService).compact(SESSION_ID, CompactionReason.MANUAL_COMMAND, 10);
    }

    @Test
    void compactFallbackWhenLlmUnavailable() throws Exception {
        when(compactionService.compact(SESSION_ID, CompactionReason.MANUAL_COMMAND, 10))
                .thenReturn(CompactionResult.builder().removed(15).usedSummary(false).build());

        CommandPort.CommandResult result = router.execute(CMD_COMPACT, List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("15"));
        assertTrue(result.output().contains("command.compact.done"));
        verify(compactionService).compact(SESSION_ID, CompactionReason.MANUAL_COMMAND, 10);
    }

    @Test
    void compactCommandWithArg() throws Exception {
        when(compactionService.compact(SESSION_ID, CompactionReason.MANUAL_COMMAND, 5))
                .thenReturn(CompactionResult.builder().removed(15).usedSummary(false).build());

        CommandPort.CommandResult result = router.execute(CMD_COMPACT, List.of("5"), CTX).get();
        assertTrue(result.success());
        verify(compactionService).compact(SESSION_ID, CompactionReason.MANUAL_COMMAND, 5);
    }

    @Test
    void compactCommandNothingToCompact() throws Exception {
        when(compactionService.compact(SESSION_ID, CompactionReason.MANUAL_COMMAND, 10))
                .thenReturn(CompactionResult.builder().removed(0).usedSummary(false).build());
        when(sessionService.getMessageCount(SESSION_ID)).thenReturn(5);

        CommandPort.CommandResult result = router.execute(CMD_COMPACT, List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.compact.nothing"));
    }

    @Test
    void helpCommand() throws Exception {
        CommandPort.CommandResult result = router.execute("help", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.help.text"));
    }

    @Test
    void sessionsCommandForTelegram() throws Exception {
        CommandPort.CommandResult result = router.execute("sessions", List.of(), CTX_WITH_CHANNEL).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.sessions.use-menu"));
    }

    @Test
    void sessionsCommandForNonTelegram() throws Exception {
        Map<String, Object> webCtx = Map.of(
                "sessionId", "web:abc",
                "channelType", "web",
                "chatId", "abc");

        CommandPort.CommandResult result = router.execute("sessions", List.of(), webCtx).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.sessions.not-available"));
    }

    @Test
    void unknownCommand() throws Exception {
        CommandPort.CommandResult result = router.execute("foobar", List.of(), CTX).get();
        assertFalse(result.success());
        assertTrue(result.output().contains("command.unknown"));
    }

    // ===== Auto mode commands =====

    private static final Map<String, Object> CTX_WITH_CHANNEL = Map.of(
            "sessionId", SESSION_ID,
            "channelType", CHANNEL_TYPE_TELEGRAM,
            "chatId", CHAT_ID);
    private static final Map<String, Object> CTX_WITH_SPLIT_IDENTITIES = Map.of(
            "sessionId", "telegram:conversation-42",
            "channelType", CHANNEL_TYPE_TELEGRAM,
            "chatId", "conversation-42",
            "sessionChatId", "conversation-42",
            "transportChatId", "transport-900");

    @Test
    void autoOnEnablesAutoMode() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        CommandPort.CommandResult result = router.execute(CMD_AUTO, List.of("on"), CTX_WITH_CHANNEL).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.auto.enabled"));
        verify(autoModeService).enableAutoMode();
        verify(eventPublisher).publishEvent(new AutoModeChannelRegisteredEvent(CHANNEL_TYPE_TELEGRAM, CHAT_ID));
    }

    @Test
    void autoOnUsesTransportChatIdFromContext() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        CommandPort.CommandResult result = router.execute(CMD_AUTO, List.of("on"), CTX_WITH_SPLIT_IDENTITIES).get();
        assertTrue(result.success());
        verify(eventPublisher).publishEvent(
                new AutoModeChannelRegisteredEvent(CHANNEL_TYPE_TELEGRAM, "conversation-42", "transport-900"));
    }

    @Test
    void autoOffDisablesAutoMode() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        CommandPort.CommandResult result = router.execute(CMD_AUTO, List.of("off"), CTX_WITH_CHANNEL).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.auto.disabled"));
        verify(autoModeService).disableAutoMode();
    }

    @Test
    void autoNoArgsShowsStatus() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        CommandPort.CommandResult result = router.execute(CMD_AUTO, List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.auto.status"));
        assertTrue(result.output().contains("ON"));
    }

    @Test
    void autoReturnsNotAvailableWhenDisabled() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(false);

        CommandPort.CommandResult result = router.execute(CMD_AUTO, List.of("on"), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.auto.not-available"));
    }

    @Test
    void autoInvalidSubcommand() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        CommandPort.CommandResult result = router.execute(CMD_AUTO, List.of("invalid"), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.auto.usage"));
    }

    // ===== Goals command =====

    @Test
    void goalsListsGoals() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getGoals()).thenReturn(List.of(
                Goal.builder().title("Build API").description("REST API").status(Goal.GoalStatus.ACTIVE)
                        .tasks(List.of(AutoTask.builder().title("Design").status(AutoTask.TaskStatus.COMPLETED).order(1)
                                .build()))
                        .build()));

        CommandPort.CommandResult result = router.execute(CMD_GOALS, List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("Build API"));
        assertTrue(result.output().contains("ACTIVE"));
    }

    @Test
    void goalsEmptyList() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getGoals()).thenReturn(List.of());

        CommandPort.CommandResult result = router.execute(CMD_GOALS, List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.goals.empty"));
    }

    // ===== Goal command =====

    @Test
    void goalCreatesGoal() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.createGoal("Build REST API", null))
                .thenReturn(
                        Goal.builder().title("Build REST API").status(Goal.GoalStatus.ACTIVE).tasks(List.of()).build());

        CommandPort.CommandResult result = router.execute(CMD_GOAL, List.of("Build", "REST", "API"), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.goal.created"));
    }

    @Test
    void goalWithoutArgsShowsEmpty() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        CommandPort.CommandResult result = router.execute(CMD_GOAL, List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.goals.empty"));
    }

    // ===== Tasks command =====

    @Test
    void tasksShowsTasks() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getGoals()).thenReturn(List.of(
                Goal.builder().title("API").status(Goal.GoalStatus.ACTIVE).tasks(List.of(
                        AutoTask.builder().title("Design endpoints").status(AutoTask.TaskStatus.PENDING).order(1)
                                .build(),
                        AutoTask.builder().title("Implement").status(AutoTask.TaskStatus.IN_PROGRESS).order(2).build()))
                        .build()));

        CommandPort.CommandResult result = router.execute("tasks", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("Design endpoints"));
        assertTrue(result.output().contains("Implement"));
    }

    @Test
    void tasksEmpty() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getGoals()).thenReturn(List.of());

        CommandPort.CommandResult result = router.execute("tasks", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.tasks.empty"));
    }

    // ===== Diary command =====

    @Test
    void diaryShowsEntries() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getRecentDiary(10)).thenReturn(List.of(
                DiaryEntry.builder()
                        .type(DiaryEntry.DiaryType.OBSERVATION)
                        .content("Started working on API design")
                        .timestamp(Instant.parse("2026-02-07T10:00:00Z"))
                        .build()));

        CommandPort.CommandResult result = router.execute(CMD_DIARY, List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("Started working on API design"));
        assertTrue(result.output().contains("OBSERVATION"));
    }

    @Test
    void diaryWithCustomCount() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getRecentDiary(5)).thenReturn(List.of());

        router.execute(CMD_DIARY, List.of("5"), CTX).get();
        verify(autoModeService).getRecentDiary(5);
    }

    @Test
    void diaryEmpty() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getRecentDiary(10)).thenReturn(List.of());

        CommandPort.CommandResult result = router.execute(CMD_DIARY, List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.diary.empty"));
    }

    // ===== Compact bounds =====

    @Test
    void compactClampsKeepToMinOne() throws Exception {
        when(compactionService.compact(SESSION_ID, CompactionReason.MANUAL_COMMAND, 1))
                .thenReturn(CompactionResult.builder().removed(0).usedSummary(false).build());
        when(sessionService.getMessageCount(SESSION_ID)).thenReturn(3);

        router.execute(CMD_COMPACT, List.of("0"), CTX).get();
        verify(compactionService).compact(SESSION_ID, CompactionReason.MANUAL_COMMAND, 1);
    }

    @Test
    void compactClampsKeepToMax100() throws Exception {
        when(compactionService.compact(SESSION_ID, CompactionReason.MANUAL_COMMAND, 100))
                .thenReturn(CompactionResult.builder().removed(0).usedSummary(false).build());
        when(sessionService.getMessageCount(SESSION_ID)).thenReturn(3);

        router.execute(CMD_COMPACT, List.of("999"), CTX).get();
        verify(compactionService).compact(SESSION_ID, CompactionReason.MANUAL_COMMAND, 100);
    }

    @Test
    void compactIgnoresNonNumericArg() throws Exception {
        when(compactionService.compact(SESSION_ID, CompactionReason.MANUAL_COMMAND, 10))
                .thenReturn(CompactionResult.builder().removed(0).usedSummary(false).build());
        when(sessionService.getMessageCount(SESSION_ID)).thenReturn(3);

        router.execute(CMD_COMPACT, List.of("abc"), CTX).get();
        verify(compactionService).compact(SESSION_ID, CompactionReason.MANUAL_COMMAND, 10);
    }

    // ===== listCommands includes auto when enabled =====

    @Test
    void listCommandsIncludesAutoWhenEnabled() {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        List<CommandPort.CommandDefinition> commands = router.listCommands();
        assertTrue(commands.stream().anyMatch(c -> CMD_AUTO.equals(c.name())));
        assertTrue(commands.stream().anyMatch(c -> CMD_GOALS.equals(c.name())));
        assertTrue(commands.stream().anyMatch(c -> CMD_DIARY.equals(c.name())));
        assertTrue(commands.stream().anyMatch(c -> CMD_SCHEDULE.equals(c.name())));
        assertEquals(18, commands.size()); // 11 base + 6 auto mode + /plan
    }

    @Test
    void listCommandsIncludesPlanWhenEnabled() {
        List<CommandPort.CommandDefinition> commands = router.listCommands();
        assertTrue(commands.stream().anyMatch(c -> "plan".equals(c.name())));
        assertTrue(commands.stream().noneMatch(c -> "plans".equals(c.name())));
        assertEquals(12, commands.size()); // 11 base + /plan
    }

    // ===== Plan commands =====

    private static final String CMD_PLAN = "plan";

    @Test
    void planNoArgsShowsStatus() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive(TELEGRAM_SESSION_IDENTITY)).thenReturn(true);

        CommandPort.CommandResult result = router.execute(CMD_PLAN, List.of(), CTX_WITH_CHANNEL).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.plan.status"));
        assertTrue(result.output().contains("ON"));
    }

    @Test
    void planNoArgsShowsOffWhenInactive() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive()).thenReturn(false);

        CommandPort.CommandResult result = router.execute(CMD_PLAN, List.of(), CTX_WITH_CHANNEL).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("OFF"));
    }

    @Test
    void planOnActivatesPlanMode() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive(TELEGRAM_SESSION_IDENTITY)).thenReturn(false);

        CommandPort.CommandResult result = router.execute(CMD_PLAN, List.of("on"), CTX_WITH_CHANNEL).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.plan.enabled"));
        verify(planService).activatePlanMode(TELEGRAM_SESSION_IDENTITY, CHAT_ID, null);
    }

    @Test
    void planOnUsesTransportChatIdFromContext() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive(new SessionIdentity(CHANNEL_TYPE_TELEGRAM, "conversation-42")))
                .thenReturn(false);

        CommandPort.CommandResult result = router.execute(CMD_PLAN, List.of("on"), CTX_WITH_SPLIT_IDENTITIES).get();
        assertTrue(result.success());
        verify(planService).activatePlanMode(
                new SessionIdentity(CHANNEL_TYPE_TELEGRAM, "conversation-42"),
                "transport-900",
                null);
    }

    @Test
    void planOnUsesConversationKeyForSessionIdentityAndTransportForReplies() throws Exception {
        SessionIdentity conversationIdentity = new SessionIdentity(CHANNEL_TYPE_TELEGRAM, "conv-1");
        Map<String, Object> context = Map.of(
                "sessionId", "telegram:conv-1",
                "channelType", CHANNEL_TYPE_TELEGRAM,
                "chatId", "transport-1",
                "sessionChatId", "transport-1",
                "conversationKey", "conv-1",
                "transportChatId", "transport-1");
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive(conversationIdentity)).thenReturn(false);

        CommandPort.CommandResult result = router.execute(CMD_PLAN, List.of("on"), context).get();

        assertTrue(result.success());
        verify(planService).activatePlanMode(conversationIdentity, "transport-1", null);
    }

    @Test
    void planOnIgnoresTierArgument() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive(TELEGRAM_SESSION_IDENTITY)).thenReturn(false);

        CommandPort.CommandResult result = router.execute(CMD_PLAN, List.of("on", TIER_SMART), CTX_WITH_CHANNEL).get();
        assertTrue(result.success());
        assertFalse(result.output().contains("tier: " + TIER_SMART));
        verify(planService).activatePlanMode(TELEGRAM_SESSION_IDENTITY, CHAT_ID, null);
    }

    @Test
    void planOnAlreadyActive() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive(TELEGRAM_SESSION_IDENTITY)).thenReturn(true);

        CommandPort.CommandResult result = router.execute(CMD_PLAN, List.of("on"), CTX_WITH_CHANNEL).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.plan.already-active"));
    }

    @Test
    void planOffDeactivatesWhenActive() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive(TELEGRAM_SESSION_IDENTITY)).thenReturn(true);

        CommandPort.CommandResult result = router.execute(CMD_PLAN, List.of(SUB_OFF), CTX_WITH_CHANNEL).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.plan.disabled"));
        verify(planService).deactivatePlanMode(TELEGRAM_SESSION_IDENTITY);
    }

    @Test
    void planDoneDeactivatesPlanMode() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive(TELEGRAM_SESSION_IDENTITY)).thenReturn(true);

        CommandPort.CommandResult result = router.execute(CMD_PLAN, List.of("done"), CTX_WITH_CHANNEL).get();
        assertTrue(result.success());
        verify(planService).completePlanMode(TELEGRAM_SESSION_IDENTITY);
    }

    @Test
    void planOffWhenNotActive() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive(TELEGRAM_SESSION_IDENTITY)).thenReturn(false);

        CommandPort.CommandResult result = router.execute(CMD_PLAN, List.of(SUB_OFF), CTX_WITH_CHANNEL).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.plan.not-active"));
    }

    @Test
    void planInvalidSubcommand() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);

        CommandPort.CommandResult result = router.execute(CMD_PLAN, List.of("invalid"), CTX_WITH_CHANNEL).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.plan.usage"));
    }

    // ===== Tier commands =====

    @Test
    void tierCommandRendersCurrentTierFromApplicationOutcome() throws Exception {
        when(modelSelectionCommandService.handleTier(new ModelSelectionCommandService.ShowTierStatus(SESSION_ID)))
                .thenReturn(new ModelSelectionCommandService.CurrentTier("smart", true));

        CommandPort.CommandResult result = router.execute(CMD_TIER, List.of(), CTX).get();

        assertTrue(result.success());
        assertEquals("command.tier.current smart on", result.output());
        verify(modelSelectionCommandService).handleTier(new ModelSelectionCommandService.ShowTierStatus(SESSION_ID));
    }

    @Test
    void tierCommandParsesForceFlagInAdapter() throws Exception {
        when(modelSelectionCommandService
                .handleTier(new ModelSelectionCommandService.SetTierSelection(TIER_SMART, true, SESSION_ID)))
                .thenReturn(new ModelSelectionCommandService.TierUpdated(TIER_SMART, true));

        CommandPort.CommandResult result = router.execute(CMD_TIER, List.of(TIER_SMART, "force"), CTX).get();

        assertTrue(result.success());
        assertEquals("command.tier.set.force smart", result.output());
        verify(modelSelectionCommandService)
                .handleTier(new ModelSelectionCommandService.SetTierSelection(TIER_SMART, true, SESSION_ID));
    }

    @Test
    void tierCommandRendersNonForcedUpdate() throws Exception {
        when(modelSelectionCommandService
                .handleTier(new ModelSelectionCommandService.SetTierSelection(TIER_SMART, false, SESSION_ID)))
                .thenReturn(new ModelSelectionCommandService.TierUpdated(TIER_SMART, false));

        CommandPort.CommandResult result = router.execute(CMD_TIER, List.of(TIER_SMART), CTX).get();

        assertTrue(result.success());
        assertEquals("command.tier.set smart", result.output());
        verify(modelSelectionCommandService)
                .handleTier(new ModelSelectionCommandService.SetTierSelection(TIER_SMART, false, SESSION_ID));
    }

    @Test
    void tierCommandRendersInvalidOutcomeFromApplicationService() throws Exception {
        when(modelSelectionCommandService
                .handleTier(new ModelSelectionCommandService.SetTierSelection(TIER_SMART, false, SESSION_ID)))
                .thenReturn(new ModelSelectionCommandService.InvalidTier());

        CommandPort.CommandResult result = router.execute(CMD_TIER, List.of(TIER_SMART), CTX).get();

        assertTrue(result.success());
        assertEquals("command.tier.invalid", result.output());
    }

    // ===== formatTokens =====

    @Test
    void formatTokensMillions() {
        assertEquals("1.5M", SystemCommandHandler.formatTokens(1_500_000));
    }

    @Test
    void formatTokensThousands() {
        assertEquals("12.3K", SystemCommandHandler.formatTokens(12_300));
    }

    @Test
    void formatTokensSmall() {
        assertEquals("999", SystemCommandHandler.formatTokens(999));
    }

    // ===== Schedule commands =====

    @Test
    void shouldHandleScheduleGoalCommand() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getGoal(TEST_GOAL_ID)).thenReturn(Optional.of(
                Goal.builder().id(TEST_GOAL_ID).title(TEST_GOAL_TITLE).status(Goal.GoalStatus.ACTIVE).tasks(List.of())
                        .build()));

        ScheduleEntry createdEntry = ScheduleEntry.builder()
                .id("sched-goal-12345678")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(TEST_GOAL_ID)
                .cronExpression("0 0 9 * * MON-FRI")
                .enabled(true)
                .build();
        when(scheduleService.createSchedule(
                eq(ScheduleEntry.ScheduleType.GOAL), eq(TEST_GOAL_ID),
                eq("0 9 * * MON-FRI"), eq(-1)))
                .thenReturn(createdEntry);

        CommandPort.CommandResult result = router.execute(CMD_SCHEDULE,
                List.of(CMD_GOAL, TEST_GOAL_ID, "0", "9", "*", "*", "MON-FRI"), CTX).get();
        assertFalse(result.success());
        assertTrue(result.output().contains("command.schedule.invalid-cron"));
        assertTrue(result.output().contains("Goal schedules are no longer supported"));
    }

    @Test
    void shouldHandleScheduleTaskCommand() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.findGoalForTask(TEST_TASK_ID)).thenReturn(Optional.of(
                Goal.builder().id("goal-1").title(TEST_GOAL_TITLE).tasks(List.of()).build()));

        ScheduleEntry createdEntry = ScheduleEntry.builder()
                .id("sched-task-12345678")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId(TEST_TASK_ID)
                .cronExpression("0 */30 * * * *")
                .enabled(true)
                .build();
        when(scheduleService.createSchedule(
                eq(ScheduleEntry.ScheduleType.TASK), eq(TEST_TASK_ID),
                eq("*/30 * * * *"), eq(-1)))
                .thenReturn(createdEntry);

        CommandPort.CommandResult result = router.execute(CMD_SCHEDULE,
                List.of("task", TEST_TASK_ID, "*/30", "*", "*", "*", "*"), CTX).get();
        assertFalse(result.success());
        assertTrue(result.output().contains("command.schedule.invalid-cron"));
        assertTrue(result.output().contains("Task schedules are no longer supported"));
    }

    @Test
    void shouldHandleScheduleListCommand() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(scheduleService.getSchedules()).thenReturn(List.of(
                ScheduleEntry.builder()
                        .id(TEST_SCHED_ID)
                        .type(ScheduleEntry.ScheduleType.GOAL)
                        .targetId("goal-1")
                        .cronExpression("0 0 9 * * MON-FRI")
                        .enabled(true)
                        .maxExecutions(-1)
                        .executionCount(3)
                        .nextExecutionAt(Instant.parse("2026-02-12T09:00:00Z"))
                        .build()));

        CommandPort.CommandResult result = router.execute(CMD_SCHEDULE, List.of("list"), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains(TEST_SCHED_ID));
        assertTrue(result.output().contains("GOAL"));
    }

    @Test
    void shouldHandleScheduleListEmptyCommand() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(scheduleService.getSchedules()).thenReturn(List.of());

        CommandPort.CommandResult result = router.execute(CMD_SCHEDULE, List.of("list"), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.schedule.list.empty"));
    }

    @Test
    void shouldHandleScheduleDefaultListWhenNoArgs() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(scheduleService.getSchedules()).thenReturn(List.of());

        CommandPort.CommandResult result = router.execute(CMD_SCHEDULE, List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.schedule.list.empty"));
    }

    @Test
    void shouldHandleScheduleDeleteCommand() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        CommandPort.CommandResult result = router.execute(CMD_SCHEDULE,
                List.of("delete", TEST_SCHED_ID), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.schedule.deleted"));
        verify(scheduleService).deleteSchedule(TEST_SCHED_ID);
    }

    @Test
    void shouldRejectInvalidCronInScheduleCommand() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getGoal(TEST_GOAL_ID)).thenReturn(Optional.of(
                Goal.builder().id(TEST_GOAL_ID).title(TEST_GOAL_TITLE).tasks(List.of()).build()));
        when(scheduleService.createSchedule(any(), anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalArgumentException("Invalid cron expression"));

        CommandPort.CommandResult result = router.execute(CMD_SCHEDULE,
                List.of(CMD_GOAL, TEST_GOAL_ID, "bad", "cron"), CTX).get();
        assertFalse(result.success());
        assertTrue(result.output().contains("command.schedule.invalid-cron"));
    }

    @Test
    void shouldReturnScheduleHelpText() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        CommandPort.CommandResult result = router.execute(CMD_SCHEDULE, List.of("help"), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.schedule.help.text"));
    }

    @Test
    void shouldRejectScheduleWhenAutoModeDisabled() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(false);

        CommandPort.CommandResult result = router.execute(CMD_SCHEDULE, List.of("list"), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.auto.not-available"));
    }

    @Test
    void shouldHandleScheduleGoalNotFound() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getGoal("missing-goal")).thenReturn(Optional.empty());

        CommandPort.CommandResult result = router.execute(CMD_SCHEDULE,
                List.of(CMD_GOAL, "missing-goal", "0", "9", "*", "*", "*"), CTX).get();
        assertFalse(result.success());
        assertTrue(result.output().contains("command.schedule.invalid-cron"));
        assertTrue(result.output().contains("Goal schedules are no longer supported"));
    }

    @Test
    void shouldHandleScheduleTaskNotFound() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.findGoalForTask("missing-task")).thenReturn(Optional.empty());

        CommandPort.CommandResult result = router.execute(CMD_SCHEDULE,
                List.of("task", "missing-task", "0", "9", "*", "*", "*"), CTX).get();
        assertFalse(result.success());
        assertTrue(result.output().contains("command.schedule.invalid-cron"));
        assertTrue(result.output().contains("Task schedules are no longer supported"));
    }

    @Test
    void shouldHandleScheduleGoalWithRepeatCount() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getGoal(TEST_GOAL_ID)).thenReturn(Optional.of(
                Goal.builder().id(TEST_GOAL_ID).title(TEST_GOAL_TITLE).tasks(List.of()).build()));

        ScheduleEntry createdEntry = ScheduleEntry.builder()
                .id("sched-goal-12345678")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(TEST_GOAL_ID)
                .cronExpression("0 0 12 * * *")
                .enabled(true)
                .maxExecutions(3)
                .build();
        when(scheduleService.createSchedule(
                eq(ScheduleEntry.ScheduleType.GOAL), eq(TEST_GOAL_ID),
                eq("0 12 * * *"), eq(3)))
                .thenReturn(createdEntry);

        CommandPort.CommandResult result = router.execute(CMD_SCHEDULE,
                List.of(CMD_GOAL, TEST_GOAL_ID, "0", "12", "*", "*", "*", "3"), CTX).get();
        assertFalse(result.success());
        assertTrue(result.output().contains("command.schedule.invalid-cron"));
        assertTrue(result.output().contains("Goal schedules are no longer supported"));
    }

    @Test
    void shouldHandleScheduleDeleteNotFound() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("not found"))
                .when(scheduleService).deleteSchedule("nonexistent");

        CommandPort.CommandResult result = router.execute(CMD_SCHEDULE,
                List.of("delete", "nonexistent"), CTX).get();
        assertFalse(result.success());
        assertTrue(result.output().contains("command.schedule.not-found"));
    }

    @Test
    void shouldShowScheduleGoalUsageWhenTooFewArgs() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        CommandPort.CommandResult result = router.execute(CMD_SCHEDULE,
                List.of(CMD_GOAL, TEST_GOAL_ID), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.schedule.goal.usage"));
    }

    // ===== Stop command =====

    @Test
    void stopCommandRequestsStop() throws Exception {
        CommandPort.CommandResult result = router.execute("stop", List.of(), CTX_WITH_CHANNEL).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.stop.ack"));
        verify(runCoordinator).requestStop(CHANNEL_TYPE_TELEGRAM, CHAT_ID);
    }

    @Test
    void stopCommandUsesSessionChatIdFromContext() throws Exception {
        CommandPort.CommandResult result = router.execute("stop", List.of(), CTX_WITH_SPLIT_IDENTITIES).get();
        assertTrue(result.success());
        verify(runCoordinator).requestStop(CHANNEL_TYPE_TELEGRAM, "conversation-42");
    }

    @Test
    void stopCommandFailsWithoutChannel() throws Exception {
        CommandPort.CommandResult result = router.execute("stop", List.of(), CTX).get();
        assertFalse(result.success());
        assertTrue(result.output().contains("command.stop.notAvailable"));
    }

    // ===== Model commands =====

    private static final String CMD_MODEL = "model";

    @Test
    void modelListCommandDelegatesAndRendersCatalog() throws Exception {
        when(modelSelectionCommandService.handleModel(new ModelSelectionCommandService.ListAvailableModels()))
                .thenReturn(
                        new ModelSelectionCommandService.AvailableModels(Map.of(
                                "openai", List.of(new ModelSelectionCommandService.AvailableModelOption(
                                        "gpt-5",
                                        "GPT-5",
                                        true,
                                        List.of("low", "medium"))))));

        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("list"), CTX).get();

        assertTrue(result.success());
        assertTrue(result.output().contains("command.model.list.title"));
        assertTrue(result.output().contains("gpt-5"));
        assertTrue(result.output().contains("reasoning: low, medium"));
        verify(modelSelectionCommandService).handleModel(new ModelSelectionCommandService.ListAvailableModels());
    }

    @Test
    void modelShowCommandDelegatesAndRendersOverview() throws Exception {
        when(modelSelectionCommandService.handleModel(new ModelSelectionCommandService.ShowModelSelection()))
                .thenReturn(new ModelSelectionCommandService.ModelSelectionOverview(List.of(
                        new ModelSelectionCommandService.TierSelection("balanced", null, null, false),
                        new ModelSelectionCommandService.TierSelection("coding", "openai/gpt-5", "medium", true))));

        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of(), CTX).get();

        assertTrue(result.success());
        assertTrue(result.output().contains("command.model.show.title"));
        assertTrue(result.output().contains("command.model.show.tier.default balanced"));
        assertTrue(result.output().contains("—"));
        assertTrue(result.output().contains("command.model.show.tier.override coding openai/gpt-5 medium"));
        verify(modelSelectionCommandService).handleModel(new ModelSelectionCommandService.ShowModelSelection());
    }

    @Test
    void modelListCommandRendersEmptyCatalog() throws Exception {
        when(modelSelectionCommandService.handleModel(new ModelSelectionCommandService.ListAvailableModels()))
                .thenReturn(new ModelSelectionCommandService.AvailableModels(Map.of()));

        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("list"), CTX).get();

        assertTrue(result.success());
        assertEquals("command.model.list.title\n\nNo models available.", result.output());
    }

    @Test
    void modelListCommandOmitsReasoningSuffixWhenModelHasNoReasoning() throws Exception {
        when(modelSelectionCommandService.handleModel(new ModelSelectionCommandService.ListAvailableModels()))
                .thenReturn(new ModelSelectionCommandService.AvailableModels(Map.of(
                        "openai", List.of(new ModelSelectionCommandService.AvailableModelOption(
                                "gpt-5-mini",
                                "GPT-5 Mini",
                                false,
                                List.of())))));

        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("list"), CTX).get();

        assertTrue(result.success());
        assertTrue(result.output().contains("command.model.list.model gpt-5-mini GPT-5 Mini"));
        assertFalse(result.output().contains("reasoning:"));
    }

    @Test
    void modelCommandRejectsInvalidTierInAdapter() throws Exception {
        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("unknown", "broken"), CTX).get();

        assertTrue(result.success());
        assertEquals("command.model.invalid.tier", result.output());
        verifyNoInteractions(modelSelectionCommandService);
    }

    @Test
    void modelCommandShowsUsageWhenActionMissing() throws Exception {
        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("coding"), CTX).get();

        assertTrue(result.success());
        assertEquals("command.model.usage", result.output());
        verifyNoInteractions(modelSelectionCommandService);
    }

    @Test
    void modelReasoningCommandRequiresLevelInAdapter() throws Exception {
        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("coding", "reasoning"), CTX).get();

        assertTrue(result.success());
        assertEquals("command.model.usage", result.output());
        verifyNoInteractions(modelSelectionCommandService);
    }

    @Test
    void modelResetCommandDelegatesAndRendersResetOutcome() throws Exception {
        when(modelSelectionCommandService.handleModel(new ModelSelectionCommandService.ResetModelOverride("coding")))
                .thenReturn(new ModelSelectionCommandService.ModelOverrideReset("coding"));

        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("coding", "reset"), CTX).get();

        assertTrue(result.success());
        assertEquals("command.model.reset coding", result.output());
        verify(modelSelectionCommandService).handleModel(new ModelSelectionCommandService.ResetModelOverride("coding"));
    }

    @Test
    void modelReasoningCommandDelegatesAndRendersUpdate() throws Exception {
        when(modelSelectionCommandService
                .handleModel(new ModelSelectionCommandService.SetReasoningLevel("coding", "high")))
                .thenReturn(new ModelSelectionCommandService.ModelReasoningSet("coding", "high"));

        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("coding", "reasoning", "high"), CTX).get();

        assertTrue(result.success());
        assertEquals("command.model.set.reasoning coding high", result.output());
        verify(modelSelectionCommandService)
                .handleModel(new ModelSelectionCommandService.SetReasoningLevel("coding", "high"));
    }

    @Test
    void modelCommandRendersSuccessfulOverrideWithDefaultReasoning() throws Exception {
        when(modelSelectionCommandService.handleModel(
                new ModelSelectionCommandService.SetModelOverride("coding", "openai/gpt-5"))).thenReturn(
                        new ModelSelectionCommandService.ModelOverrideSet("coding", "openai/gpt-5", "medium"));

        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("coding", "openai/gpt-5"), CTX).get();

        assertTrue(result.success());
        assertEquals("command.model.set coding openai/gpt-5 (reasoning: medium)", result.output());
    }

    @Test
    void modelCommandRendersSuccessfulOverrideWithoutDefaultReasoning() throws Exception {
        when(modelSelectionCommandService.handleModel(
                new ModelSelectionCommandService.SetModelOverride("coding", "openai/gpt-5-mini"))).thenReturn(
                        new ModelSelectionCommandService.ModelOverrideSet("coding", "openai/gpt-5-mini", null));

        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("coding", "openai/gpt-5-mini"), CTX).get();

        assertTrue(result.success());
        assertEquals("command.model.set coding openai/gpt-5-mini", result.output());
    }

    @Test
    void modelCommandRendersProviderMismatchFromApplicationOutcome() throws Exception {
        when(modelSelectionCommandService.handleModel(
                new ModelSelectionCommandService.SetModelOverride("coding", "anthropic/claude"))).thenReturn(
                        new ModelSelectionCommandService.ProviderNotConfigured(
                                "anthropic/claude",
                                List.of("openai", "google")));

        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("coding", "anthropic/claude"), CTX).get();

        assertTrue(result.success());
        assertEquals("command.model.invalid.provider anthropic/claude openai, google", result.output());
        verify(modelSelectionCommandService).handleModel(
                new ModelSelectionCommandService.SetModelOverride("coding", "anthropic/claude"));
    }

    @Test
    void modelCommandRendersInvalidTierFromApplicationOutcome() throws Exception {
        when(modelSelectionCommandService.handleModel(
                new ModelSelectionCommandService.SetModelOverride("coding", "openai/gpt-5"))).thenReturn(
                        new ModelSelectionCommandService.InvalidModelTier());

        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("coding", "openai/gpt-5"), CTX).get();

        assertTrue(result.success());
        assertEquals("command.model.invalid.tier", result.output());
    }

    @Test
    void modelCommandRendersInvalidModelFromApplicationOutcome() throws Exception {
        when(modelSelectionCommandService.handleModel(
                new ModelSelectionCommandService.SetModelOverride("coding", "unknown/bad"))).thenReturn(
                        new ModelSelectionCommandService.InvalidModel("unknown/bad"));

        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("coding", "unknown/bad"), CTX).get();

        assertTrue(result.success());
        assertEquals("command.model.invalid.model unknown/bad", result.output());
    }

    @Test
    void modelCommandRendersMissingOverrideFromApplicationOutcome() throws Exception {
        when(modelSelectionCommandService
                .handleModel(new ModelSelectionCommandService.SetReasoningLevel("coding", "high")))
                .thenReturn(new ModelSelectionCommandService.MissingModelOverride("coding"));

        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("coding", "reasoning", "high"), CTX).get();

        assertTrue(result.success());
        assertEquals("command.model.no.override coding", result.output());
    }

    @Test
    void modelCommandRendersMissingReasoningSupportFromApplicationOutcome() throws Exception {
        when(modelSelectionCommandService
                .handleModel(new ModelSelectionCommandService.SetReasoningLevel("coding", "high")))
                .thenReturn(new ModelSelectionCommandService.MissingReasoningSupport("openai/gpt-5-mini"));

        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("coding", "reasoning", "high"), CTX).get();

        assertTrue(result.success());
        assertEquals("command.model.no.reasoning openai/gpt-5-mini", result.output());
    }

    @Test
    void modelCommandRendersInvalidReasoningLevelFromApplicationOutcome() throws Exception {
        when(modelSelectionCommandService
                .handleModel(new ModelSelectionCommandService.SetReasoningLevel("coding", "xhigh")))
                .thenReturn(new ModelSelectionCommandService.InvalidReasoningLevel(
                        "xhigh",
                        List.of("low", "medium", "high")));

        CommandPort.CommandResult result = router.execute(CMD_MODEL, List.of("coding", "reasoning", "xhigh"), CTX)
                .get();

        assertTrue(result.success());
        assertEquals("command.model.invalid.reasoning xhigh low, medium, high", result.output());
    }
}
