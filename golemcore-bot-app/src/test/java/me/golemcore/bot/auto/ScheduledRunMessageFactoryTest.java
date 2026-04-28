package me.golemcore.bot.auto;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.AutoRunKind;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.auto.AutoModeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledRunMessageFactoryTest {

    private AutoModeService autoModeService;
    private RuntimeConfigService runtimeConfigService;
    private SkillComponent skillComponent;
    private ScheduledRunMessageFactory factory;

    @BeforeEach
    void setUp() {
        autoModeService = mock(AutoModeService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        skillComponent = mock(SkillComponent.class);
        factory = new ScheduledRunMessageFactory(autoModeService, runtimeConfigService, skillComponent);

        when(runtimeConfigService.getAutoReflectionModelTier()).thenReturn("deep");
        when(runtimeConfigService.isAutoReflectionTierPriority()).thenReturn(false);
    }

    @Test
    void buildForScheduleShouldCreateTaskRunForActiveGoalPendingTask() {
        AutoTask task = AutoTask.builder()
                .id("task-1")
                .goalId("goal-1")
                .title("Implement retry")
                .prompt("Ship retry logic")
                .status(AutoTask.TaskStatus.PENDING)
                .order(1)
                .build();
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Stabilize scheduler")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(List.of(task))
                .createdAt(Instant.now())
                .build();
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-1")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId("goal-1")
                .build();

        when(autoModeService.getGoal("goal-1")).thenReturn(Optional.of(goal));

        Optional<ScheduledRunMessage> message = factory.buildForSchedule(schedule);

        assertTrue(message.isPresent());
        assertTrue(message.get().content().contains("Ship retry logic"));
        assertEquals(AutoRunKind.GOAL_RUN, message.get().runKind());
        assertEquals("goal-1", message.get().goalId());
        assertEquals("task-1", message.get().taskId());
        assertEquals("Stabilize scheduler", message.get().goalTitle());
        assertEquals("Implement retry", message.get().taskTitle());
        assertEquals("deep", message.get().reflectionTier());
    }

    @Test
    void buildSyntheticMessageShouldAttachScheduleMetadataAndTraceContext() {
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal-1")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId("goal-1")
                .build();
        ScheduledRunMessage scheduledRunMessage = new ScheduledRunMessage(
                "Work on task",
                AutoRunKind.GOAL_RUN,
                null,
                "goal-1",
                "task-1",
                "Goal",
                "Task",
                false,
                "deep",
                true,
                false);

        Message syntheticMessage = factory.buildSyntheticMessage(
                scheduledRunMessage,
                schedule,
                new ScheduleDeliveryContext("telegram", "session-1", "transport-1"),
                "run-1");

        assertEquals("user", syntheticMessage.getRole());
        assertEquals("Work on task", syntheticMessage.getContent());
        assertEquals("telegram", syntheticMessage.getChannelType());
        assertEquals("session-1", syntheticMessage.getChatId());
        assertEquals(true, syntheticMessage.getMetadata().get(ContextAttributes.AUTO_MODE));
        assertEquals("run-1", syntheticMessage.getMetadata().get(ContextAttributes.AUTO_RUN_ID));
        assertEquals("sched-goal-1", syntheticMessage.getMetadata().get(ContextAttributes.AUTO_SCHEDULE_ID));
        assertEquals("session-1", syntheticMessage.getMetadata().get(ContextAttributes.CONVERSATION_KEY));
        assertEquals("transport-1", syntheticMessage.getMetadata().get(ContextAttributes.TRANSPORT_CHAT_ID));
        assertEquals("goal-1", syntheticMessage.getMetadata().get(ContextAttributes.AUTO_GOAL_ID));
        assertEquals("task-1", syntheticMessage.getMetadata().get(ContextAttributes.AUTO_TASK_ID));
        assertEquals("INTERNAL", syntheticMessage.getMetadata().get("trace.root.kind"));
        assertEquals("auto.schedule.goal", syntheticMessage.getMetadata().get("trace.name"));
        assertNotNull(syntheticMessage.getMetadata().get("trace.id"));
        assertNotNull(syntheticMessage.getMetadata().get("trace.span.id"));
    }

    @Test
    void buildSyntheticMessageShouldAttachScheduledTaskMetadata() {
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-persistent-1")
                .type(ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .targetId("scheduled-task-1")
                .build();
        ScheduledRunMessage scheduledRunMessage = new ScheduledRunMessage(
                "Run recurring cleanup",
                AutoRunKind.SCHEDULED_TASK_RUN,
                "scheduled-task-1",
                null,
                null,
                null,
                "Nightly cleanup",
                false,
                "smart",
                true,
                false);

        Message syntheticMessage = factory.buildSyntheticMessage(
                scheduledRunMessage,
                schedule,
                new ScheduleDeliveryContext("telegram", "session-1", "transport-1"),
                "run-2");

        assertEquals("scheduled-task-1", syntheticMessage.getMetadata().get(ContextAttributes.AUTO_SCHEDULED_TASK_ID));
        assertFalse(syntheticMessage.getMetadata().containsKey(ContextAttributes.AUTO_GOAL_ID));
        assertFalse(syntheticMessage.getMetadata().containsKey(ContextAttributes.AUTO_TASK_ID));
    }

    @Test
    void buildReflectionMessageShouldIncludeScheduledTaskFailureContextAndResolvedTier() {
        Skill skill = Skill.builder().name("cleanup").build();
        ScheduledRunMessage source = new ScheduledRunMessage(
                "Run recurring cleanup",
                AutoRunKind.SCHEDULED_TASK_RUN,
                "scheduled-task-1",
                null,
                null,
                null,
                "Nightly cleanup",
                false,
                "smart",
                false,
                false);

        when(autoModeService.resolveScheduledTaskReflectionState("scheduled-task-1"))
                .thenReturn(new AutoModeService.TaskReflectionState(
                        null,
                        false,
                        "smart",
                        false,
                        "cleanup",
                        2,
                        true,
                        "API quota exceeded",
                        "quota-exceeded",
                        null));
        when(skillComponent.findByName("cleanup")).thenReturn(Optional.of(skill));
        when(autoModeService.resolveScheduledTaskReflectionTier(eq("scheduled-task-1"), eq(skill)))
                .thenReturn("coding");
        when(autoModeService.isScheduledTaskReflectionTierPriority("scheduled-task-1")).thenReturn(true);

        Optional<ScheduledRunMessage> reflection = factory.buildReflectionMessage(source, "sched-persistent-1");

        assertTrue(reflection.isPresent());
        assertEquals("scheduled-task-1", reflection.get().scheduledTaskId());
        assertEquals("coding", reflection.get().reflectionTier());
        assertTrue(reflection.get().reflectionTierPriority());
        assertTrue(reflection.get().content().contains("Nightly cleanup"));
        assertTrue(reflection.get().content().contains("API quota exceeded"));
        assertTrue(reflection.get().content().contains("quota-exceeded"));
    }

    @Test
    void buildReflectionMessageShouldIncludeFailureContextAndResolvedTier() {
        Skill skill = Skill.builder().name("debug").build();
        ScheduledRunMessage source = new ScheduledRunMessage(
                "Work on task",
                AutoRunKind.GOAL_RUN,
                null,
                "goal-1",
                "task-1",
                "Goal",
                "Task",
                false,
                "deep",
                false,
                false);

        when(autoModeService.resolveTaskReflectionState("goal-1", "task-1"))
                .thenReturn(new AutoModeService.TaskReflectionState(
                        null,
                        false,
                        null,
                        false,
                        "debug",
                        2,
                        true,
                        "Disk full",
                        "disk-full",
                        null));
        when(skillComponent.findByName("debug")).thenReturn(Optional.of(skill));
        when(autoModeService.resolveReflectionTier(eq("goal-1"), eq("task-1"), eq(skill))).thenReturn("ultra");
        when(autoModeService.isReflectionTierPriority("goal-1", "task-1")).thenReturn(true);

        Optional<ScheduledRunMessage> reflection = factory.buildReflectionMessage(source, "sched-goal-1");

        assertTrue(reflection.isPresent());
        assertTrue(reflection.get().reflectionActive());
        assertEquals("ultra", reflection.get().reflectionTier());
        assertTrue(reflection.get().reflectionTierPriority());
        assertTrue(reflection.get().content().contains("Disk full"));
        assertTrue(reflection.get().content().contains("disk-full"));
        assertTrue(reflection.get().content().contains("sched-goal-1"));
    }

    @Test
    void buildForScheduleShouldCreateScheduledTaskRunForPersistentScheduledTask() {
        ScheduledTask scheduledTask = ScheduledTask.builder()
                .id("scheduled-task-1")
                .title("Refresh inbox")
                .description("Check all external feeds")
                .prompt("Review external feeds and summarise actionable changes")
                .reflectionModelTier("smart")
                .reflectionTierPriority(true)
                .build();
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-persistent-1")
                .type(ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .targetId("scheduled-task-1")
                .build();

        when(autoModeService.getScheduledTask("scheduled-task-1")).thenReturn(Optional.of(scheduledTask));

        Optional<ScheduledRunMessage> message = factory.buildForSchedule(schedule);

        assertTrue(message.isPresent());
        assertEquals(AutoRunKind.SCHEDULED_TASK_RUN, message.get().runKind());
        assertEquals("scheduled-task-1", message.get().scheduledTaskId());
        assertEquals("Refresh inbox", message.get().taskTitle());
        assertEquals("smart", message.get().reflectionTier());
        assertTrue(message.get().reflectionTierPriority());
        assertTrue(message.get().content().contains("Review external feeds"));
    }

    @Test
    void buildForScheduleShouldMarkCompletedTaskForResetWithoutMutatingState() {
        AutoTask task = AutoTask.builder()
                .id("task-done")
                .goalId("goal-1")
                .title("Done task")
                .status(AutoTask.TaskStatus.COMPLETED)
                .order(1)
                .build();
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Stabilize scheduler")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(List.of(task))
                .build();
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-task-done")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId("task-done")
                .build();

        when(autoModeService.findGoalForTask("task-done")).thenReturn(Optional.of(goal));

        Optional<ScheduledRunMessage> message = factory.buildForSchedule(schedule);

        assertTrue(message.isPresent());
        assertTrue(message.get().resetTaskBeforeRun());
        verify(autoModeService, never()).updateTaskStatus("goal-1", "task-done", AutoTask.TaskStatus.PENDING, null);
    }
}
