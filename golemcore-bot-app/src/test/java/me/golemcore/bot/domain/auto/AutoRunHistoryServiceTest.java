package me.golemcore.bot.domain.auto;

import me.golemcore.bot.domain.scheduling.ScheduleService;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoRunHistoryServiceTest {

    private SessionPort sessionPort;
    private ScheduleService scheduleService;
    private AutoModeService autoModeService;
    private AutoRunHistoryService service;

    @BeforeEach
    void setUp() {
        sessionPort = mock(SessionPort.class);
        scheduleService = mock(ScheduleService.class);
        autoModeService = mock(AutoModeService.class);
        service = new AutoRunHistoryService(sessionPort, scheduleService, autoModeService);
    }

    @Test
    void shouldGroupMessagesByAutoRunId() {
        AutoTask task = AutoTask.builder()
                .id("task-1")
                .goalId("goal-1")
                .title("Write docs")
                .order(1)
                .build();
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Launch")
                .tasks(List.of(task))
                .build();
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId("task-1")
                .cronExpression("* * * * *")
                .build();

        Message user = Message.builder()
                .id("m1")
                .role("user")
                .content("start")
                .timestamp(Instant.parse("2026-03-11T10:00:00Z"))
                .metadata(Map.of(
                        ContextAttributes.AUTO_MODE, true,
                        ContextAttributes.AUTO_RUN_ID, "run-1",
                        ContextAttributes.AUTO_SCHEDULE_ID, "sched-1",
                        ContextAttributes.AUTO_GOAL_ID, "goal-1",
                        ContextAttributes.AUTO_TASK_ID, "task-1"))
                .build();
        Message assistant = Message.builder()
                .id("m2")
                .role("assistant")
                .content("done")
                .timestamp(Instant.parse("2026-03-11T10:00:03Z"))
                .metadata(Map.of(
                        ContextAttributes.AUTO_MODE, true,
                        ContextAttributes.AUTO_RUN_ID, "run-1",
                        ContextAttributes.AUTO_SCHEDULE_ID, "sched-1",
                        ContextAttributes.AUTO_GOAL_ID, "goal-1",
                        ContextAttributes.AUTO_TASK_ID, "task-1",
                        ContextAttributes.AUTO_RUN_STATUS, "COMPLETED",
                        ContextAttributes.AUTO_RUN_ACTIVE_SKILL, "reviewer-skill",
                        "modelTier", "coding"))
                .build();
        AgentSession session = AgentSession.builder()
                .id("web:conv-1")
                .channelType("web")
                .chatId("conv-1")
                .metadata(Map.of(
                        ContextAttributes.CONVERSATION_KEY, "conv-1",
                        ContextAttributes.TRANSPORT_CHAT_ID, "client-1"))
                .messages(List.of(user, assistant))
                .build();

        when(autoModeService.getGoals()).thenReturn(List.of(goal));
        when(scheduleService.getSchedules()).thenReturn(List.of(schedule));
        when(sessionPort.listAll()).thenReturn(List.of(session));

        List<AutoRunHistoryService.RunSummary> runs = service.listRuns("sched-1", null, null, 10);

        assertEquals(1, runs.size());
        AutoRunHistoryService.RunSummary run = runs.get(0);
        assertEquals("run-1", run.runId());
        assertEquals("sched-1", run.scheduleId());
        assertEquals("task-1", run.taskId());
        assertEquals("Write docs", run.taskLabel());
        assertEquals("COMPLETED", run.status());
        assertEquals(2, run.messageCount());

        Optional<AutoRunHistoryService.RunDetail> detail = service.getRun("run-1");
        assertTrue(detail.isPresent());
        assertEquals(2, detail.get().messages().size());
        assertNull(detail.get().messages().get(0).modelTier());
        assertNull(detail.get().messages().get(0).skill());
        assertEquals("coding", detail.get().messages().get(1).modelTier());
        assertEquals("reviewer-skill", detail.get().messages().get(1).skill());
    }

    @Test
    void shouldPreferExplicitFailedStatusFromMetadata() {
        AutoTask task = AutoTask.builder()
                .id("task-1")
                .goalId("goal-1")
                .title("Write docs")
                .order(1)
                .build();
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Launch")
                .tasks(List.of(task))
                .build();
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId("task-1")
                .cronExpression("* * * * *")
                .build();

        Message user = Message.builder()
                .id("m1")
                .role("user")
                .content("start")
                .timestamp(Instant.parse("2026-03-11T10:00:00Z"))
                .metadata(Map.of(
                        ContextAttributes.AUTO_MODE, true,
                        ContextAttributes.AUTO_RUN_ID, "run-1",
                        ContextAttributes.AUTO_SCHEDULE_ID, "sched-1",
                        ContextAttributes.AUTO_GOAL_ID, "goal-1",
                        ContextAttributes.AUTO_TASK_ID, "task-1",
                        ContextAttributes.AUTO_RUN_STATUS, "FAILED"))
                .build();
        AgentSession session = AgentSession.builder()
                .id("web:conv-1")
                .channelType("web")
                .chatId("conv-1")
                .metadata(Map.of(
                        ContextAttributes.CONVERSATION_KEY, "conv-1",
                        ContextAttributes.TRANSPORT_CHAT_ID, "client-1"))
                .messages(List.of(user))
                .build();

        when(autoModeService.getGoals()).thenReturn(List.of(goal));
        when(scheduleService.getSchedules()).thenReturn(List.of(schedule));
        when(sessionPort.listAll()).thenReturn(List.of(session));

        List<AutoRunHistoryService.RunSummary> runs = service.listRuns("sched-1", null, null, 10);

        assertEquals(1, runs.size());
        assertEquals("FAILED", runs.get(0).status());
    }

    @Test
    void shouldExposeScheduledTaskIdentityFromRunMetadata() {
        ScheduledTask scheduledTask = ScheduledTask.builder()
                .id("scheduled-task-1")
                .title("Daily summary")
                .build();
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-scheduled-task")
                .type(ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .targetId("scheduled-task-1")
                .cronExpression("* * * * *")
                .build();

        Message user = Message.builder()
                .id("m1")
                .role("user")
                .content("start")
                .timestamp(Instant.parse("2026-03-11T10:00:00Z"))
                .metadata(Map.of(
                        ContextAttributes.AUTO_MODE, true,
                        ContextAttributes.AUTO_RUN_ID, "run-scheduled-task",
                        ContextAttributes.AUTO_SCHEDULE_ID, "sched-scheduled-task",
                        ContextAttributes.AUTO_SCHEDULED_TASK_ID, "scheduled-task-1"))
                .build();
        AgentSession session = AgentSession.builder()
                .id("web:conv-scheduled-task")
                .channelType("web")
                .chatId("conv-scheduled-task")
                .messages(List.of(user))
                .build();

        when(autoModeService.getGoals()).thenReturn(List.of());
        when(autoModeService.getScheduledTasks()).thenReturn(List.of(scheduledTask));
        when(scheduleService.getSchedules()).thenReturn(List.of(schedule));
        when(sessionPort.listAll()).thenReturn(List.of(session));

        AutoRunHistoryService.RunSummary summary = service
                .listRuns("sched-scheduled-task", null, null, 10)
                .getFirst();

        assertEquals("scheduled-task-1", summary.scheduledTaskId());
        assertEquals("Daily summary", summary.scheduledTaskLabel());
        assertEquals("SCHEDULED_TASK", summary.scheduleTargetType());
        assertEquals("Daily summary", summary.scheduleTargetLabel());

        AutoRunHistoryService.RunDetail detail = service.getRun("run-scheduled-task").orElseThrow();
        assertEquals("scheduled-task-1", detail.scheduledTaskId());
        assertEquals("Daily summary", detail.scheduledTaskLabel());
    }

    @Test
    void shouldExposeToolMetadataAndToolOutputStatusWhenAssistantIsMissing() {
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Launch")
                .tasks(List.of())
                .build();
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-goal")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId("goal-1")
                .cronExpression("* * * * *")
                .build();

        Message user = Message.builder()
                .id("m-user")
                .role("user")
                .content("start")
                .timestamp(Instant.parse("2026-03-11T10:00:00Z"))
                .metadata(Map.of(
                        ContextAttributes.AUTO_MODE, true,
                        ContextAttributes.AUTO_RUN_ID, "run-tool",
                        ContextAttributes.AUTO_SCHEDULE_ID, "sched-goal",
                        ContextAttributes.AUTO_GOAL_ID, "goal-1",
                        ContextAttributes.AUTO_RUN_ACTIVE_SKILL, "user-skill",
                        "model", "user-model",
                        "modelTier", "balanced"))
                .build();
        Message tool = Message.builder()
                .id("m-tool")
                .role("tool")
                .content("tool output")
                .timestamp(Instant.parse("2026-03-11T10:00:02Z"))
                .metadata(Map.of(
                        ContextAttributes.AUTO_MODE, true,
                        ContextAttributes.AUTO_RUN_ID, "run-tool",
                        ContextAttributes.AUTO_SCHEDULE_ID, "sched-goal",
                        ContextAttributes.AUTO_GOAL_ID, "goal-1",
                        ContextAttributes.AUTO_RUN_ACTIVE_SKILL, "tool-skill",
                        "model", "tool-model",
                        "modelTier", "smart"))
                .build();
        AgentSession session = AgentSession.builder()
                .id("web:conv-tool")
                .channelType("web")
                .chatId("conv-tool")
                .metadata(Map.of(
                        ContextAttributes.CONVERSATION_KEY, "conv-tool",
                        ContextAttributes.TRANSPORT_CHAT_ID, "client-tool"))
                .messages(List.of(user, tool))
                .build();

        when(autoModeService.getGoals()).thenReturn(List.of(goal));
        when(scheduleService.getSchedules()).thenReturn(List.of(schedule));
        when(sessionPort.listAll()).thenReturn(List.of(session));

        List<AutoRunHistoryService.RunSummary> runs = service.listRuns("sched-goal", null, null, 10);

        assertEquals(1, runs.size());
        AutoRunHistoryService.RunSummary run = runs.get(0);
        assertEquals("TOOL_OUTPUT", run.status());
        assertEquals("GOAL", run.scheduleTargetType());
        assertEquals("Launch", run.scheduleTargetLabel());

        AutoRunHistoryService.RunDetail detail = service.getRun("run-tool").orElseThrow();
        assertEquals(2, detail.messages().size());
        assertNull(detail.messages().get(0).model());
        assertNull(detail.messages().get(0).modelTier());
        assertNull(detail.messages().get(0).skill());
        assertEquals("tool-model", detail.messages().get(1).model());
        assertEquals("smart", detail.messages().get(1).modelTier());
        assertEquals("tool-skill", detail.messages().get(1).skill());
    }

}
