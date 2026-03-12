package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals("coding", detail.get().messages().get(1).modelTier());
    }
}
