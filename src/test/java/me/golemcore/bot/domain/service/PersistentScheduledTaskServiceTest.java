package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.port.outbound.StoragePort;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersistentScheduledTaskServiceTest {

    private StoragePort storagePort;
    private ObjectMapper objectMapper;
    private PersistentScheduledTaskService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        when(storagePort.getText(eq("auto"), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putTextAtomic(eq("auto"), eq("scheduled-tasks.json"), anyString(), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(null));

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new PersistentScheduledTaskService(storagePort, objectMapper);
    }

    @Test
    void getScheduledTasksShouldReturnImmutableSnapshot() {
        ScheduledTask task = service.createScheduledTask("Refresh inbox", null, null, null, false);

        var tasks = service.getScheduledTasks();

        assertEquals(1, tasks.size());
        assertEquals(task.getId(), tasks.getFirst().getId());
        assertThrows(UnsupportedOperationException.class, () -> tasks.add(task));
    }

    @Test
    void createFromLegacyGoalShouldBeIdempotentByLegacySource() {
        Goal legacyGoal = Goal.builder()
                .id("goal-1")
                .title("Legacy goal")
                .description(" Legacy description ")
                .prompt(" Legacy prompt ")
                .reflectionModelTier("deep")
                .reflectionTierPriority(true)
                .consecutiveFailureCount(2)
                .reflectionRequired(true)
                .lastFailureSummary("failed")
                .lastFailureFingerprint("fingerprint")
                .reflectionStrategy("try another path")
                .lastUsedSkillName("inspector")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-02T00:00:00Z"))
                .build();

        ScheduledTask first = service.createFromLegacyGoal(legacyGoal);
        ScheduledTask second = service.createFromLegacyGoal(legacyGoal);

        assertEquals(first.getId(), second.getId());
        assertEquals("Legacy description", first.getDescription());
        assertEquals("Legacy prompt", first.getPrompt());
        assertEquals("deep", first.getReflectionModelTier());
        assertTrue(first.isReflectionTierPriority());
        assertTrue(first.isReflectionRequired());
        assertEquals("GOAL", first.getLegacySourceType());
        assertEquals("goal-1", first.getLegacySourceId());
        assertEquals(1, service.getScheduledTasks().size());
        assertTrue(service.findByLegacySource("GOAL", "goal-1").isPresent());
    }

    @Test
    void shouldCreateUpdateDeleteAndLookupScheduledTask() throws Exception {
        ScheduledTask created = service.createScheduledTask(
                " Refresh inbox ",
                " Pull unread messages ",
                " Check sources ",
                " Coding ",
                true);

        assertEquals("Refresh inbox", created.getTitle());
        assertEquals("Pull unread messages", created.getDescription());
        assertEquals("Check sources", created.getPrompt());
        assertEquals("Coding", created.getReflectionModelTier());
        assertTrue(created.isReflectionTierPriority());
        assertTrue(service.getScheduledTask(created.getId()).isPresent());
        assertTrue(service.getScheduledTask(" ").isEmpty());

        ScheduledTask updated = service.updateScheduledTask(
                created.getId(),
                " Updated ",
                " ",
                " New prompt ",
                null,
                false);

        assertEquals("Updated", updated.getTitle());
        assertNull(updated.getDescription());
        assertEquals("New prompt", updated.getPrompt());
        assertNull(updated.getReflectionModelTier());
        assertFalse(updated.isReflectionTierPriority());

        service.deleteScheduledTask(created.getId());

        assertTrue(service.getScheduledTasks().isEmpty());
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putTextAtomic(eq("auto"), eq("scheduled-tasks.json"),
                jsonCaptor.capture(), eq(true));
        assertEquals(0, objectMapper.readValue(jsonCaptor.getAllValues().getLast(), ScheduledTask[].class).length);
    }

    @Test
    void shouldCreateLegacyTaskWithFallbackDescriptionAndAvoidDuplicates() {
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Legacy goal")
                .build();
        AutoTask legacyTask = AutoTask.builder()
                .id("task-1")
                .title("Legacy task")
                .description(" ")
                .prompt(" Task prompt ")
                .reflectionModelTier("smart")
                .reflectionTierPriority(true)
                .consecutiveFailureCount(3)
                .reflectionRequired(true)
                .lastFailureSummary("timeout")
                .lastFailureFingerprint("timeout")
                .reflectionStrategy("use cache")
                .lastUsedSkillName("debugger")
                .createdAt(Instant.parse("2026-02-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-02-02T00:00:00Z"))
                .build();

        ScheduledTask first = service.createFromLegacyTask(goal, legacyTask);
        ScheduledTask second = service.createFromLegacyTask(goal, legacyTask);

        assertEquals(first.getId(), second.getId());
        assertEquals("Legacy task", first.getTitle());
        assertEquals("Migrated from goal: Legacy goal", first.getDescription());
        assertEquals("Task prompt", first.getPrompt());
        assertEquals("TASK", first.getLegacySourceType());
        assertEquals("task-1", first.getLegacySourceId());
        assertEquals(1, service.getScheduledTasks().size());
        assertTrue(service.findByLegacySource(" TASK ", " task-1 ").isPresent());
    }

    @Test
    void shouldRecordFailureSuccessAndReflectionState() {
        ScheduledTask task = service.createScheduledTask("Run report", null, null, null, false);

        service.recordFailure(task.getId(), " first failure ", null, " scout ", 2);
        ScheduledTask afterFirstFailure = service.getScheduledTask(task.getId()).orElseThrow();
        assertEquals(1, afterFirstFailure.getConsecutiveFailureCount());
        assertFalse(afterFirstFailure.isReflectionRequired());
        assertEquals("first failure", afterFirstFailure.getLastFailureSummary());
        assertEquals("first failure", afterFirstFailure.getLastFailureFingerprint());
        assertEquals("scout", afterFirstFailure.getLastUsedSkillName());
        assertNotNull(afterFirstFailure.getLastFailureAt());

        service.recordFailure(task.getId(), " second failure ", " explicit-fingerprint ", " scout ", 2);
        ScheduledTask afterSecondFailure = service.getScheduledTask(task.getId()).orElseThrow();
        assertEquals(2, afterSecondFailure.getConsecutiveFailureCount());
        assertTrue(afterSecondFailure.isReflectionRequired());
        assertEquals("explicit-fingerprint", afterSecondFailure.getLastFailureFingerprint());

        service.applyReflectionResult(task.getId(), " Try a different source ");
        ScheduledTask afterReflection = service.getScheduledTask(task.getId()).orElseThrow();
        assertEquals("Try a different source", afterReflection.getReflectionStrategy());
        assertFalse(afterReflection.isReflectionRequired());
        assertEquals(0, afterReflection.getConsecutiveFailureCount());
        assertNotNull(afterReflection.getLastReflectionAt());

        service.recordSuccess(task.getId(), " reporter ");
        ScheduledTask afterSuccess = service.getScheduledTask(task.getId()).orElseThrow();
        assertEquals(0, afterSuccess.getConsecutiveFailureCount());
        assertFalse(afterSuccess.isReflectionRequired());
        assertNull(afterSuccess.getLastFailureSummary());
        assertNull(afterSuccess.getLastFailureFingerprint());
        assertNull(afterSuccess.getLastFailureAt());
        assertEquals("reporter", afterSuccess.getLastUsedSkillName());
    }

    @Test
    void shouldLoadExistingTasksAndHandleStorageFailures() throws Exception {
        ScheduledTask existing = ScheduledTask.builder()
                .id("scheduled-task-1")
                .title("Existing")
                .createdAt(Instant.parse("2026-03-01T00:00:00Z"))
                .build();
        when(storagePort.getText(eq("auto"), eq("scheduled-tasks.json")))
                .thenReturn(CompletableFuture.completedFuture(
                        objectMapper.writeValueAsString(List.of(existing))));

        assertEquals("Existing", service.getScheduledTask("scheduled-task-1").orElseThrow().getTitle());

        StoragePort failingStorage = mock(StoragePort.class);
        when(failingStorage.getText(eq("auto"), eq("scheduled-tasks.json")))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("storage unavailable")));
        PersistentScheduledTaskService failingService = new PersistentScheduledTaskService(
                failingStorage,
                new ObjectMapper().registerModule(new JavaTimeModule()));

        assertTrue(failingService.getScheduledTasks().isEmpty());
    }

    @Test
    void shouldRejectInvalidOperations() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createScheduledTask(" ", null, null, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateScheduledTask("missing", "Title", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.deleteScheduledTask("missing"));
        assertThrows(IllegalArgumentException.class, () -> service.createFromLegacyGoal(null));
        assertThrows(IllegalArgumentException.class, () -> service.createFromLegacyTask(null, null));
        assertTrue(service.findByLegacySource(null, "id").isEmpty());
        assertTrue(service.findByLegacySource("TASK", " ").isEmpty());
    }
}
