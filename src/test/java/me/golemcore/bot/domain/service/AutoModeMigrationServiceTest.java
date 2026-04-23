package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.port.outbound.SchedulePersistencePort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutoModeMigrationServiceTest {

    private StoragePort storagePort;
    private SchedulePersistencePort schedulePersistencePort;
    private PersistentScheduledTaskService persistentScheduledTaskService;
    private AutoModeMigrationService migrationService;
    private String legacyGoalsJson;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
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
        doThrow(new RuntimeException("partial failure"))
                .doNothing()
                .when(schedulePersistencePort).saveSchedules(any());

        SessionPort sessionPort = mock(SessionPort.class);
        when(sessionPort.listAll()).thenReturn(List.of());
        SessionScopedGoalService sessionScopedGoalService = mock(SessionScopedGoalService.class);
        persistentScheduledTaskService = new PersistentScheduledTaskService(storagePort, objectMapper);
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
        migrationService.migrateIfNeeded();
        migrationService.migrateIfNeeded();

        assertEquals(1, persistentScheduledTaskService.getScheduledTasks().size());
        assertEquals("goal-1", persistentScheduledTaskService.getScheduledTasks().getFirst().getLegacySourceId());
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
}
