package me.golemcore.bot.adapter.inbound.command;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.AutoModeChannelRegisteredEvent;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.UsageStats;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.CompactionService;
import me.golemcore.bot.domain.service.PlanExecutionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.CommandPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEFAULTS;

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

    private SkillComponent skillComponent;
    private SessionPort sessionService;
    private UsageTrackingPort usageTracker;
    private UserPreferencesService preferencesService;
    private CompactionService compactionService;
    private AutoModeService autoModeService;
    private PlanService planService;
    private PlanExecutionService planExecutionService;
    private ScheduleService scheduleService;
    private ApplicationEventPublisher eventPublisher;
    private CommandRouter router;

    private static final Map<String, Object> CTX = Map.of(
            "sessionId", SESSION_ID);

    @BeforeEach
    void setUp() {
        skillComponent = mock(SkillComponent.class);
        sessionService = mock(SessionPort.class);
        usageTracker = mock(UsageTrackingPort.class);
        compactionService = mock(CompactionService.class);

        // Default: pass through message key + args for easy assertion.
        preferencesService = mock(UserPreferencesService.class, inv -> {
            if (GET_MESSAGE_METHOD.equals(inv.getMethod().getName())) {
                Object[] allArgs = inv.getArguments();
                String key = (String) allArgs[0];
                if (allArgs.length > ONE) {
                    StringBuilder sb = new StringBuilder(key);
                    Object vararg = allArgs[1];
                    if (vararg instanceof Object[] arr) {
                        for (Object a : arr)
                            sb.append(" ").append(a);
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
        planService = mock(PlanService.class);
        planExecutionService = mock(PlanExecutionService.class);
        scheduleService = mock(ScheduleService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        ToolComponent tool1 = mockTool("filesystem", "File system operations", true);
        ToolComponent tool2 = mockTool("shell", "Shell command execution", true);
        ToolComponent tool3 = mockTool("disabled-tool", "Disabled tool", false);

        BotProperties properties = new BotProperties();

        router = new CommandRouter(
                skillComponent,
                List.of(tool1, tool2, tool3),
                sessionService,
                usageTracker,
                preferencesService,
                compactionService,
                autoModeService,
                planService,
                planExecutionService,
                scheduleService,
                eventPublisher,
                properties);
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
        assertTrue(router.hasCommand("status"));
        assertTrue(router.hasCommand("new"));
        assertTrue(router.hasCommand("reset"));
        assertTrue(router.hasCommand(CMD_COMPACT));
        assertTrue(router.hasCommand("help"));
        assertTrue(router.hasCommand(CMD_AUTO));
        assertTrue(router.hasCommand(CMD_GOALS));
        assertTrue(router.hasCommand(CMD_GOAL));
        assertTrue(router.hasCommand(CMD_DIARY));
        assertTrue(router.hasCommand("tasks"));
        assertTrue(router.hasCommand(CMD_SCHEDULE));
        assertTrue(router.hasCommand("plan"));
        assertTrue(router.hasCommand("plans"));
        assertFalse(router.hasCommand("unknown"));
        assertFalse(router.hasCommand("settings"));
    }

    @Test
    void listCommands() {
        List<CommandPort.CommandDefinition> commands = router.listCommands();
        assertEquals(7, commands.size());
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
        assertTrue(result.output().contains("filesystem"));
        assertTrue(result.output().contains("shell"));
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

        CommandPort.CommandResult result = router.execute("status", List.of(), CTX).get();
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

        CommandPort.CommandResult result = router.execute("status", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("3"));
        assertTrue(result.output().contains("command.status.usage.empty"));
    }

    @Test
    void newCommand() throws Exception {
        CommandPort.CommandResult result = router.execute("new", List.of(), CTX).get();
        assertTrue(result.success());
        verify(sessionService).clearMessages(SESSION_ID);
    }

    @Test
    void resetCommand() throws Exception {
        CommandPort.CommandResult result = router.execute("reset", List.of(), CTX).get();
        assertTrue(result.success());
        verify(sessionService).clearMessages(SESSION_ID);
    }

    @Test
    void compactWithSummary() throws Exception {
        List<Message> oldMessages = List.of(
                Message.builder().role("user").content("Hello").timestamp(Instant.now()).build(),
                Message.builder().role("assistant").content("Hi there!").timestamp(Instant.now()).build());
        when(sessionService.getMessagesToCompact(SESSION_ID, 10)).thenReturn(oldMessages);

        when(compactionService.summarize(oldMessages)).thenReturn("User greeted the bot.");
        Message summaryMsg = Message.builder().role("system").content("[Conversation summary]\nUser greeted the bot.")
                .build();
        when(compactionService.createSummaryMessage("User greeted the bot.")).thenReturn(summaryMsg);
        when(sessionService.compactWithSummary(SESSION_ID, 10, summaryMsg)).thenReturn(20);

        CommandPort.CommandResult result = router.execute(CMD_COMPACT, List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("20"));
        assertTrue(result.output().contains("command.compact.done.summary"));
        verify(compactionService).summarize(oldMessages);
        verify(sessionService).compactWithSummary(SESSION_ID, 10, summaryMsg);
    }

    @Test
    void compactFallbackWhenLlmUnavailable() throws Exception {
        List<Message> oldMessages = List.of(
                Message.builder().role("user").content("Hello").timestamp(Instant.now()).build());
        when(sessionService.getMessagesToCompact(SESSION_ID, 10)).thenReturn(oldMessages);

        when(compactionService.summarize(oldMessages)).thenReturn(null);
        when(sessionService.compactMessages(SESSION_ID, 10)).thenReturn(15);

        CommandPort.CommandResult result = router.execute(CMD_COMPACT, List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("15"));
        assertTrue(result.output().contains("command.compact.done "));
        verify(sessionService).compactMessages(SESSION_ID, 10);
        verify(sessionService, never()).compactWithSummary(anyString(), anyInt(), any());
    }

    @Test
    void compactCommandWithArg() throws Exception {
        List<Message> oldMessages = List.of(
                Message.builder().role("user").content("Hi").timestamp(Instant.now()).build());
        when(sessionService.getMessagesToCompact(SESSION_ID, 5)).thenReturn(oldMessages);
        when(compactionService.summarize(oldMessages)).thenReturn(null);
        when(sessionService.compactMessages(SESSION_ID, 5)).thenReturn(15);

        CommandPort.CommandResult result = router.execute(CMD_COMPACT, List.of("5"), CTX).get();
        assertTrue(result.success());
        verify(sessionService).compactMessages(SESSION_ID, 5);
    }

    @Test
    void compactCommandNothingToCompact() throws Exception {
        when(sessionService.getMessagesToCompact(SESSION_ID, 10)).thenReturn(List.of());
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

    @Test
    void goalLimitExceeded() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.createGoal(anyString(), any()))
                .thenThrow(new IllegalStateException("Max goals reached"));

        CommandPort.CommandResult result = router.execute(CMD_GOAL, List.of("Another", CMD_GOAL), CTX).get();
        assertFalse(result.success());
        assertTrue(result.output().contains("command.goal.limit"));
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
        when(sessionService.getMessagesToCompact(SESSION_ID, 1)).thenReturn(List.of());
        when(sessionService.getMessageCount(SESSION_ID)).thenReturn(3);

        router.execute(CMD_COMPACT, List.of("0"), CTX).get();
        verify(sessionService).getMessagesToCompact(SESSION_ID, 1);
    }

    @Test
    void compactClampsKeepToMax100() throws Exception {
        when(sessionService.getMessagesToCompact(SESSION_ID, 100)).thenReturn(List.of());
        when(sessionService.getMessageCount(SESSION_ID)).thenReturn(3);

        router.execute(CMD_COMPACT, List.of("999"), CTX).get();
        verify(sessionService).getMessagesToCompact(SESSION_ID, 100);
    }

    @Test
    void compactIgnoresNonNumericArg() throws Exception {
        when(sessionService.getMessagesToCompact(SESSION_ID, 10)).thenReturn(List.of());
        when(sessionService.getMessageCount(SESSION_ID)).thenReturn(3);

        router.execute(CMD_COMPACT, List.of("abc"), CTX).get();
        // Falls back to default 10
        verify(sessionService).getMessagesToCompact(SESSION_ID, 10);
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
        assertEquals(13, commands.size()); // 7 base + 6 auto mode
    }

    @Test
    void listCommandsIncludesPlanWhenEnabled() {
        when(planService.isFeatureEnabled()).thenReturn(true);

        List<CommandPort.CommandDefinition> commands = router.listCommands();
        assertTrue(commands.stream().anyMatch(c -> "plan".equals(c.name())));
        assertTrue(commands.stream().anyMatch(c -> "plans".equals(c.name())));
        assertEquals(9, commands.size()); // 7 base + 2 plan
    }

    // ===== formatTokens =====

    @Test
    void formatTokensMillions() {
        assertEquals("1.5M", CommandRouter.formatTokens(1_500_000));
    }

    @Test
    void formatTokensThousands() {
        assertEquals("12.3K", CommandRouter.formatTokens(12_300));
    }

    @Test
    void formatTokensSmall() {
        assertEquals("999", CommandRouter.formatTokens(999));
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
        assertTrue(result.success());
        assertTrue(result.output().contains("command.schedule.created"));
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
        assertTrue(result.success());
        assertTrue(result.output().contains("command.schedule.created"));
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
        assertTrue(result.output().contains("command.schedule.goal.not-found"));
    }

    @Test
    void shouldHandleScheduleTaskNotFound() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.findGoalForTask("missing-task")).thenReturn(Optional.empty());

        CommandPort.CommandResult result = router.execute(CMD_SCHEDULE,
                List.of("task", "missing-task", "0", "9", "*", "*", "*"), CTX).get();
        assertFalse(result.success());
        assertTrue(result.output().contains("command.schedule.task.not-found"));
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
        assertTrue(result.success());
        assertTrue(result.output().contains("command.schedule.created"));
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
}
