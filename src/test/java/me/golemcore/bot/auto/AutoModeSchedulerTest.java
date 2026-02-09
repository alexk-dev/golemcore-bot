package me.golemcore.bot.auto;

import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AutoModeChannelRegisteredEvent;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.infrastructure.config.BotProperties;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoModeSchedulerTest {

    private static final String CHANNEL_TYPE_TELEGRAM = "telegram";
    private static final String GOAL_ID = "goal-1";
    private static final String GOAL_TITLE = "Test Goal";

    private AutoModeService autoModeService;
    private AgentLoop agentLoop;
    private GoalManagementTool goalManagementTool;
    private ChannelPort channelPort;
    private BotProperties properties;
    private AutoModeScheduler scheduler;

    @BeforeEach
    void setUp() {
        autoModeService = mock(AutoModeService.class);
        agentLoop = mock(AgentLoop.class);
        goalManagementTool = mock(GoalManagementTool.class);
        channelPort = mock(ChannelPort.class);

        when(channelPort.getChannelType()).thenReturn(CHANNEL_TYPE_TELEGRAM);
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        properties = new BotProperties();
        properties.getAuto().setEnabled(true);
        properties.getAuto().setIntervalMinutes(15);
        properties.getAuto().setNotifyMilestones(true);

        scheduler = new AutoModeScheduler(
                autoModeService, agentLoop, properties,
                goalManagementTool, List.of(channelPort));
    }

    @Test
    void tickSkipsWhenAutoModeDisabled() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(false);

        scheduler.tick();

        verify(agentLoop, never()).processMessage(any(Message.class));
    }

    @Test
    void tickSkipsWhenNoActiveGoals() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        when(autoModeService.getActiveGoals()).thenReturn(List.of());

        scheduler.tick();

        verify(agentLoop, never()).processMessage(any(Message.class));
    }

    @Test
    void tickPlansTasksWhenGoalHasNoTasks() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getActiveGoals()).thenReturn(List.of(goal));
        when(autoModeService.getNextPendingTask()).thenReturn(Optional.empty());

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(agentLoop).processMessage(captor.capture());

        Message sent = captor.getValue();
        assertTrue(sent.getContent().contains("Plan tasks for goal"));
        assertTrue(sent.getContent().contains("Test Goal"));
    }

    @Test
    void tickSendsSyntheticMessageWhenPendingTask() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getActiveGoals()).thenReturn(List.of(goal));

        AutoTask task = AutoTask.builder()
                .id("task-1")
                .goalId(GOAL_ID)
                .title("Write unit tests")
                .status(AutoTask.TaskStatus.PENDING)
                .order(1)
                .build();
        when(autoModeService.getNextPendingTask()).thenReturn(Optional.of(task));

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(agentLoop).processMessage(captor.capture());

        Message sent = captor.getValue();
        assertEquals("user", sent.getRole());
        assertTrue(sent.getContent().contains("Write unit tests"));
        assertNotNull(sent.getMetadata());
        assertEquals(true, sent.getMetadata().get("auto.mode"));
    }

    @Test
    void registerChannelStoresChannelInfo() {
        scheduler.registerChannel(CHANNEL_TYPE_TELEGRAM, "chat-123");

        // Verify by sending a milestone notification and checking it reaches the
        // channel
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
        properties.getAuto().setNotifyMilestones(false);

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

        // Verify registration worked by sending a milestone notification
        scheduler.sendMilestoneNotification("Event test");
        verify(channelPort).sendMessage(eq("chat-event-123"), contains("Event test"));
    }

    // ===== Notification failure handling =====

    @Test
    void sendMilestoneNotificationHandlesChannelNotFound() {
        // Register a channel type that doesn't exist in the registry
        scheduler.registerChannel("unknown-channel", "chat-123");

        // Should not throw
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
    void tickUsesFallbackChatIdWhenNoChannelRegistered() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        when(autoModeService.getActiveGoals()).thenReturn(List.of(goal));
        when(autoModeService.getNextPendingTask()).thenReturn(Optional.empty());

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(agentLoop).processMessage(captor.capture());

        Message sent = captor.getValue();
        assertEquals("auto", sent.getChatId());
        assertEquals("auto", sent.getChannelType());
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
        when(autoModeService.getActiveGoals()).thenReturn(List.of(goal));
        when(autoModeService.getNextPendingTask()).thenReturn(Optional.empty());

        scheduler.tick();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(agentLoop).processMessage(captor.capture());

        Message sent = captor.getValue();
        assertEquals("chat-999", sent.getChatId());
        assertEquals(CHANNEL_TYPE_TELEGRAM, sent.getChannelType());
    }

    // ===== Shutdown =====

    @Test
    void shutdownDoesNotThrowWhenNotInitialized() {
        AutoModeScheduler freshScheduler = new AutoModeScheduler(
                autoModeService, agentLoop, properties,
                goalManagementTool, List.of(channelPort));

        assertDoesNotThrow(freshScheduler::shutdown);
    }
}
