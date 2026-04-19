package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionGoalStorageServiceTest {

    private StoragePort storagePort;
    private ObjectMapper objectMapper;
    private SessionGoalStorageService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        when(storagePort.getText(eq("auto"), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putTextAtomic(eq("auto"), anyString(), anyString(), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.deleteObject(eq("auto"), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        service = new SessionGoalStorageService(storagePort, objectMapper);
    }

    @Test
    void loadGoalsShouldReturnSessionGoalsAndFallbackToEmptyOnMissingOrBadStorage() throws Exception {
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Goal")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(storagePort.getText(eq("auto"), eq("session-goals/session-1.json")))
                .thenReturn(CompletableFuture.completedFuture(objectMapper.writeValueAsString(List.of(goal))));

        List<Goal> goals = service.loadGoals("session-1");

        assertEquals(1, goals.size());
        assertEquals("Goal", goals.getFirst().getTitle());
        goals.clear();
        assertEquals(1, service.loadGoals("session-1").size());

        when(storagePort.getText(eq("auto"), eq("session-goals/blank-session.json")))
                .thenReturn(CompletableFuture.completedFuture(" "));
        assertTrue(service.loadGoals("blank-session").isEmpty());

        when(storagePort.getText(eq("auto"), eq("session-goals/bad-session.json")))
                .thenReturn(CompletableFuture.completedFuture("{bad json"));
        assertTrue(service.loadGoals("bad-session").isEmpty());

        when(storagePort.getText(eq("auto"), eq("session-goals/failing-session.json")))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("unavailable")));
        assertTrue(service.loadGoals("failing-session").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.loadGoals(" "));
    }

    @Test
    void saveGoalsShouldWriteNonEmptyGoalsAndDeleteEmptyGoalSets() throws Exception {
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Goal")
                .build();

        service.saveGoals("session-1", List.of(goal));
        service.saveGoals("session-1", List.of());
        service.saveGoals("session-1", null);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(storagePort).putTextAtomic(eq("auto"), eq("session-goals/session-1.json"), jsonCaptor.capture(),
                eq(true));
        assertEquals("Goal", objectMapper.readValue(jsonCaptor.getValue(), Goal[].class)[0].getTitle());
        verify(storagePort, times(2)).deleteObject(eq("auto"), eq("session-goals/session-1.json"));
        assertThrows(IllegalArgumentException.class, () -> service.saveGoals("", List.of(goal)));
    }

    @Test
    void saveGoalsShouldFailFastWhenPersistenceFails() {
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Goal")
                .build();
        when(storagePort.putTextAtomic(eq("auto"), eq("session-goals/session-1.json"), anyString(), eq(true)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk full")));

        assertThrows(IllegalStateException.class, () -> service.saveGoals("session-1", List.of(goal)));
    }

    @Test
    void deleteGoalsShouldBeBestEffort() {
        when(storagePort.deleteObject(eq("auto"), eq("session-goals/session-1.json")))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("missing")));

        service.deleteGoals("session-1");

        verify(storagePort).deleteObject(eq("auto"), eq("session-goals/session-1.json"));
        assertThrows(IllegalArgumentException.class, () -> service.deleteGoals(null));
    }
}
