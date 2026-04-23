package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.adapter.outbound.storage.JsonScheduledTaskPersistenceAdapter;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.ChannelTypes;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.port.outbound.SchedulePersistencePort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AutoModeMigrationServiceTest {

    private StoragePort storagePort;
    private ObjectMapper objectMapper;
    private SessionPort sessionPort;
    private SessionScopedGoalService sessionScopedGoalService;
    private SchedulePersistencePort schedulePersistencePort;
    private PersistentScheduledTaskService persistentScheduledTaskService;
    private AutoModeMigrationService migrationService;
    private String legacyGoalsJson;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        legacyGoalsJson = objectMapper.writeValueAsString(List.of(Goal.builder()
                .id("goal-1")
                .title("Legacy goal")
                .build()));

        storagePort = mock(StoragePort.class);
        when(storagePort.exists(eq("auto"), eq("goals-session-migration.marker")))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(storagePort.getText(eq("auto"), anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(1);
            if ("goals.json".equals(path)) {
                return CompletableFuture.completedFuture(legacyGoalsJson);
            }
            return CompletableFuture.completedFuture(null);
        });
        when(storagePort.putTextAtomic(eq("auto"), eq("scheduled-tasks.json"), anyString(), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putText(eq("auto"), eq("goals-session-migration.marker"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        schedulePersistencePort = mock(SchedulePersistencePort.class);
        when(schedulePersistencePort.loadSchedules()).thenAnswer(ignored -> List.of(legacyGoalSchedule()));

        sessionPort = mock(SessionPort.class);
        when(sessionPort.listAll()).thenReturn(List.of());
        sessionScopedGoalService = mock(SessionScopedGoalService.class);
        persistentScheduledTaskService = new PersistentScheduledTaskService(
                new JsonScheduledTaskPersistenceAdapter(storagePort, objectMapper));
        migrationService = new AutoModeMigrationService(
                storagePort,
                objectMapper,
                sessionPort,
                sessionScopedGoalService,
                persistentScheduledTaskService,
                schedulePersistencePort);
    }

    @Test
    void migrationRetryAfterPartialFailureShouldNotDuplicateScheduledTasks() {
        doThrow(new RuntimeException("partial failure"))
                .doNothing()
                .when(schedulePersistencePort).saveSchedules(any());

        migrationService.migrateIfNeeded();
        migrationService.migrateIfNeeded();

        assertEquals(1, persistentScheduledTaskService.getScheduledTasks().size());
        assertEquals("goal-1", persistentScheduledTaskService.getScheduledTasks().getFirst().getLegacySourceId());
    }

    @Test
    void shouldNotWriteMigrationMarkerUntilLegacyGoalsCanBeAttachedToASession() {
        migrationService.migrateIfNeeded();

        verify(sessionScopedGoalService, never()).replaceGoals(anyString(), any());
        verify(storagePort, never()).putText(eq("auto"), eq("goals-session-migration.marker"), anyString());

        when(sessionPort.listAll()).thenReturn(List.of(
                session("web-1", ChannelTypes.WEB, Instant.parse("2026-02-01T00:00:00Z"))));

        migrationService.migrateIfNeeded();

        verify(sessionScopedGoalService).replaceGoals(eq("web-1"), any());
        verify(storagePort).putText(eq("auto"), eq("goals-session-migration.marker"), anyString());
    }

    @Test
    void shouldMergeLegacyGoalsIntoExistingSessionGoalsInsteadOfReplacingThem() {
        Goal existingGoal = Goal.builder()
                .id("existing-goal")
                .sessionId("web-1")
                .title("Existing session goal")
                .build();
        when(sessionPort.listAll()).thenReturn(List.of(
                session("web-1", ChannelTypes.WEB, Instant.parse("2026-02-01T00:00:00Z"))));
        when(sessionScopedGoalService.getGoals("web-1")).thenReturn(new ArrayList<>(List.of(existingGoal)));

        migrationService.migrateIfNeeded();

        ArgumentCaptor<List<Goal>> goalsCaptor = ArgumentCaptor.captor();
        verify(sessionScopedGoalService).replaceGoals(eq("web-1"), goalsCaptor.capture());
        List<Goal> mergedGoals = goalsCaptor.getValue();
        assertEquals(List.of("existing-goal", "goal-1"), mergedGoals.stream().map(Goal::getId).toList());
        assertEquals("web-1", mergedGoals.get(1).getSessionId());
    }

    @Test
    void shouldSkipMigrationWhenMarkerExists() {
        when(storagePort.exists(eq("auto"), eq("goals-session-migration.marker")))
                .thenReturn(CompletableFuture.completedFuture(true));

        migrationService.migrateIfNeeded();

        verify(storagePort, never()).getText(eq("auto"), eq("goals.json"));
        verify(schedulePersistencePort, never()).loadSchedules();
    }

    @Test
    void shouldMigrateLegacyGoalsAndSchedulesToLatestTelegramOrWebSession() throws Exception {
        AutoTask legacyTask = AutoTask.builder()
                .id("task-1")
                .title("Legacy task")
                .description("Task description")
                .prompt("Task prompt")
                .build();
        Goal legacyGoal = Goal.builder()
                .id("goal-1")
                .title("Legacy goal")
                .tasks(new ArrayList<>(List.of(legacyTask)))
                .build();
        Goal inboxGoal = Goal.builder()
                .id("inbox")
                .title("Inbox")
                .tasks(new ArrayList<>())
                .build();
        legacyGoalsJson = objectMapper.writeValueAsString(List.of(legacyGoal, inboxGoal));

        ScheduleEntry goalSchedule = legacyGoalSchedule();
        ScheduleEntry taskSchedule = ScheduleEntry.builder()
                .id("sched-task-1")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId("task-1")
                .cronExpression("0 0 10 * * *")
                .enabled(true)
                .build();
        ScheduleEntry existingScheduledTaskSchedule = ScheduleEntry.builder()
                .id("sched-existing")
                .type(ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .targetId("scheduled-task-existing")
                .enabled(true)
                .build();
        ScheduleEntry missingGoalSchedule = ScheduleEntry.builder()
                .id("sched-missing")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId("missing")
                .enabled(true)
                .build();
        ScheduleEntry missingTaskSchedule = ScheduleEntry.builder()
                .id("sched-missing-task")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId("missing-task")
                .enabled(true)
                .build();
        ScheduleEntry nullTypeSchedule = ScheduleEntry.builder()
                .id("sched-null-type")
                .targetId("goal-1")
                .enabled(true)
                .build();
        List<ScheduleEntry> schedules = new ArrayList<>();
        schedules.add(null);
        schedules.add(goalSchedule);
        schedules.add(taskSchedule);
        schedules.add(existingScheduledTaskSchedule);
        schedules.add(missingGoalSchedule);
        schedules.add(missingTaskSchedule);
        schedules.add(nullTypeSchedule);
        when(schedulePersistencePort.loadSchedules()).thenReturn(schedules);
        when(sessionPort.listAll()).thenReturn(List.of(
                session("telegram-old", ChannelTypes.TELEGRAM, Instant.parse("2026-01-01T00:00:00Z")),
                session("telegram-unknown", ChannelTypes.TELEGRAM, null),
                session("web-new", ChannelTypes.WEB, Instant.parse("2026-02-01T00:00:00Z")),
                session("slack-latest", "slack", Instant.parse("2026-03-01T00:00:00Z"))));

        migrationService.migrateIfNeeded();

        ArgumentCaptor<List<Goal>> goalsCaptor = ArgumentCaptor.captor();
        verify(sessionScopedGoalService).replaceGoals(eq("web-new"), goalsCaptor.capture());
        assertEquals("web-new", goalsCaptor.getValue().getFirst().getSessionId());
        assertTrue(goalsCaptor.getValue().stream()
                .filter(goal -> "inbox".equals(goal.getId()))
                .findFirst()
                .orElseThrow()
                .isSystemInbox());
        assertEquals(ScheduleEntry.ScheduleType.SCHEDULED_TASK, goalSchedule.getType());
        assertEquals(ScheduleEntry.ScheduleType.SCHEDULED_TASK, taskSchedule.getType());
        assertFalse("goal-1".equals(goalSchedule.getTargetId()));
        assertFalse("task-1".equals(taskSchedule.getTargetId()));
        assertEquals("scheduled-task-existing", existingScheduledTaskSchedule.getTargetId());
        assertEquals(ScheduleEntry.ScheduleType.GOAL, missingGoalSchedule.getType());
        assertFalse(missingGoalSchedule.isEnabled());
        assertNull(missingGoalSchedule.getNextExecutionAt());
        assertEquals(ScheduleEntry.ScheduleType.TASK, missingTaskSchedule.getType());
        assertFalse(missingTaskSchedule.isEnabled());
        assertNull(missingTaskSchedule.getNextExecutionAt());
        assertEquals(2, persistentScheduledTaskService.getScheduledTasks().size());
        verify(schedulePersistencePort).saveSchedules(schedules);
        verify(storagePort).putText(eq("auto"), eq("goals-session-migration.marker"), anyString());
    }

    private static ScheduleEntry legacyGoalSchedule() {
        return ScheduleEntry.builder()
                .id("sched-goal-1")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId("goal-1")
                .cronExpression("0 0 9 * * *")
                .enabled(true)
                .build();
    }

    private static AgentSession session(String id, String channelType, Instant lastActivity) {
        return AgentSession.builder()
                .id(id)
                .channelType(channelType)
                .createdAt(lastActivity)
                .updatedAt(lastActivity)
                .build();
    }
}
