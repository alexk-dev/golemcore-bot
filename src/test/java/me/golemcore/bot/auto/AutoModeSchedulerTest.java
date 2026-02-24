package me.golemcore.bot.auto;

import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AutoModeChannelRegisteredEvent;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.plugin.context.PluginChannelCatalog;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.tools.GoalManagementTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private AgentLoop agentLoop;
    private GoalManagementTool goalManagementTool;
    private ChannelPort channelPort;
    private RuntimeConfigService runtimeConfigService;
    private AutoModeScheduler scheduler;

    @BeforeEach
    void setUp() {
        autoModeService = mock(AutoModeService.class);
        scheduleService = mock(ScheduleService.class);
        agentLoop = mock(AgentLoop.class);
        goalManagementTool = mock(GoalManagementTool.class);
        channelPort = mock(ChannelPort.class);

        when(channelPort.getChannelType()).thenReturn(CHANNEL_TYPE_TELEGRAM);
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(scheduleService.getDueSchedules()).thenReturn(List.of());

        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isAutoModeEnabled()).thenReturn(true);
        when(runtimeConfigService.getAutoTaskTimeLimitMinutes()).thenReturn(10);
        when(runtimeConfigService.isAutoNotifyMilestonesEnabled()).thenReturn(true);
        when(runtimeConfigService.isAutoStartEnabled()).thenReturn(false);

        scheduler = new AutoModeScheduler(
                autoModeService, scheduleService, agentLoop, runtimeConfigService,
                goalManagementTool, PluginChannelCatalog.forTesting(List.of(channelPort)));
    }

    @Test
    void tickSkipsWhenAutoModeDisabled() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(false);

        scheduler.tick();

        verify(agentLoop, never()).processMessage(any(Message.class));
        verify(scheduleService, never()).getDueSchedules();
    }

    @Test
    void tickSkipsWhenNoDueSchedules() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        when(scheduleService.getDueSchedules()).thenReturn(List.of());

        scheduler.tick();

        verify(agentLoop, never()).processMessage(any(Message.class));
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
        verify(agentLoop).processMessage(captor.capture());

        Message sent = captor.getValue();
        assertTrue(sent.getContent().contains("Write unit tests"));
        assertEquals("user", sent.getRole());
        assertEquals(true, sent.getMetadata().get("auto.mode"));
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
        verify(agentLoop).processMessage(captor.capture());

        Message sent = captor.getValue();
        assertTrue(sent.getContent().contains("Plan tasks for goal"));
        assertTrue(sent.getContent().contains(GOAL_TITLE));
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
        verify(agentLoop).processMessage(captor.capture());

        Message sent = captor.getValue();
        assertTrue(sent.getContent().contains("Implement feature X"));
        verify(scheduleService).recordExecution("sched-task-xyz");
    }

    @Test
    void shouldSkipTickWhenExecutionInProgress() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        // Simulate a long-running schedule that blocks
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

        // First tick returns a due schedule, second tick should find executing=true
        // We can't truly test concurrency in a unit test, but we can test the flag
        // mechanism
        when(scheduleService.getDueSchedules()).thenReturn(List.of(schedule));

        scheduler.tick();

        // After tick completes, executing should be false again
        // Next tick should work normally
        scheduler.tick();

        // Both ticks should have processed (no concurrent blocking in sequential test)
        verify(agentLoop, org.mockito.Mockito.times(2)).processMessage(any(Message.class));
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

        verify(agentLoop, never()).processMessage(any(Message.class));
        verify(scheduleService).recordExecution("sched-goal-missing");
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

        verify(agentLoop, never()).processMessage(any(Message.class));
    }

    @Test
    void shouldSkipTaskScheduleWhenTaskCompleted() {
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

        verify(agentLoop, never()).processMessage(any(Message.class));
    }

    // ===== Channel registration =====

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

    // ===== Event listener =====

    @Test
    void onChannelRegisteredDelegatesToRegisterChannel() {
        AutoModeChannelRegisteredEvent event = new AutoModeChannelRegisteredEvent(CHANNEL_TYPE_TELEGRAM,
                "chat-event-123");

        scheduler.onChannelRegistered(event);

        scheduler.sendMilestoneNotification("Event test");
        verify(channelPort).sendMessage(eq("chat-event-123"), contains("Event test"));
    }

    // ===== Notification failure handling =====

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

    // ===== Tick with channel info =====

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
        verify(agentLoop).processMessage(captor.capture());

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
        verify(agentLoop).processMessage(captor.capture());

        Message sent = captor.getValue();
        assertEquals("auto", sent.getChatId());
        assertEquals("auto", sent.getChannelType());
    }

    // ===== Shutdown =====

    @Test
    void shutdownDoesNotThrowWhenNotInitialized() {
        AutoModeScheduler freshScheduler = new AutoModeScheduler(
                autoModeService, scheduleService, agentLoop, runtimeConfigService,
                goalManagementTool, PluginChannelCatalog.forTesting(List.of(channelPort)));

        assertDoesNotThrow(freshScheduler::shutdown);
    }

    // ===== Auto-start =====

    @Test
    void shouldAutoStartWhenEnabledInConfig() {
        when(runtimeConfigService.isAutoStartEnabled()).thenReturn(true);
        when(autoModeService.isAutoModeEnabled()).thenReturn(false);

        AutoModeScheduler newScheduler = new AutoModeScheduler(
                autoModeService, scheduleService, agentLoop, runtimeConfigService,
                goalManagementTool, PluginChannelCatalog.forTesting(List.of(channelPort)));

        newScheduler.init();

        verify(autoModeService).enableAutoMode();

        newScheduler.shutdown();
    }

    @Test
    void shouldNotAutoStartWhenAutoStartDisabled() {
        when(runtimeConfigService.isAutoStartEnabled()).thenReturn(false);

        AutoModeScheduler newScheduler = new AutoModeScheduler(
                autoModeService, scheduleService, agentLoop, runtimeConfigService,
                goalManagementTool, PluginChannelCatalog.forTesting(List.of(channelPort)));

        newScheduler.init();

        verify(autoModeService, never()).enableAutoMode();

        newScheduler.shutdown();
    }

    @Test
    void shouldNotAutoStartWhenAlreadyEnabled() {
        when(runtimeConfigService.isAutoStartEnabled()).thenReturn(true);
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        AutoModeScheduler newScheduler = new AutoModeScheduler(
                autoModeService, scheduleService, agentLoop, runtimeConfigService,
                goalManagementTool, PluginChannelCatalog.forTesting(List.of(channelPort)));

        newScheduler.init();

        verify(autoModeService, never()).enableAutoMode();

        newScheduler.shutdown();
    }

    @Test
    void shouldInitializeSchedulerEvenWhenFeatureDisabledAtStartup() {
        when(runtimeConfigService.isAutoModeEnabled()).thenReturn(false);

        AutoModeScheduler newScheduler = new AutoModeScheduler(
                autoModeService, scheduleService, agentLoop, runtimeConfigService,
                goalManagementTool, PluginChannelCatalog.forTesting(List.of(channelPort)));

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
        verify(agentLoop, never()).processMessage(any(Message.class));
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

        verify(agentLoop, org.mockito.Mockito.times(1)).processMessage(any(Message.class));
    }
}
