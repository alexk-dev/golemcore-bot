package me.golemcore.bot.auto;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.AutoModeChannelRegisteredEvent;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.ScheduleReportConfig;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.SessionRunCoordinator;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.channel.ChannelPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.tools.GoalManagementTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoModeSchedulerTest {

    private static final String CHANNEL_TYPE_TELEGRAM = "telegram";
    private static final String GOAL_ID = "goal-1";
    private static final String GOAL_TITLE = "Test Goal";
    private static final String TASK_ID = "task-1";
    private static final String TEST_CRON = "0 0 9 * * *";

    private AutoModeService autoModeService;
    private ScheduleService scheduleService;
    private SessionRunCoordinator sessionRunCoordinator;
    private GoalManagementTool goalManagementTool;
    private ChannelPort channelPort;
    private RuntimeConfigService runtimeConfigService;
    private SessionPort sessionPort;
    private SkillComponent skillComponent;
    private ScheduleReportSender reportSender;
    private ScheduledTaskShellRunner scheduledTaskShellRunner;
    private AutoModeScheduler scheduler;

    private AutoModeScheduler createScheduler() {
        ScheduledRunMessageFactory scheduledRunMessageFactory = new ScheduledRunMessageFactory(
                autoModeService,
                runtimeConfigService,
                skillComponent);
        ScheduledRunExecutor scheduledRunExecutor = new ScheduledRunExecutor(
                autoModeService,
                sessionRunCoordinator,
                runtimeConfigService,
                sessionPort,
                scheduledRunMessageFactory,
                reportSender,
                scheduledTaskShellRunner);
        return new AutoModeScheduler(
                autoModeService,
                scheduleService,
                runtimeConfigService,
                goalManagementTool,
                new ChannelRegistry(List.of(channelPort)),
                scheduledRunExecutor);
    }

    private static ScheduleReportConfig channelReport(String channelType, String chatId) {
        return ScheduleReportConfig.builder()
                .channelType(channelType)
                .chatId(chatId)
                .build();
    }

    @BeforeEach
    void setUp() {
        autoModeService = mock(AutoModeService.class);
        scheduleService = mock(ScheduleService.class);
        sessionRunCoordinator = mock(SessionRunCoordinator.class);
        goalManagementTool = mock(GoalManagementTool.class);
        channelPort = mock(ChannelPort.class);
        sessionPort = mock(SessionPort.class);
        skillComponent = mock(SkillComponent.class);

        when(channelPort.getChannelType()).thenReturn(CHANNEL_TYPE_TELEGRAM);
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(scheduleService.getDueSchedules()).thenReturn(List.of());
        when(sessionRunCoordinator.submit(any(Message.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(skillComponent.findByName(anyString())).thenReturn(Optional.empty());

        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isAutoModeEnabled()).thenReturn(true);
        when(runtimeConfigService.getAutoTaskTimeLimitMinutes()).thenReturn(10);
        when(runtimeConfigService.isAutoNotifyMilestonesEnabled()).thenReturn(true);
        when(runtimeConfigService.isAutoStartEnabled()).thenReturn(false);
        when(runtimeConfigService.isAutoReflectionEnabled()).thenReturn(true);
        when(runtimeConfigService.getAutoReflectionModelTier()).thenReturn("deep");
        when(runtimeConfigService.isAutoReflectionTierPriority()).thenReturn(false);

        reportSender = mock(ScheduleReportSender.class);
        scheduledTaskShellRunner = mock(ScheduledTaskShellRunner.class);

        scheduler = createScheduler();
    }

    @Test
    void tickSkipsWhenAutoModeDisabled() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(false);

        scheduler.tick();

        verify(sessionRunCoordinator, never()).submit(any(Message.class));
        verify(scheduleService, never()).getDueSchedules();
    }

    @Test
    void tickSkipsWhenNoDueSchedules() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        when(scheduleService.getDueSchedules()).thenReturn(List.of());

        scheduler.tick();

        verify(sessionRunCoordinator, never()).submit(any(Message.class));
    }

    @Test
    void shouldReevaluateDueSchedulesAfterFailureBeforeRunningAnotherScheduleForSameTask() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        ScheduledTask task = ScheduledTask.builder()
                .id("scheduled-task-1")
                .title("Nightly cleanup")
                .executionMode(ScheduledTask.ExecutionMode.AGENT_PROMPT)
                .prompt("cleanup")
                .build();
        when(autoModeService.getScheduledTask("scheduled-task-1")).thenReturn(Optional.of(task));
        when(runtimeConfigService.isAutoReflectionEnabled()).thenReturn(false);

        ScheduleEntry first = ScheduleEntry.builder()
                .id("sched-1")
                .type(ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .targetId("scheduled-task-1")
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        ScheduleEntry second = ScheduleEntry.builder()
                .id("sched-2")
                .type(ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .targetId("scheduled-task-1")
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules())
                .thenReturn(List.of(first, second))
                .thenReturn(List.of());
        when(sessionRunCoordinator.submit(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.getMetadata().put(ContextAttributes.AUTO_RUN_STATUS, "FAILED");
            message.getMetadata().put(ContextAttributes.AUTO_RUN_FAILURE_SUMMARY, "boom");
            message.getMetadata().put(ContextAttributes.AUTO_RUN_FAILURE_FINGERPRINT, "boom");
            return CompletableFuture.completedFuture(null);
        });

        scheduler.tick();

        verify(sessionRunCoordinator, times(1)).submit(any(Message.class));
        verify(scheduleService).recordFailedAttempt("sched-1");
        verify(scheduleService, never()).recordFailedAttempt("sched-2");
        verify(scheduleService, times(2)).getDueSchedules();
    }

    @Test
    void shouldExposeExecutingStateWhileTickIsRunning() throws Exception {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-busy")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        CountDownLatch submitted = new CountDownLatch(1);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        when(sessionRunCoordinator.submit(any(Message.class))).thenAnswer(invocation -> {
            submitted.countDown();
            return completion;
        });

        CompletableFuture<Void> tickFuture = CompletableFuture.runAsync(scheduler::tick);
        assertTrue(submitted.await(1, TimeUnit.SECONDS), "tick should reach coordinator submission");
        assertTrue(scheduler.isExecuting());

        completion.complete(null);
        tickFuture.get(1, TimeUnit.SECONDS);

        assertFalse(scheduler.isExecuting());
    }

    @Test
    void shouldProcessGoalScheduleWithPendingTask() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        AutoTask task = AutoTask.builder()
                .id(TASK_ID)
                .goalId(GOAL_ID)
                .title("Write unit tests")
                .status(AutoTask.TaskStatus.PENDING)
                .order(1)
                .build();

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-abc")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sessionRunCoordinator).submit(captor.capture());

        Message sent = captor.getValue();
        assertTrue(sent.getContent().contains("Write unit tests"));
        assertEquals("user", sent.getRole());
        assertEquals(true, sent.getMetadata().get(ContextAttributes.AUTO_MODE));
        assertEquals("sched-goal-abc", sent.getMetadata().get(ContextAttributes.AUTO_SCHEDULE_ID));
        assertEquals(TASK_ID, sent.getMetadata().get(ContextAttributes.AUTO_TASK_ID));
        assertEquals("INTERNAL", sent.getMetadata().get("trace.root.kind"));
        assertEquals("auto.schedule.goal", sent.getMetadata().get("trace.name"));
        assertTrue(sent.getMetadata().get("trace.id") instanceof String);
        assertTrue(sent.getMetadata().get("trace.span.id") instanceof String);
        verify(scheduleService).recordExecution("sched-goal-abc");
    }

    @Test
    void shouldProcessGoalScheduleForUnplannedGoal() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-abc")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sessionRunCoordinator).submit(captor.capture());

        Message sent = captor.getValue();
        assertTrue(sent.getContent().contains("Plan tasks for goal"));
        assertTrue(sent.getContent().contains(GOAL_TITLE));
        assertEquals("sched-goal-abc", sent.getMetadata().get(ContextAttributes.AUTO_SCHEDULE_ID));
        assertNull(sent.getMetadata().get(ContextAttributes.AUTO_TASK_ID));
    }

    @Test
    void shouldUseGoalPromptWhenPlanningUnplannedGoal() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .prompt("Break the release down into concrete tasks")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-prompt")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sessionRunCoordinator).submit(captor.capture());

        Message sent = captor.getValue();
        assertTrue(sent.getContent().contains("Break the release down into concrete tasks"));
    }

    @Test
    void shouldProcessTaskScheduleForSpecificTask() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        AutoTask task = AutoTask.builder()
                .id(TASK_ID)
                .goalId(GOAL_ID)
                .title("Implement feature X")
                .status(AutoTask.TaskStatus.PENDING)
                .order(1)
                .build();

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .build();
        when(autoModeService.findGoalForTask(TASK_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-task-xyz")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId(TASK_ID)
                .cronExpression("0 30 14 * * *")
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sessionRunCoordinator).submit(captor.capture());

        Message sent = captor.getValue();
        assertTrue(sent.getContent().contains("Implement feature X"));
        verify(scheduleService).recordExecution("sched-task-xyz");
    }

    @Test
    void shouldUseTaskPromptWhenTaskHasCustomPrompt() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        AutoTask task = AutoTask.builder()
                .id(TASK_ID)
                .goalId(GOAL_ID)
                .title("Implement feature X")
                .prompt("Implement feature X with migration, tests, and rollout notes")
                .status(AutoTask.TaskStatus.PENDING)
                .order(1)
                .build();

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .build();
        when(autoModeService.findGoalForTask(TASK_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-task-prompt")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId(TASK_ID)
                .cronExpression("0 30 14 * * *")
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sessionRunCoordinator).submit(captor.capture());

        Message sent = captor.getValue();
        assertTrue(sent.getContent().contains("migration, tests, and rollout notes"));
    }

    @Test
    void shouldIncludeReflectionTierMetadataFromTaskSettings() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        AutoTask task = AutoTask.builder()
                .id(TASK_ID)
                .goalId(GOAL_ID)
                .title("Recover feature X")
                .reflectionModelTier("deep")
                .reflectionTierPriority(true)
                .status(AutoTask.TaskStatus.PENDING)
                .order(1)
                .build();

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .build();
        when(autoModeService.findGoalForTask(TASK_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-task-reflection-tier")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId(TASK_ID)
                .cronExpression("0 30 14 * * *")
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sessionRunCoordinator).submit(captor.capture());
        Message sent = captor.getValue();
        assertEquals("deep", sent.getMetadata().get(ContextAttributes.AUTO_REFLECTION_TIER));
        assertEquals(true, sent.getMetadata().get(ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY));
    }

    @Test
    void shouldTriggerReflectionRunAfterSecondFailure() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        when(autoModeService.shouldTriggerReflection(GOAL_ID, TASK_ID)).thenReturn(true);
        when(autoModeService.isReflectionTierPriority(GOAL_ID, TASK_ID)).thenReturn(true);
        when(autoModeService.resolveTaskReflectionState(GOAL_ID, TASK_ID))
                .thenReturn(new AutoModeService.TaskReflectionState(
                        null,
                        false,
                        "deep",
                        true,
                        "reviewer-skill",
                        2,
                        true,
                        "403 from tool",
                        "403 from tool",
                        null));
        when(skillComponent.findByName("reviewer-skill")).thenReturn(Optional.of(Skill.builder()
                .name("reviewer-skill")
                .reflectionTier("coding")
                .build()));
        when(autoModeService.resolveReflectionTier(eq(GOAL_ID), eq(TASK_ID), any(Skill.class))).thenReturn("coding");

        AutoTask task = AutoTask.builder()
                .id(TASK_ID)
                .goalId(GOAL_ID)
                .title("Implement feature X")
                .status(AutoTask.TaskStatus.FAILED)
                .order(1)
                .build();

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .build();
        when(autoModeService.findGoalForTask(TASK_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-task-reflect")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId(TASK_ID)
                .cronExpression("0 30 14 * * *")
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        when(sessionRunCoordinator.submit(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            if (Boolean.TRUE.equals(message.getMetadata().get(ContextAttributes.AUTO_REFLECTION_ACTIVE))) {
                message.getMetadata().put(ContextAttributes.AUTO_RUN_STATUS, "REFLECTION_COMPLETED");
                message.getMetadata().put(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT,
                        "Use a different tool and verify permissions first");
            } else {
                message.getMetadata().put(ContextAttributes.AUTO_RUN_STATUS, "FAILED");
                message.getMetadata().put(ContextAttributes.AUTO_RUN_FAILURE_SUMMARY, "403 from tool");
                message.getMetadata().put(ContextAttributes.AUTO_RUN_FAILURE_FINGERPRINT, "403 from tool");
                message.getMetadata().put(ContextAttributes.AUTO_RUN_ACTIVE_SKILL, "reviewer-skill");
            }
            return CompletableFuture.completedFuture(null);
        });

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sessionRunCoordinator, times(2)).submit(captor.capture());
        List<Message> submitted = captor.getAllValues();
        assertNull(submitted.get(0).getMetadata().get(ContextAttributes.AUTO_REFLECTION_ACTIVE));
        assertEquals(true, submitted.get(1).getMetadata().get(ContextAttributes.AUTO_REFLECTION_ACTIVE));
        assertEquals("coding", submitted.get(1).getMetadata().get(ContextAttributes.AUTO_REFLECTION_TIER));
        verify(autoModeService).applyReflectionResult(GOAL_ID, TASK_ID,
                "Use a different tool and verify permissions first");
        verify(autoModeService).recordAutoRunFailure(GOAL_ID, TASK_ID, "403 from tool", "403 from tool",
                "reviewer-skill");
    }

    @Test
    void shouldTriggerGoalLevelReflectionForUnplannedGoalAfterRepeatedFailure() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        when(autoModeService.shouldTriggerReflection(GOAL_ID, null)).thenReturn(true);
        when(autoModeService.isReflectionTierPriority(GOAL_ID, null)).thenReturn(true);
        when(autoModeService.resolveTaskReflectionState(GOAL_ID, null))
                .thenReturn(new AutoModeService.TaskReflectionState(
                        "deep",
                        true,
                        null,
                        false,
                        "planner-skill",
                        2,
                        true,
                        "planner timeout",
                        "planner timeout",
                        null));
        when(skillComponent.findByName("planner-skill")).thenReturn(Optional.of(Skill.builder()
                .name("planner-skill")
                .reflectionTier("coding")
                .build()));
        when(autoModeService.resolveReflectionTier(eq(GOAL_ID), eq(null), any(Skill.class))).thenReturn("deep");

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .reflectionModelTier("deep")
                .reflectionTierPriority(true)
                .tasks(new ArrayList<>())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-reflect")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        when(sessionRunCoordinator.submit(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            if (Boolean.TRUE.equals(message.getMetadata().get(ContextAttributes.AUTO_REFLECTION_ACTIVE))) {
                message.getMetadata().put(ContextAttributes.AUTO_RUN_STATUS, "REFLECTION_COMPLETED");
                message.getMetadata().put(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT,
                        "Ask for a smaller planning slice and enumerate blockers first");
            } else {
                message.getMetadata().put(ContextAttributes.AUTO_RUN_STATUS, "FAILED");
                message.getMetadata().put(ContextAttributes.AUTO_RUN_FAILURE_SUMMARY, "planner timeout");
                message.getMetadata().put(ContextAttributes.AUTO_RUN_FAILURE_FINGERPRINT, "planner timeout");
                message.getMetadata().put(ContextAttributes.AUTO_RUN_ACTIVE_SKILL, "planner-skill");
            }
            return CompletableFuture.completedFuture(null);
        });

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sessionRunCoordinator, times(2)).submit(captor.capture());
        List<Message> submitted = captor.getAllValues();
        assertNull(submitted.get(0).getMetadata().get(ContextAttributes.AUTO_TASK_ID));
        assertEquals(true, submitted.get(1).getMetadata().get(ContextAttributes.AUTO_REFLECTION_ACTIVE));
        assertNull(submitted.get(1).getMetadata().get(ContextAttributes.AUTO_TASK_ID));
        assertEquals("deep", submitted.get(1).getMetadata().get(ContextAttributes.AUTO_REFLECTION_TIER));
        assertTrue(submitted.get(1).getContent().contains("planning goal"));
        verify(autoModeService).recordAutoRunFailure(GOAL_ID, null, "planner timeout", "planner timeout",
                "planner-skill");
        verify(autoModeService).applyReflectionResult(GOAL_ID, null,
                "Ask for a smaller planning slice and enumerate blockers first");
    }

    @Test
    void shouldFallbackToConfiguredReflectionTierWhenLastUsedSkillIsUnavailable() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        when(autoModeService.shouldTriggerReflection(GOAL_ID, TASK_ID)).thenReturn(true);
        when(autoModeService.isReflectionTierPriority(GOAL_ID, TASK_ID)).thenReturn(false);
        when(autoModeService.resolveTaskReflectionState(GOAL_ID, TASK_ID))
                .thenReturn(new AutoModeService.TaskReflectionState(
                        "deep",
                        false,
                        "smart",
                        false,
                        "missing-skill",
                        2,
                        true,
                        "tool timeout",
                        "tool timeout",
                        null));
        when(skillComponent.findByName("missing-skill")).thenReturn(Optional.empty());
        when(autoModeService.resolveReflectionTier(GOAL_ID, TASK_ID, null)).thenReturn("deep");

        AutoTask task = AutoTask.builder()
                .id(TASK_ID)
                .goalId(GOAL_ID)
                .title("Implement feature X")
                .status(AutoTask.TaskStatus.FAILED)
                .order(1)
                .build();
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .build();
        when(autoModeService.findGoalForTask(TASK_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-task-reflect-fallback")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId(TASK_ID)
                .cronExpression("0 30 14 * * *")
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        when(sessionRunCoordinator.submit(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            if (Boolean.TRUE.equals(message.getMetadata().get(ContextAttributes.AUTO_REFLECTION_ACTIVE))) {
                message.getMetadata().put(ContextAttributes.AUTO_RUN_STATUS, "REFLECTION_COMPLETED");
                message.getMetadata().put(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT,
                        "Use the goal-level fallback strategy");
            } else {
                message.getMetadata().put(ContextAttributes.AUTO_RUN_STATUS, "FAILED");
                message.getMetadata().put(ContextAttributes.AUTO_RUN_FAILURE_SUMMARY, "tool timeout");
                message.getMetadata().put(ContextAttributes.AUTO_RUN_FAILURE_FINGERPRINT, "tool timeout");
                message.getMetadata().put(ContextAttributes.AUTO_RUN_ACTIVE_SKILL, "missing-skill");
            }
            return CompletableFuture.completedFuture(null);
        });

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sessionRunCoordinator, times(2)).submit(captor.capture());
        List<Message> submitted = captor.getAllValues();
        assertEquals("deep", submitted.get(1).getMetadata().get(ContextAttributes.AUTO_REFLECTION_TIER));
        verify(autoModeService).applyReflectionResult(GOAL_ID, TASK_ID, "Use the goal-level fallback strategy");
    }

    @Test
    void shouldMarkFailureWhenScheduleTimesOut() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        AutoTask task = AutoTask.builder()
                .id(TASK_ID)
                .goalId(GOAL_ID)
                .title("Implement feature X")
                .status(AutoTask.TaskStatus.PENDING)
                .order(1)
                .build();

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .build();
        when(autoModeService.findGoalForTask(TASK_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-task-timeout")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId(TASK_ID)
                .cronExpression("0 30 14 * * *")
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));
        when(runtimeConfigService.getAutoTaskTimeLimitMinutes()).thenReturn(0);
        when(sessionRunCoordinator.submit(any(Message.class))).thenReturn(new CompletableFuture<>());

        scheduler.tick();

        verify(autoModeService).recordAutoRunFailure(eq(GOAL_ID), eq(TASK_ID), contains("timed out"), eq("timeout"),
                eq(null));
    }

    @Test
    void shouldProcessStandaloneTaskScheduleStoredInInboxGoal() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        AutoTask task = AutoTask.builder()
                .id("task-standalone")
                .goalId("inbox")
                .title("Check support queue")
                .prompt("   ")
                .status(AutoTask.TaskStatus.PENDING)
                .order(1)
                .build();
        Goal inbox = Goal.builder()
                .id("inbox")
                .title("Inbox")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .build();
        when(autoModeService.findGoalForTask("task-standalone")).thenReturn(Optional.of(inbox));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-task-standalone")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId("task-standalone")
                .cronExpression("0 0 15 * * *")
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sessionRunCoordinator).submit(captor.capture());

        Message sent = captor.getValue();
        assertTrue(sent.getContent().contains("Check support queue"));
        assertEquals("sched-task-standalone", sent.getMetadata().get(ContextAttributes.AUTO_SCHEDULE_ID));
        assertEquals("task-standalone", sent.getMetadata().get(ContextAttributes.AUTO_TASK_ID));
        verify(scheduleService).recordExecution("sched-task-standalone");
    }

    @Test
    void shouldExecutePersistentScheduledTaskSchedule() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        ScheduledTask task = ScheduledTask.builder()
                .id("scheduled-task-1")
                .title("Refresh inbox")
                .prompt("Review feeds")
                .build();
        when(autoModeService.getScheduledTask("scheduled-task-1")).thenReturn(Optional.of(task));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-persistent-1")
                .type(ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .targetId("scheduled-task-1")
                .cronExpression("0 0 15 * * *")
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sessionRunCoordinator).submit(captor.capture());

        Message sent = captor.getValue();
        assertTrue(sent.getContent().contains("Review feeds"));
        assertEquals("sched-persistent-1", sent.getMetadata().get(ContextAttributes.AUTO_SCHEDULE_ID));
        assertEquals("scheduled-task-1", sent.getMetadata().get(ContextAttributes.AUTO_SCHEDULED_TASK_ID));
        verify(autoModeService).recordScheduledTaskSuccess("scheduled-task-1", null);
        verify(scheduleService).recordExecution("sched-persistent-1");
    }

    @Test
    void shouldSkipTickWhenExecutionInProgress() throws Exception {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-slow")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        CompletableFuture<Void> completion = new CompletableFuture<>();
        CountDownLatch submitStarted = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);
        when(sessionRunCoordinator.submit(any(Message.class))).thenAnswer(invocation -> {
            submitStarted.countDown();
            allowFinish.await(1, TimeUnit.SECONDS);
            return completion;
        });

        Thread firstTick = new Thread(scheduler::tick);
        firstTick.start();
        assertTrue(submitStarted.await(1, TimeUnit.SECONDS));

        scheduler.tick();

        verify(sessionRunCoordinator, times(1)).submit(any(Message.class));
        verify(scheduleService, times(1)).getDueSchedules();

        completion.complete(null);
        allowFinish.countDown();
        firstTick.join(1000);
    }

    @Test
    void shouldRecordExecutionAfterProcessing() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-rec")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        verify(scheduleService).recordExecution("sched-goal-rec");
    }

    @Test
    void shouldWaitForCoordinatorCompletionBeforeRecordingExecution() throws Exception {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-await")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        CompletableFuture<Void> completion = new CompletableFuture<>();
        CountDownLatch submitted = new CountDownLatch(1);
        when(sessionRunCoordinator.submit(any(Message.class))).thenAnswer(invocation -> {
            submitted.countDown();
            return completion;
        });

        AtomicBoolean finished = new AtomicBoolean(false);
        Thread tickThread = new Thread(() -> {
            scheduler.tick();
            finished.set(true);
        });
        tickThread.start();

        assertTrue(submitted.await(1, TimeUnit.SECONDS));
        verify(scheduleService, never()).recordExecution("sched-goal-await");

        completion.complete(null);
        tickThread.join(1000);

        assertTrue(finished.get());
        verify(scheduleService).recordExecution("sched-goal-await");
    }

    @Test
    void shouldClearSessionContextBeforeRunWhenScheduleRequestsIt() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        scheduler.registerChannel(CHANNEL_TYPE_TELEGRAM, "session-42", "transport-42");

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, "session-42"))
                .thenReturn(AgentSession.builder()
                        .id(CHANNEL_TYPE_TELEGRAM + ":session-42")
                        .channelType(CHANNEL_TYPE_TELEGRAM)
                        .chatId("session-42")
                        .build());

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-clear")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .clearContextBeforeRun(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        verify(sessionPort).clearMessages(CHANNEL_TYPE_TELEGRAM + ":session-42");
        verify(sessionRunCoordinator).submit(any(Message.class));
    }

    @Test
    void shouldSkipGoalScheduleWhenGoalNotFound() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        when(autoModeService.getGoal("missing-goal")).thenReturn(Optional.empty());

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-missing")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId("missing-goal")
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        verify(sessionRunCoordinator, never()).submit(any(Message.class));
        verify(scheduleService, never()).recordExecution("sched-goal-missing");
        verify(scheduleService).disableSchedule("sched-goal-missing");
    }

    @Test
    void shouldAdvanceFailedScheduleAttemptToAvoidRetryLoop() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        when(autoModeService.getScheduledTask("scheduled-task-1")).thenReturn(Optional.of(ScheduledTask.builder()
                .id("scheduled-task-1")
                .title("Daily summary")
                .prompt("Summarize status")
                .build()));
        when(sessionRunCoordinator.submit(any(Message.class)))
                .thenThrow(new IllegalStateException("submit failed"));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-failed-attempt")
                .type(ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .targetId("scheduled-task-1")
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        verify(scheduleService).recordFailedAttempt("sched-failed-attempt");
        verify(scheduleService, never()).recordExecution("sched-failed-attempt");
        verify(scheduleService, never()).disableSchedule("sched-failed-attempt");
    }

    @Test
    void shouldBackoffWhenScheduledRunFinishesWithFailedStatus() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        when(autoModeService.getScheduledTask("scheduled-task-1")).thenReturn(Optional.of(ScheduledTask.builder()
                .id("scheduled-task-1")
                .title("Daily summary")
                .prompt("Summarize status")
                .build()));
        when(runtimeConfigService.isAutoReflectionEnabled()).thenReturn(false);
        when(sessionRunCoordinator.submit(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.getMetadata().put(ContextAttributes.AUTO_RUN_STATUS, "FAILED");
            message.getMetadata().put(ContextAttributes.AUTO_RUN_FAILURE_SUMMARY, "boom");
            message.getMetadata().put(ContextAttributes.AUTO_RUN_FAILURE_FINGERPRINT, "fp");
            return CompletableFuture.completedFuture(null);
        });

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-failed-status")
                .type(ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .targetId("scheduled-task-1")
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        verify(scheduleService).recordFailedAttempt("sched-failed-status");
        verify(scheduleService, never()).recordExecution("sched-failed-status");
        verify(scheduleService, never()).disableSchedule("sched-failed-status");
    }

    @Test
    void shouldSkipGoalScheduleWhenGoalNotActive() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.COMPLETED)
                .tasks(new ArrayList<>())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-done")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        verify(sessionRunCoordinator, never()).submit(any(Message.class));
    }

    @Test
    void shouldResetCompletedTaskToPendingAndRun() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        AutoTask task = AutoTask.builder()
                .id("task-done")
                .goalId(GOAL_ID)
                .title("Done task")
                .status(AutoTask.TaskStatus.COMPLETED)
                .order(1)
                .build();

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .build();
        when(autoModeService.findGoalForTask("task-done")).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-task-done")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId("task-done")
                .cronExpression("0 0 12 * * *")
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        verify(autoModeService).updateTaskStatus(GOAL_ID, "task-done", AutoTask.TaskStatus.PENDING, null);
        verify(sessionRunCoordinator).submit(any(Message.class));
    }

    @Test
    void registerChannelStoresChannelInfo() {
        scheduler.registerChannel(CHANNEL_TYPE_TELEGRAM, "chat-123");

        scheduler.sendMilestoneNotification("Task done");

        verify(channelPort).sendMessage(eq("chat-123"), contains("Task done"));
    }

    @Test
    void sendMilestoneNotificationSendsToRegisteredChannel() {
        scheduler.registerChannel(CHANNEL_TYPE_TELEGRAM, "chat-456");

        scheduler.sendMilestoneNotification("Goal completed: Deploy v2");

        verify(channelPort).sendMessage(eq("chat-456"), contains("Goal completed: Deploy v2"));
    }

    @Test
    void sendMilestoneNotificationDoesNothingWhenNoChannelRegistered() {
        scheduler.sendMilestoneNotification("Some notification");

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void sendMilestoneNotificationDoesNothingWhenNotifyMilestonesDisabled() {
        when(runtimeConfigService.isAutoNotifyMilestonesEnabled()).thenReturn(false);

        scheduler.registerChannel(CHANNEL_TYPE_TELEGRAM, "chat-789");

        scheduler.sendMilestoneNotification("Should not be sent");

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void onChannelRegisteredDelegatesToRegisterChannel() {
        AutoModeChannelRegisteredEvent event = new AutoModeChannelRegisteredEvent(CHANNEL_TYPE_TELEGRAM,
                "chat-event-123");

        scheduler.onChannelRegistered(event);

        scheduler.sendMilestoneNotification("Event test");
        verify(channelPort).sendMessage(eq("chat-event-123"), contains("Event test"));
    }

    @Test
    void sendMilestoneNotificationHandlesChannelNotFound() {
        scheduler.registerChannel("unknown-channel", "chat-123");

        assertDoesNotThrow(() -> scheduler.sendMilestoneNotification("Test"));
        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void sendMilestoneNotificationHandlesExecutionException() {
        scheduler.registerChannel(CHANNEL_TYPE_TELEGRAM, "chat-fail");
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Send failed")));

        assertDoesNotThrow(() -> scheduler.sendMilestoneNotification("Should handle error"));
    }

    @Test
    void tickUsesRegisteredChannelForChatId() {
        scheduler.registerChannel(CHANNEL_TYPE_TELEGRAM, "chat-999");
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-ch")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sessionRunCoordinator).submit(captor.capture());

        Message sent = captor.getValue();
        assertEquals("chat-999", sent.getChatId());
        assertEquals(CHANNEL_TYPE_TELEGRAM, sent.getChannelType());
    }

    @Test
    void tickUsesFallbackChatIdWhenNoChannelRegistered() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-fallback")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sessionRunCoordinator).submit(captor.capture());

        Message sent = captor.getValue();
        assertEquals("auto", sent.getChatId());
        assertEquals("auto", sent.getChannelType());
    }

    @Test
    void shutdownDoesNotThrowWhenNotInitialized() {
        AutoModeScheduler freshScheduler = createScheduler();

        assertDoesNotThrow(freshScheduler::shutdown);
    }

    @Test
    void shouldAutoStartWhenEnabledInConfig() {
        when(runtimeConfigService.isAutoStartEnabled()).thenReturn(true);
        when(autoModeService.isAutoModeEnabled()).thenReturn(false);

        AutoModeScheduler newScheduler = createScheduler();

        newScheduler.init();

        verify(autoModeService).enableAutoMode();

        newScheduler.shutdown();
    }

    @Test
    void shouldNotAutoStartWhenAutoStartDisabled() {
        when(runtimeConfigService.isAutoStartEnabled()).thenReturn(false);

        AutoModeScheduler newScheduler = createScheduler();

        newScheduler.init();

        verify(autoModeService, never()).enableAutoMode();

        newScheduler.shutdown();
    }

    @Test
    void shouldNotAutoStartWhenAlreadyEnabled() {
        when(runtimeConfigService.isAutoStartEnabled()).thenReturn(true);
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        AutoModeScheduler newScheduler = createScheduler();

        newScheduler.init();

        verify(autoModeService, never()).enableAutoMode();

        newScheduler.shutdown();
    }

    @Test
    void shouldInitializeSchedulerEvenWhenFeatureDisabledAtStartup() {
        when(runtimeConfigService.isAutoModeEnabled()).thenReturn(false);

        AutoModeScheduler newScheduler = createScheduler();

        newScheduler.init();

        verify(autoModeService).loadState();
        verify(autoModeService, never()).enableAutoMode();

        newScheduler.shutdown();
    }

    @Test
    void tickShouldSkipWhenRuntimeFeatureDisabled() {
        when(runtimeConfigService.isAutoModeEnabled()).thenReturn(false);
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        scheduler.tick();

        verify(scheduleService, never()).getDueSchedules();
        verify(sessionRunCoordinator, never()).submit(any(Message.class));
    }

    @Test
    void shouldApplyRuntimeFeatureToggleImmediatelyBetweenTicks() {
        when(runtimeConfigService.isAutoModeEnabled()).thenReturn(false, true);
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-toggle")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();
        scheduler.tick();

        verify(sessionRunCoordinator, times(1)).submit(any(Message.class));
    }

    @Test
    void shouldSendReportToConfiguredChannelAfterSuccessfulRun() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(List.of(AutoTask.builder()
                        .id(TASK_ID)
                        .title("Verify deployments")
                        .status(AutoTask.TaskStatus.PENDING)
                        .order(1)
                        .build()))
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-report")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .report(channelReport(CHANNEL_TYPE_TELEGRAM, "999"))
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        when(sessionRunCoordinator.submit(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.getMetadata().put(ContextAttributes.AUTO_RUN_STATUS, "COMPLETED");
            message.getMetadata().put(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT,
                    "All 3 deployments are healthy. No issues found.");
            return CompletableFuture.completedFuture(null);
        });

        scheduler.registerChannel(CHANNEL_TYPE_TELEGRAM, "123");
        scheduler.tick();

        ArgumentCaptor<ScheduleEntry> scheduleCaptor = ArgumentCaptor.forClass(ScheduleEntry.class);
        ArgumentCaptor<String> headerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(reportSender).sendReport(scheduleCaptor.capture(), headerCaptor.capture(), textCaptor.capture(), any());

        assertEquals("sched-report", scheduleCaptor.getValue().getId());
        assertEquals("All 3 deployments are healthy. No issues found.", textCaptor.getValue());
    }

    @Test
    void shouldAutoResolveChatIdFromRegisteredChannelWhenReportChatIdIsNull() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(List.of(AutoTask.builder()
                        .id(TASK_ID)
                        .title("Check logs")
                        .status(AutoTask.TaskStatus.PENDING)
                        .order(1)
                        .build()))
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-report-auto")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .report(channelReport(CHANNEL_TYPE_TELEGRAM, null))
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        when(sessionRunCoordinator.submit(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.getMetadata().put(ContextAttributes.AUTO_RUN_STATUS, "COMPLETED");
            message.getMetadata().put(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT, "Logs look clean.");
            return CompletableFuture.completedFuture(null);
        });

        scheduler.registerChannel(CHANNEL_TYPE_TELEGRAM, "auto-chat-42");
        scheduler.tick();

        ArgumentCaptor<ScheduleEntry> scheduleCaptor = ArgumentCaptor.forClass(ScheduleEntry.class);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ScheduleDeliveryContext> channelInfoCaptor = ArgumentCaptor
                .forClass(ScheduleDeliveryContext.class);
        verify(reportSender).sendReport(scheduleCaptor.capture(), anyString(), textCaptor.capture(),
                channelInfoCaptor.capture());

        assertEquals("sched-report-auto", scheduleCaptor.getValue().getId());
        assertEquals("Logs look clean.", textCaptor.getValue());
        assertEquals("auto-chat-42", channelInfoCaptor.getValue().sessionChatId());
    }

    @Test
    void shouldNotSendReportWhenNoReportChannelConfigured() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(List.of(AutoTask.builder()
                        .id(TASK_ID)
                        .title("Normal task")
                        .status(AutoTask.TaskStatus.PENDING)
                        .order(1)
                        .build()))
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-no-report")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        when(sessionRunCoordinator.submit(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.getMetadata().put(ContextAttributes.AUTO_RUN_STATUS, "COMPLETED");
            message.getMetadata().put(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT, "Done.");
            return CompletableFuture.completedFuture(null);
        });

        scheduler.registerChannel(CHANNEL_TYPE_TELEGRAM, "123");
        scheduler.tick();

        ArgumentCaptor<ScheduleEntry> scheduleCaptor = ArgumentCaptor.forClass(ScheduleEntry.class);
        verify(reportSender).sendReport(scheduleCaptor.capture(), anyString(), eq("Done."), any());
        assertNull(scheduleCaptor.getValue().getReport());
    }

    @Test
    void shouldNotSendReportWhenAssistantTextIsEmpty() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(List.of(AutoTask.builder()
                        .id(TASK_ID)
                        .title("Silent task")
                        .status(AutoTask.TaskStatus.PENDING)
                        .order(1)
                        .build()))
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-report-empty")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .report(channelReport(CHANNEL_TYPE_TELEGRAM, "999"))
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.registerChannel(CHANNEL_TYPE_TELEGRAM, "123");
        scheduler.tick();

        verify(reportSender).sendReport(any(ScheduleEntry.class), anyString(), isNull(), any());
    }

    @Test
    void shouldFallbackToDefaultChannelWhenReportChannelTypeDoesNotMatchRegistered() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(List.of(AutoTask.builder()
                        .id(TASK_ID)
                        .title("Check")
                        .status(AutoTask.TaskStatus.PENDING)
                        .order(1)
                        .build()))
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-report-mismatch")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .report(channelReport("slack", null))
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        when(sessionRunCoordinator.submit(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.getMetadata().put(ContextAttributes.AUTO_RUN_STATUS, "COMPLETED");
            message.getMetadata().put(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT, "Result text.");
            return CompletableFuture.completedFuture(null);
        });

        scheduler.registerChannel(CHANNEL_TYPE_TELEGRAM, "123");
        scheduler.tick();

        ArgumentCaptor<ScheduleEntry> scheduleCaptor = ArgumentCaptor.forClass(ScheduleEntry.class);
        verify(reportSender).sendReport(scheduleCaptor.capture(), anyString(), eq("Result text."), any());
        assertEquals("slack", scheduleCaptor.getValue().getReport().getChannelType());
    }

    @Test
    void shouldPassOriginalChannelSnapshotToReportSenderEvenIfGlobalChannelChangesMidRun() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(List.of(AutoTask.builder()
                        .id(TASK_ID)
                        .title("Check logs")
                        .status(AutoTask.TaskStatus.PENDING)
                        .order(1)
                        .build()))
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getGoal(GOAL_ID)).thenReturn(Optional.of(goal));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-report-snapshot")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId(GOAL_ID)
                .cronExpression(TEST_CRON)
                .enabled(true)
                .report(channelReport(CHANNEL_TYPE_TELEGRAM, null))
                .build();
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        when(sessionRunCoordinator.submit(any(Message.class))).thenAnswer(invocation -> {
            scheduler.registerChannel(CHANNEL_TYPE_TELEGRAM, "new-session", "new-transport");
            Message message = invocation.getArgument(0);
            message.getMetadata().put(ContextAttributes.AUTO_RUN_STATUS, "COMPLETED");
            message.getMetadata().put(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT, "Snapshot text.");
            return CompletableFuture.completedFuture(null);
        });

        scheduler.registerChannel(CHANNEL_TYPE_TELEGRAM, "original-session", "original-transport");
        scheduler.tick();

        ArgumentCaptor<ScheduleDeliveryContext> channelInfoCaptor = ArgumentCaptor
                .forClass(ScheduleDeliveryContext.class);
        verify(reportSender).sendReport(eq(schedule), anyString(), eq("Snapshot text."), channelInfoCaptor.capture());
        assertEquals("original-session", channelInfoCaptor.getValue().sessionChatId());
        assertEquals("original-transport", channelInfoCaptor.getValue().transportChatId());
    }
}
