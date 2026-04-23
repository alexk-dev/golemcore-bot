package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersistentScheduledTaskServiceTest {

    private StoragePort storagePort;
    private PersistentScheduledTaskService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        when(storagePort.getText(eq("auto"), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putTextAtomic(eq("auto"), eq("scheduled-tasks.json"), anyString(), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(null));

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
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
                .build();

        ScheduledTask first = service.createFromLegacyGoal(legacyGoal);
        ScheduledTask second = service.createFromLegacyGoal(legacyGoal);

        assertEquals(first.getId(), second.getId());
        assertEquals(1, service.getScheduledTasks().size());
        assertTrue(service.findByLegacySource("GOAL", "goal-1").isPresent());
    }
}
