package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.port.outbound.StoragePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-02-11T10:00:00Z");
    private static final String CRON_WEEKDAYS_9AM = "0 0 9 * * MON-FRI";
    private static final String CRON_DAILY_NOON = "0 0 12 * * *";
    private static final String CRON_DAILY_9AM = "0 0 9 * * *";
    private static final String TARGET_GOAL_1 = "goal-1";

    private StoragePort storagePort;
    private ObjectMapper objectMapper;
    private Clock clock;
    private ScheduleService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

        when(storagePort.getText(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        service = new ScheduleService(storagePort, objectMapper, clock);
    }

    @Test
    void shouldCreateGoalSchedule() {
        ScheduleEntry entry = service.createSchedule(
                ScheduleEntry.ScheduleType.GOAL, "goal-123",
                CRON_WEEKDAYS_9AM, -1);

        assertNotNull(entry.getId());
        assertTrue(entry.getId().startsWith("sched-goal-"));
        assertEquals(ScheduleEntry.ScheduleType.GOAL, entry.getType());
        assertEquals("goal-123", entry.getTargetId());
        assertEquals(CRON_WEEKDAYS_9AM, entry.getCronExpression());
        assertTrue(entry.isEnabled());
        assertEquals(-1, entry.getMaxExecutions());
        assertEquals(0, entry.getExecutionCount());
        assertNotNull(entry.getNextExecutionAt());
        verify(storagePort).putText(any(), any(), any());
    }

    @Test
    void shouldCreateTaskSchedule() {
        ScheduleEntry entry = service.createSchedule(
                ScheduleEntry.ScheduleType.TASK, "task-456",
                "0 30 14 * * *", 1);

        assertTrue(entry.getId().startsWith("sched-task-"));
        assertEquals(ScheduleEntry.ScheduleType.TASK, entry.getType());
        assertEquals("task-456", entry.getTargetId());
        assertEquals(1, entry.getMaxExecutions());
    }

    @Test
    void shouldCreateOneShotScheduleWithMaxExecutionsOne() {
        ScheduleEntry entry = service.createSchedule(
                ScheduleEntry.ScheduleType.TASK, "task-789",
                CRON_DAILY_NOON, 1);

        assertEquals(1, entry.getMaxExecutions());
        assertEquals(0, entry.getExecutionCount());
        assertFalse(entry.isExhausted());
    }

    @Test
    void shouldNormalizeFiveFieldCronToSixField() {
        String result = ScheduleService.normalizeCronExpression("*/15 * * * *");
        assertEquals("0 */15 * * * *", result);
    }

    @Test
    void shouldPassThroughSixFieldCron() {
        String result = ScheduleService.normalizeCronExpression(CRON_WEEKDAYS_9AM);
        assertEquals(CRON_WEEKDAYS_9AM, result);
    }

    @Test
    void shouldRejectInvalidCronExpression() {
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleService.normalizeCronExpression("not a cron"));
    }

    @Test
    void shouldRejectEmptyCronExpression() {
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleService.normalizeCronExpression(""));
    }

    @Test
    void shouldRejectNullCronExpression() {
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleService.normalizeCronExpression(null));
    }

    @Test
    void shouldComputeNextExecutionCorrectly() {
        // 2026-02-11 is a Wednesday, 10:00 UTC
        // Cron: every day at 12:00 UTC
        Instant next = service.computeNextExecution(CRON_DAILY_NOON, FIXED_NOW);

        assertNotNull(next);
        assertEquals(Instant.parse("2026-02-11T12:00:00Z"), next);
    }

    @Test
    void shouldComputeNextExecutionForWeekdayOnly() {
        // 2026-02-11 is Wednesday, cron is MON-FRI at 09:00
        // Current time is 10:00, so next is Thursday 09:00
        Instant next = service.computeNextExecution(CRON_WEEKDAYS_9AM, FIXED_NOW);

        assertNotNull(next);
        assertEquals(Instant.parse("2026-02-12T09:00:00Z"), next);
    }

    @Test
    void shouldFindDueSchedules() {
        // Create a schedule that should be due (next execution in the past relative to
        // clock)
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_DAILY_9AM, -1);

        // The schedule was created with next execution at 2026-02-12T09:00 (after 10:00
        // today)
        // So it's not due yet. Let's manually set it to be due.
        ScheduleEntry entry = service.getSchedules().get(0);
        entry.setNextExecutionAt(FIXED_NOW.minusSeconds(60));

        List<ScheduleEntry> due = service.getDueSchedules();
        assertEquals(1, due.size());
        assertEquals(entry.getId(), due.get(0).getId());
    }

    @Test
    void shouldNotReturnDisabledSchedulesAsDue() {
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_DAILY_9AM, -1);

        ScheduleEntry entry = service.getSchedules().get(0);
        entry.setNextExecutionAt(FIXED_NOW.minusSeconds(60));
        entry.setEnabled(false);

        List<ScheduleEntry> due = service.getDueSchedules();
        assertTrue(due.isEmpty());
    }

    @Test
    void shouldNotReturnExhaustedSchedulesAsDue() {
        service.createSchedule(ScheduleEntry.ScheduleType.TASK, "task-1",
                CRON_DAILY_9AM, 1);

        ScheduleEntry entry = service.getSchedules().get(0);
        entry.setNextExecutionAt(FIXED_NOW.minusSeconds(60));
        entry.setExecutionCount(1); // exhausted (maxExecutions=1)

        List<ScheduleEntry> due = service.getDueSchedules();
        assertTrue(due.isEmpty());
    }

    @Test
    void shouldNotReturnFutureSchedulesAsDue() {
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_DAILY_NOON, -1);

        // Next execution is in the future (12:00 today, now is 10:00)
        List<ScheduleEntry> due = service.getDueSchedules();
        assertTrue(due.isEmpty());
    }

    @Test
    void shouldRecordExecutionAndUpdateNextRun() {
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_DAILY_NOON, -1);

        ScheduleEntry entry = service.getSchedules().get(0);
        String id = entry.getId();

        service.recordExecution(id);

        ScheduleEntry updated = service.findSchedule(id).orElseThrow();
        assertEquals(1, updated.getExecutionCount());
        assertEquals(FIXED_NOW, updated.getLastExecutedAt());
        assertNotNull(updated.getNextExecutionAt());
        assertTrue(updated.isEnabled());
    }

    @Test
    void shouldDisableExhaustedScheduleAfterExecution() {
        service.createSchedule(ScheduleEntry.ScheduleType.TASK, "task-1",
                CRON_DAILY_NOON, 1);

        ScheduleEntry entry = service.getSchedules().get(0);
        String id = entry.getId();

        service.recordExecution(id);

        ScheduleEntry updated = service.findSchedule(id).orElseThrow();
        assertEquals(1, updated.getExecutionCount());
        assertFalse(updated.isEnabled());
        assertNull(updated.getNextExecutionAt());
    }

    @Test
    void shouldDeleteScheduleById() {
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_DAILY_9AM, -1);

        ScheduleEntry entry = service.getSchedules().get(0);
        String id = entry.getId();

        service.deleteSchedule(id);

        assertTrue(service.getSchedules().isEmpty());
    }

    @Test
    void shouldThrowWhenDeletingNonexistentSchedule() {
        assertThrows(IllegalArgumentException.class,
                () -> service.deleteSchedule("nonexistent-id"));
    }

    @Test
    void shouldFindSchedulesForTarget() {
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_DAILY_9AM, -1);
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                "0 0 18 * * *", -1);
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, "goal-2",
                CRON_DAILY_NOON, -1);

        List<ScheduleEntry> forGoal1 = service.findSchedulesForTarget(TARGET_GOAL_1);
        assertEquals(2, forGoal1.size());

        List<ScheduleEntry> forGoal2 = service.findSchedulesForTarget("goal-2");
        assertEquals(1, forGoal2.size());
    }

    @Test
    void shouldReturnEmptyWhenNoSchedules() {
        assertTrue(service.getSchedules().isEmpty());
        assertTrue(service.getDueSchedules().isEmpty());
    }

    @Test
    void shouldFindScheduleById() {
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_DAILY_9AM, -1);

        ScheduleEntry entry = service.getSchedules().get(0);
        Optional<ScheduleEntry> found = service.findSchedule(entry.getId());

        assertTrue(found.isPresent());
        assertEquals(entry.getId(), found.get().getId());
    }

    @Test
    void shouldReturnEmptyForNonexistentScheduleId() {
        Optional<ScheduleEntry> found = service.findSchedule("nonexistent");
        assertFalse(found.isPresent());
    }

    @Test
    void shouldPersistAndLoadSchedulesRoundTrip() throws Exception {
        // Create a schedule
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_WEEKDAYS_9AM, -1);

        // Capture what was persisted
        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(storagePort).putText(any(), any(), jsonCaptor.capture());

        String savedJson = jsonCaptor.getValue();

        // Create a new service that loads from storage
        when(storagePort.getText("auto", "schedules.json"))
                .thenReturn(CompletableFuture.completedFuture(savedJson));

        ScheduleService newService = new ScheduleService(storagePort, objectMapper, clock);
        List<ScheduleEntry> loaded = newService.getSchedules();

        assertEquals(1, loaded.size());
        assertEquals(TARGET_GOAL_1, loaded.get(0).getTargetId());
        assertEquals(ScheduleEntry.ScheduleType.GOAL, loaded.get(0).getType());
        assertEquals(CRON_WEEKDAYS_9AM, loaded.get(0).getCronExpression());
    }
}
