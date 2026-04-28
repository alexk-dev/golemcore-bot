package me.golemcore.bot.adapter.inbound.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.golemcore.bot.application.command.AutomationCommandService;
import me.golemcore.bot.domain.model.AutoModeChannelRegisteredEvent;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedActionStatus;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.command.CommandOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class AutomationCommandHandlerTest {

    private static final String GET_MESSAGE_METHOD = "getMessage";

    private AutomationCommandService automationCommandService;
    private UserPreferencesService preferencesService;
    private ApplicationEventPublisher eventPublisher;
    private AutomationCommandHandler handler;

    @BeforeEach
    void setUp() {
        automationCommandService = mock(AutomationCommandService.class);
        preferencesService = mock(UserPreferencesService.class, invocation -> {
            if (GET_MESSAGE_METHOD.equals(invocation.getMethod().getName())) {
                Object[] allArgs = invocation.getArguments();
                String key = (String) allArgs[0];
                if (allArgs.length > 1) {
                    StringBuilder builder = new StringBuilder(key);
                    Object vararg = allArgs[1];
                    if (vararg instanceof Object[] arr) {
                        for (Object arg : arr) {
                            builder.append(" ").append(arg);
                        }
                    } else {
                        for (int index = 1; index < allArgs.length; index++) {
                            builder.append(" ").append(allArgs[index]);
                        }
                    }
                    return builder.toString();
                }
                return key;
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        eventPublisher = mock(ApplicationEventPublisher.class);
        handler = new AutomationCommandHandler(automationCommandService, preferencesService, eventPublisher);
    }

    @Test
    void shouldEnableAutoModeAndPublishRegistrationEvent() {
        when(automationCommandService.isAutoFeatureEnabled()).thenReturn(true);

        CommandOutcome result = handler.handleAuto(List.of("on"), "telegram", "12345", "transport-12345");

        assertTrue(result.success());
        assertEquals("command.auto.enabled", result.fallbackText());
        verify(automationCommandService).enableAutoMode();
        verify(eventPublisher).publishEvent(any(AutoModeChannelRegisteredEvent.class));
    }

    @Test
    void shouldRenderAutoStatusAndUsage() {
        when(automationCommandService.isAutoFeatureEnabled()).thenReturn(true);
        when(automationCommandService.getAutoStatus()).thenReturn(new AutomationCommandService.AutoStatus(true));

        CommandOutcome status = handler.handleAuto(List.of(), "telegram", "12345", "12345");
        CommandOutcome usage = handler.handleAuto(List.of("weird"), "telegram", "12345", "12345");

        assertEquals("command.auto.status ON", status.fallbackText());
        assertEquals("command.auto.usage", usage.fallbackText());
    }

    @Test
    void shouldRenderGoalsTasksAndDiaryOverviews() {
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Ship PR")
                .description("Finalize hex cleanup")
                .tasks(List.of(
                        AutoTask.builder().id("task-2").title("Review").order(2).status(AutoTask.TaskStatus.COMPLETED)
                                .build(),
                        AutoTask.builder().id("task-1").title("Implement").order(1)
                                .status(AutoTask.TaskStatus.IN_PROGRESS).build()))
                .build();
        DiaryEntry entry = DiaryEntry.builder()
                .timestamp(Instant.parse("2026-04-10T12:00:00Z"))
                .type(DiaryEntry.DiaryType.PROGRESS)
                .content("Tests passed")
                .build();
        when(automationCommandService.getGoals()).thenReturn(new AutomationCommandService.GoalsOverview(List.of(goal)));
        when(automationCommandService.getTasks()).thenReturn(new AutomationCommandService.TasksOverview(List.of(goal)));
        when(automationCommandService.getDiary(50))
                .thenReturn(new AutomationCommandService.DiaryOverview(List.of(entry)));

        CommandOutcome goals = handler.handleGoals();
        CommandOutcome tasks = handler.handleTasks();
        CommandOutcome diary = handler.handleDiary(List.of("999"));

        assertTrue(goals.fallbackText().contains("Ship PR"));
        assertTrue(goals.fallbackText().contains("Finalize hex cleanup"));
        assertTrue(tasks.fallbackText().contains("task-1"));
        assertTrue(tasks.fallbackText().contains("task-2"));
        assertTrue(diary.fallbackText().contains("Tests passed"));
        verify(automationCommandService).getDiary(50);
    }

    @Test
    void shouldRenderSessionScopedGoalsTasksDiaryAndGoalCreationOutcomes() {
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Session PR")
                .description("Session scoped goal")
                .status(Goal.GoalStatus.PAUSED)
                .tasks(List.of(
                        AutoTask.builder().id("task-1").title("Plan").order(1).status(AutoTask.TaskStatus.PENDING)
                                .build(),
                        AutoTask.builder().id("task-2").title("Fix").order(2).status(AutoTask.TaskStatus.FAILED)
                                .build(),
                        AutoTask.builder().id("task-3").title("Skip").order(3).status(AutoTask.TaskStatus.SKIPPED)
                                .build()))
                .build();
        DiaryEntry entry = DiaryEntry.builder()
                .timestamp(Instant.parse("2026-04-10T12:00:00Z"))
                .type(DiaryEntry.DiaryType.DECISION)
                .content("Session note")
                .build();
        when(automationCommandService.getGoals("session-1"))
                .thenReturn(new AutomationCommandService.GoalsOverview(List.of(goal)));
        when(automationCommandService.getTasks("session-1"))
                .thenReturn(new AutomationCommandService.TasksOverview(List.of(goal)));
        when(automationCommandService.getDiary("session-1", 10))
                .thenReturn(new AutomationCommandService.DiaryOverview(List.of(entry)));
        when(automationCommandService.createGoal("session-1", "New goal"))
                .thenReturn(new AutomationCommandService.GoalCreated(Goal.builder().title("New goal").build()));

        CommandOutcome goals = handler.handleGoals("session-1");
        CommandOutcome tasks = handler.handleTasks("session-1");
        CommandOutcome diary = handler.handleDiary(List.of("bad-count"), "session-1");
        CommandOutcome created = handler.handleGoal(List.of("New", "goal"), "session-1");

        assertTrue(goals.fallbackText().contains("Session PR"));
        assertTrue(goals.fallbackText().contains("PAUSED"));
        assertTrue(tasks.fallbackText().contains("[ ] Plan"));
        assertTrue(tasks.fallbackText().contains("[!] Fix"));
        assertTrue(tasks.fallbackText().contains("[-] Skip"));
        assertTrue(diary.fallbackText().contains("Session note"));
        assertEquals("command.goal.created New goal", created.fallbackText());
    }

    @Test
    void shouldRenderScheduleListAndDeleteOutcomes() {
        ScheduleEntry entry = ScheduleEntry.builder()
                .id("sched-1")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId("goal-1")
                .cronExpression("0 * * * *")
                .enabled(true)
                .executionCount(2)
                .maxExecutions(5)
                .nextExecutionAt(Instant.parse("2026-04-11T15:30:00Z"))
                .build();
        when(automationCommandService.isAutoFeatureEnabled()).thenReturn(true);
        when(automationCommandService.listSchedules())
                .thenReturn(new AutomationCommandService.SchedulesOverview(List.of(entry)));
        when(automationCommandService.deleteSchedule("sched-1"))
                .thenReturn(new AutomationCommandService.ScheduleDeleted("sched-1"));

        CommandOutcome listResult = handler.handleSchedule(List.of());
        CommandOutcome deleteResult = handler.handleSchedule(List.of("delete", "sched-1"));

        assertTrue(listResult.fallbackText().contains("sched-1"));
        assertTrue(listResult.fallbackText().contains("0 * * * *"));
        assertTrue(deleteResult.success());
        assertEquals("command.schedule.deleted sched-1", deleteResult.fallbackText());
    }

    @Test
    void shouldRenderLaterOverviewAndCancelFailure() {
        DelayedSessionAction action = DelayedSessionAction.builder()
                .id("later-1")
                .kind(DelayedActionKind.REMIND_LATER)
                .status(DelayedActionStatus.SCHEDULED)
                .runAt(Instant.parse("2026-04-10T18:00:00Z"))
                .cancelOnUserActivity(true)
                .payload(Map.of("message", "Ping me"))
                .build();
        when(automationCommandService.isLaterFeatureEnabled()).thenReturn(true);
        when(automationCommandService.listLaterActions("telegram", "chat-1"))
                .thenReturn(new AutomationCommandService.LaterActionsOverview(List.of(action)));
        when(automationCommandService.cancelLaterAction("missing", "telegram", "chat-1"))
                .thenReturn(new AutomationCommandService.LaterNotFound("missing"));
        when(preferencesService.getPreferences()).thenReturn(UserPreferences.builder()
                .timezone("Europe/Moscow")
                .build());

        CommandOutcome listResult = handler.handleLater(List.of("list"), "telegram", "chat-1");
        CommandOutcome cancelResult = handler.handleLater(List.of("cancel", "missing"), "telegram",
                "chat-1");

        assertTrue(listResult.fallbackText().contains("later-1"));
        assertTrue(listResult.fallbackText().contains("Ping me"));
        assertTrue(listResult.fallbackText().contains("2026-04-10 21:00"));
        assertFalse(cancelResult.success());
        assertEquals("command.later.not-found missing", cancelResult.fallbackText());
    }

    @Test
    void shouldExposePassThroughStateHelpers() {
        Goal goal = Goal.builder().id("goal-1").title("Ship PR").build();
        AutoTask task = AutoTask.builder().id("task-1").title("Implement").build();
        when(automationCommandService.isAutoFeatureEnabled()).thenReturn(true);
        when(automationCommandService.isAutoModeEnabled()).thenReturn(true);
        when(automationCommandService.getActiveGoals()).thenReturn(List.of(goal));
        when(automationCommandService.getNextPendingTask()).thenReturn(Optional.of(task));
        when(automationCommandService.isLaterFeatureEnabled()).thenReturn(true);

        assertTrue(handler.isAutoFeatureEnabled());
        assertTrue(handler.isAutoModeEnabled());
        assertEquals(List.of(goal), handler.getActiveGoals());
        assertEquals(Optional.of(task), handler.getNextPendingTask());
        assertTrue(handler.isLaterFeatureEnabled());
    }
}
