package me.golemcore.bot.domain.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.adapter.outbound.storage.StorageSchedulePersistenceAdapter;
import me.golemcore.bot.adapter.outbound.update.SpringCronScheduleAdapter;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.ScheduleReportConfig;
import me.golemcore.bot.domain.model.ScheduleReportConfigUpdate;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import static org.mockito.Mockito.anyBoolean;
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
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));

        service = new ScheduleService(
                new StorageSchedulePersistenceAdapter(storagePort, objectMapper),
                new SpringCronScheduleAdapter(),
                clock);
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
        verify(storagePort).putTextAtomic(any(), any(), any(), anyBoolean());
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
    void shouldCreateScheduleWithClearContextBeforeRunEnabled() {
        ScheduleEntry entry = service.createSchedule(
                ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_DAILY_9AM, -1, true);

        assertTrue(entry.isClearContextBeforeRun());
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
        String result = service.normalizeCronExpression("*/15 * * * *");
        assertEquals("0 */15 * * * *", result);
    }

    @Test
    void shouldPassThroughSixFieldCron() {
        String result = service.normalizeCronExpression(CRON_WEEKDAYS_9AM);
        assertEquals(CRON_WEEKDAYS_9AM, result);
    }

    @Test
    void shouldRejectInvalidCronExpression() {
        assertThrows(IllegalArgumentException.class,
                () -> service.normalizeCronExpression("not a cron"));
    }

    @Test
    void shouldRejectEmptyCronExpression() {
        assertThrows(IllegalArgumentException.class,
                () -> service.normalizeCronExpression(""));
    }

    @Test
    void shouldRejectNullCronExpression() {
        assertThrows(IllegalArgumentException.class,
                () -> service.normalizeCronExpression(null));
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
    void shouldReportScheduledTaskAsBlockedWhenRetryWindowIsActive() {
        ScheduleEntry entry = service.createSchedule(
                ScheduleEntry.ScheduleType.SCHEDULED_TASK,
                "scheduled-task-1",
                CRON_DAILY_NOON,
                -1);

        entry.setActiveWindowStartedAt(FIXED_NOW.minusSeconds(30));
        entry.setNextWindowAt(FIXED_NOW.plusSeconds(300));
        entry.setRetryCount(1);

        assertTrue(service.isScheduledTaskBlocked("scheduled-task-1"));
        assertFalse(service.isScheduledTaskBlocked("scheduled-task-2"));
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
    void shouldCoalesceMissedWindowsAfterLongRunningExecution() {
        service.createSchedule(ScheduleEntry.ScheduleType.SCHEDULED_TASK, "scheduled-task-1",
                "0 * * * * *", -1);

        ScheduleEntry entry = service.getSchedules().get(0);
        String id = entry.getId();
        entry.setNextExecutionAt(Instant.parse("2026-02-11T09:58:00Z"));

        service.recordExecution(id);

        ScheduleEntry updated = service.findSchedule(id).orElseThrow();
        assertEquals(1, updated.getExecutionCount());
        assertEquals(FIXED_NOW, updated.getLastExecutedAt());
        assertEquals(Instant.parse("2026-02-11T10:01:00Z"), updated.getNextExecutionAt());
        assertTrue(updated.isEnabled());
    }

    @Test
    void shouldCoalesceDeepMissedBacklogAfterSchedulerDowntime() {
        service.createSchedule(ScheduleEntry.ScheduleType.SCHEDULED_TASK, "scheduled-task-1",
                "0 * * * * *", -1);

        ScheduleEntry entry = service.getSchedules().get(0);
        String id = entry.getId();
        entry.setNextExecutionAt(Instant.parse("2026-02-11T09:00:00Z"));

        service.recordExecution(id);

        ScheduleEntry updated = service.findSchedule(id).orElseThrow();
        assertEquals(1, updated.getExecutionCount());
        assertEquals(FIXED_NOW, updated.getLastExecutedAt());
        assertEquals(Instant.parse("2026-02-11T10:01:00Z"), updated.getNextExecutionAt());
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
    void shouldRecordFailedAttemptAndUpdateNextRun() {
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_DAILY_NOON, -1);

        ScheduleEntry entry = service.getSchedules().get(0);
        String id = entry.getId();
        entry.setNextExecutionAt(FIXED_NOW.minusSeconds(30));

        service.recordFailedAttempt(id);

        ScheduleEntry updated = service.findSchedule(id).orElseThrow();
        assertEquals(0, updated.getExecutionCount());
        assertEquals(1, updated.getRetryCount());
        assertNull(updated.getLastExecutedAt());
        assertEquals(FIXED_NOW.plusSeconds(60), updated.getNextExecutionAt());
        assertEquals(FIXED_NOW.minusSeconds(30), updated.getActiveWindowStartedAt());
        assertEquals(Instant.parse("2026-02-11T12:00:00Z"), updated.getNextWindowAt());
        assertTrue(updated.isEnabled());
    }

    @Test
    void shouldFinalizeFailedWindowWhenRetryWouldCrossNextScheduleWindow() {
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                "0 * * * * *", -1);

        ScheduleEntry entry = service.getSchedules().get(0);
        String id = entry.getId();
        entry.setNextExecutionAt(FIXED_NOW.minusSeconds(10));

        service.recordFailedAttempt(id);

        ScheduleEntry updated = service.findSchedule(id).orElseThrow();
        assertEquals(1, updated.getExecutionCount());
        assertEquals(FIXED_NOW, updated.getLastExecutedAt());
        assertEquals(0, updated.getRetryCount());
        assertNull(updated.getActiveWindowStartedAt());
        assertNull(updated.getNextWindowAt());
        assertEquals(Instant.parse("2026-02-11T10:00:00Z"), updated.getNextExecutionAt());
    }

    @Test
    void shouldCoalesceMissedWindowsAfterLongRunningFailedExecution() {
        service.createSchedule(ScheduleEntry.ScheduleType.SCHEDULED_TASK, "scheduled-task-1",
                "0 * * * * *", -1);

        ScheduleEntry entry = service.getSchedules().get(0);
        String id = entry.getId();
        entry.setNextExecutionAt(Instant.parse("2026-02-11T09:58:00Z"));

        service.recordFailedAttempt(id);

        ScheduleEntry updated = service.findSchedule(id).orElseThrow();
        assertEquals(1, updated.getExecutionCount());
        assertEquals(FIXED_NOW, updated.getLastExecutedAt());
        assertEquals(0, updated.getRetryCount());
        assertNull(updated.getActiveWindowStartedAt());
        assertNull(updated.getNextWindowAt());
        assertEquals(Instant.parse("2026-02-11T10:01:00Z"), updated.getNextExecutionAt());
        assertTrue(updated.isEnabled());
    }

    @Test
    void shouldPromoteExpiredRetryWindowWhenSchedulerResumesAfterDowntime() {
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                "0 * * * * *", -1);

        ScheduleEntry entry = service.getSchedules().get(0);
        entry.setNextExecutionAt(FIXED_NOW.minusSeconds(30));
        entry.setRetryCount(1);
        entry.setActiveWindowStartedAt(FIXED_NOW.minusSeconds(30));
        entry.setNextWindowAt(FIXED_NOW);

        List<ScheduleEntry> dueSchedules = service.getDueSchedules();

        assertEquals(1, dueSchedules.size());
        ScheduleEntry due = dueSchedules.getFirst();
        assertEquals(0, due.getRetryCount());
        assertEquals(FIXED_NOW, due.getActiveWindowStartedAt());
        assertEquals(FIXED_NOW, due.getNextExecutionAt());
        assertEquals(Instant.parse("2026-02-11T10:01:00Z"), due.getNextWindowAt());
    }

    @Test
    void shouldBlockOtherScheduledTaskSchedulesWhileRetryWindowIsActive() {
        service.createSchedule(ScheduleEntry.ScheduleType.SCHEDULED_TASK, "scheduled-task-1",
                "0 * * * * *", -1);
        service.createSchedule(ScheduleEntry.ScheduleType.SCHEDULED_TASK, "scheduled-task-1",
                CRON_DAILY_NOON, -1);

        ScheduleEntry retryOwner = service.getSchedules().get(0);
        retryOwner.setRetryCount(1);
        retryOwner.setActiveWindowStartedAt(FIXED_NOW.minusSeconds(30));
        retryOwner.setNextWindowAt(Instant.parse("2026-02-11T10:01:00Z"));
        retryOwner.setNextExecutionAt(FIXED_NOW.plusSeconds(30));

        ScheduleEntry blocked = service.getSchedules().get(1);
        blocked.setNextExecutionAt(FIXED_NOW.minusSeconds(5));

        List<ScheduleEntry> dueSchedules = service.getDueSchedules();

        assertTrue(dueSchedules.isEmpty());
    }

    @Test
    void shouldDisableScheduleAndClearNextRun() {
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_DAILY_NOON, -1);

        ScheduleEntry entry = service.getSchedules().get(0);

        service.disableSchedule(entry.getId());

        ScheduleEntry disabled = service.findSchedule(entry.getId()).orElseThrow();
        assertFalse(disabled.isEnabled());
        assertNull(disabled.getNextExecutionAt());
        assertEquals(FIXED_NOW, disabled.getUpdatedAt());
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
    void shouldUpdateScheduleAndRecomputeNextExecution() {
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_DAILY_9AM, -1);

        ScheduleEntry entry = service.getSchedules().get(0);
        String id = entry.getId();

        ScheduleEntry updated = service.updateSchedule(
                id,
                ScheduleEntry.ScheduleType.TASK,
                "task-2",
                CRON_DAILY_NOON,
                3,
                true);

        assertEquals(ScheduleEntry.ScheduleType.TASK, updated.getType());
        assertEquals("task-2", updated.getTargetId());
        assertEquals(CRON_DAILY_NOON, updated.getCronExpression());
        assertEquals(3, updated.getMaxExecutions());
        assertTrue(updated.isEnabled());
        assertEquals(Instant.parse("2026-02-11T12:00:00Z"), updated.getNextExecutionAt());
    }

    @Test
    void shouldKeepUpdatedScheduleDisabledWhenNewLimitIsAlreadyExhausted() {
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_DAILY_9AM, -1);

        ScheduleEntry entry = service.getSchedules().get(0);
        entry.setExecutionCount(2);

        ScheduleEntry updated = service.updateSchedule(
                entry.getId(),
                ScheduleEntry.ScheduleType.GOAL,
                TARGET_GOAL_1,
                CRON_DAILY_NOON,
                2,
                true);

        assertFalse(updated.isEnabled());
        assertNull(updated.getNextExecutionAt());
        assertEquals(2, updated.getExecutionCount());
    }

    @Test
    void shouldPreserveClearContextBeforeRunWhenUpdateOmitsFlag() {
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_DAILY_9AM, -1, true);

        ScheduleEntry entry = service.getSchedules().get(0);

        ScheduleEntry updated = service.updateSchedule(
                entry.getId(),
                ScheduleEntry.ScheduleType.GOAL,
                TARGET_GOAL_1,
                CRON_DAILY_NOON,
                -1,
                true);

        assertTrue(updated.isClearContextBeforeRun());
    }

    @Test
    void shouldUpdateClearContextBeforeRunWhenProvided() {
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_DAILY_9AM, -1);

        ScheduleEntry entry = service.getSchedules().get(0);

        ScheduleEntry updated = service.updateSchedule(
                entry.getId(),
                ScheduleEntry.ScheduleType.GOAL,
                TARGET_GOAL_1,
                CRON_DAILY_NOON,
                -1,
                true,
                true);

        assertTrue(updated.isClearContextBeforeRun());
    }

    @Test
    void shouldSynchronizeScheduleUpdateOverloads() throws Exception {
        Method reportUpdateOverload = ScheduleService.class.getDeclaredMethod(
                "updateSchedule",
                String.class,
                ScheduleEntry.ScheduleType.class,
                String.class,
                String.class,
                int.class,
                boolean.class,
                Boolean.class,
                ScheduleReportConfigUpdate.class);
        Method clearContextOverload = ScheduleService.class.getDeclaredMethod(
                "updateSchedule",
                String.class,
                ScheduleEntry.ScheduleType.class,
                String.class,
                String.class,
                int.class,
                boolean.class,
                Boolean.class);

        assertTrue(Modifier.isSynchronized(reportUpdateOverload.getModifiers()));
        assertTrue(Modifier.isSynchronized(clearContextOverload.getModifiers()));
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
    void shouldReturnDefensiveCopyOfSchedulesList() {
        service.createSchedule(ScheduleEntry.ScheduleType.GOAL, TARGET_GOAL_1,
                CRON_DAILY_9AM, -1);

        List<ScheduleEntry> snapshot = service.getSchedules();
        assertThrows(UnsupportedOperationException.class, snapshot::clear);

        assertEquals(1, service.getSchedules().size());
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
                CRON_WEEKDAYS_9AM, -1, true);

        // Capture what was persisted
        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(storagePort).putTextAtomic(any(), any(), jsonCaptor.capture(), anyBoolean());

        String savedJson = jsonCaptor.getValue();

        // Create a new service that loads from storage
        when(storagePort.getText("auto", "schedules.json"))
                .thenReturn(CompletableFuture.completedFuture(savedJson));

        ScheduleService newService = new ScheduleService(
                new StorageSchedulePersistenceAdapter(storagePort, objectMapper),
                new SpringCronScheduleAdapter(),
                clock);
        List<ScheduleEntry> loaded = newService.getSchedules();

        assertEquals(1, loaded.size());
        assertEquals(TARGET_GOAL_1, loaded.get(0).getTargetId());
        assertEquals(ScheduleEntry.ScheduleType.GOAL, loaded.get(0).getType());
        assertEquals(CRON_WEEKDAYS_9AM, loaded.get(0).getCronExpression());
        assertTrue(loaded.get(0).isClearContextBeforeRun());
    }

    @Test
    void shouldWrapScheduleLoadFailures() {
        when(storagePort.getText("auto", "schedules.json"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("read failed")));
        StorageSchedulePersistenceAdapter adapter = new StorageSchedulePersistenceAdapter(storagePort, objectMapper);

        IllegalStateException exception = assertThrows(IllegalStateException.class, adapter::loadSchedules);

        assertEquals("Failed to load schedules", exception.getMessage());
    }

    @Test
    void shouldWrapScheduleSaveFailures() {
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("write failed")));
        StorageSchedulePersistenceAdapter adapter = new StorageSchedulePersistenceAdapter(storagePort, objectMapper);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> adapter.saveSchedules(List.of()));

        assertEquals("Failed to persist schedules", exception.getMessage());
    }

    @Test
    void shouldCreateScheduleWithReportConfig() {
        ScheduleReportConfig report = ScheduleReportConfig.builder()
                .channelType("telegram")
                .chatId("99999")
                .build();

        ScheduleEntry entry = service.createSchedule(
                ScheduleEntry.ScheduleType.GOAL, "goal-rpt",
                CRON_DAILY_9AM, -1, false,
                report);

        assertNotNull(entry.getId());
        assertNotNull(entry.getReport());
        assertEquals("telegram", entry.getReport().getChannelType());
        assertEquals("99999", entry.getReport().getChatId());
        assertTrue(entry.isEnabled());
        verify(storagePort).putTextAtomic(any(), any(), any(), anyBoolean());
    }

    @Test
    void shouldCreateScheduleWithWebhookReportConfig() {
        ScheduleReportConfig report = ScheduleReportConfig.builder()
                .channelType("webhook")
                .webhookUrl("https://example.com/hook")
                .webhookBearerToken("bearer-token")
                .build();

        ScheduleEntry entry = service.createSchedule(
                ScheduleEntry.ScheduleType.GOAL, "goal-rpt2",
                CRON_DAILY_9AM, -1, false,
                report);

        assertNotNull(entry.getReport());
        assertEquals("webhook", entry.getReport().getChannelType());
        assertEquals("https://example.com/hook", entry.getReport().getWebhookUrl());
        assertEquals("bearer-token", entry.getReport().getWebhookBearerToken());
    }

    @Test
    void shouldUpdateScheduleWithExplicitReportConfigChange() {
        ScheduleEntry entry = service.createSchedule(
                ScheduleEntry.ScheduleType.GOAL, "goal-upd",
                CRON_DAILY_9AM, -1);

        assertNull(entry.getReport());

        ScheduleReportConfig report = ScheduleReportConfig.builder()
                .channelType("telegram")
                .chatId("12345")
                .build();

        ScheduleEntry updated = service.updateSchedule(
                entry.getId(),
                ScheduleEntry.ScheduleType.GOAL,
                "goal-upd",
                CRON_DAILY_NOON,
                -1,
                true,
                null,
                ScheduleReportConfigUpdate.set(report));

        assertNotNull(updated.getReport());
        assertEquals("telegram", updated.getReport().getChannelType());
        assertEquals("12345", updated.getReport().getChatId());
    }

    @Test
    void shouldClearReportConfigWhenExplicitlyRequested() {
        ScheduleEntry entry = service.createSchedule(
                ScheduleEntry.ScheduleType.GOAL, "goal-keep",
                CRON_DAILY_9AM, -1, false,
                ScheduleReportConfig.builder()
                        .channelType("telegram")
                        .chatId("55555")
                        .build());

        ScheduleEntry updated = service.updateSchedule(
                entry.getId(),
                ScheduleEntry.ScheduleType.GOAL,
                "goal-keep",
                CRON_DAILY_NOON,
                -1,
                true,
                null,
                ScheduleReportConfigUpdate.clear());

        assertNull(updated.getReport());
    }

    @Test
    void shouldPreserveReportConfigWhenUpdateDoesNotChangeIt() {
        ScheduleEntry entry = service.createSchedule(
                ScheduleEntry.ScheduleType.GOAL, "goal-keep",
                CRON_DAILY_9AM, -1, false,
                ScheduleReportConfig.builder()
                        .channelType("telegram")
                        .chatId("55555")
                        .build());

        ScheduleEntry updated = service.updateSchedule(
                entry.getId(),
                ScheduleEntry.ScheduleType.GOAL,
                "goal-keep",
                CRON_DAILY_NOON,
                -1,
                true,
                null,
                ScheduleReportConfigUpdate.noChange());

        assertNotNull(updated.getReport());
        assertEquals("telegram", updated.getReport().getChannelType());
        assertEquals("55555", updated.getReport().getChatId());
    }
}
