package me.golemcore.bot.adapter.inbound.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ScheduledTasksControllerTest {

    private AutoModeService autoModeService;
    private ScheduleService scheduleService;
    private ScheduledTasksController controller;

    @BeforeEach
    void setUp() {
        autoModeService = mock(AutoModeService.class);
        scheduleService = mock(ScheduleService.class);
        controller = new ScheduledTasksController(autoModeService, scheduleService);
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
    }

    @Test
    void deleteScheduledTaskShouldRejectWhenSchedulesReferenceTask() {
        when(scheduleService.findSchedulesForTarget("scheduled-task-1")).thenReturn(List.of(ScheduleEntry.builder()
                .id("sched-1")
                .type(ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .targetId("scheduled-task-1")
                .build()));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.deleteScheduledTask("scheduled-task-1"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(autoModeService, never()).deleteScheduledTask("scheduled-task-1");
    }

    @Test
    void listScheduledTasksShouldReturnSortedTasksAndFlags() {
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        when(autoModeService.getScheduledTasks()).thenReturn(List.of(
                scheduledTask("task-2", "Second", Instant.parse("2026-01-02T00:00:00Z")),
                scheduledTask("task-1", "First", Instant.parse("2026-01-01T00:00:00Z"))));

        var response = controller.listScheduledTasks().block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().featureEnabled());
        assertTrue(response.getBody().autoModeEnabled());
        assertEquals("task-1", response.getBody().scheduledTasks().get(0).id());
        assertEquals("task-2", response.getBody().scheduledTasks().get(1).id());
    }

    @Test
    void createScheduledTaskShouldNormalizeTierAndReturnCreatedTask() {
        ScheduledTask created = scheduledTask("scheduled-task-1", "Refresh inbox",
                Instant.parse("2026-01-01T00:00:00Z"));
        when(autoModeService.createScheduledTask(
                "Refresh inbox",
                "Read feeds",
                "Summarize",
                "coding",
                true)).thenReturn(created);

        var response = controller.createScheduledTask(new ScheduledTasksController.CreateScheduledTaskRequest(
                "Refresh inbox",
                "Read feeds",
                "Summarize",
                " Coding ",
                true)).block();

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("scheduled-task-1", response.getBody().id());
        assertEquals("Refresh inbox", response.getBody().title());
        verify(autoModeService).createScheduledTask("Refresh inbox", "Read feeds", "Summarize", "coding", true);
    }

    @Test
    void updateScheduledTaskShouldTrimIdAndPassNullableTierPriority() {
        ScheduledTask updated = scheduledTask("scheduled-task-1", "Updated",
                Instant.parse("2026-01-01T00:00:00Z"));
        when(autoModeService.updateScheduledTask(
                "scheduled-task-1",
                "Updated",
                null,
                "Prompt",
                null,
                null)).thenReturn(updated);

        var response = controller.updateScheduledTask(" scheduled-task-1 ",
                new ScheduledTasksController.UpdateScheduledTaskRequest(
                        "Updated",
                        null,
                        "Prompt",
                        null,
                        null))
                .block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Updated", response.getBody().title());
        verify(autoModeService).updateScheduledTask("scheduled-task-1", "Updated", null, "Prompt", null, null);
    }

    @Test
    void deleteScheduledTaskShouldDeleteWhenNoLinkedScheduledTaskScheduleExists() {
        when(scheduleService.findSchedulesForTarget("scheduled-task-1")).thenReturn(List.of(ScheduleEntry.builder()
                .id("legacy-schedule")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId("scheduled-task-1")
                .build()));

        var response = controller.deleteScheduledTask(" scheduled-task-1 ").block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("scheduled-task-1", response.getBody().scheduledTaskId());
        verify(autoModeService).deleteScheduledTask("scheduled-task-1");
    }

    @Test
    void shouldRejectDisabledFeatureNullBodiesInvalidTierAndBlankIds() {
        when(autoModeService.isFeatureEnabled()).thenReturn(false);
        ResponseStatusException disabled = assertThrows(
                ResponseStatusException.class,
                () -> controller.createScheduledTask(new ScheduledTasksController.CreateScheduledTaskRequest(
                        "Title", null, null, null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, disabled.getStatusCode());

        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        assertThrows(ResponseStatusException.class, () -> controller.createScheduledTask(null));
        assertThrows(ResponseStatusException.class, () -> controller.updateScheduledTask("id", null));
        assertThrows(ResponseStatusException.class,
                () -> controller.createScheduledTask(new ScheduledTasksController.CreateScheduledTaskRequest(
                        "Title", null, null, "routing", null)));
        assertThrows(ResponseStatusException.class,
                () -> controller.updateScheduledTask(" ",
                        new ScheduledTasksController.UpdateScheduledTaskRequest(
                                "Title", null, null, null, null)));
    }

    private static ScheduledTask scheduledTask(String id, String title, Instant createdAt) {
        return ScheduledTask.builder()
                .id(id)
                .title(title)
                .description("description")
                .prompt("prompt")
                .reflectionModelTier("coding")
                .reflectionTierPriority(true)
                .legacySourceType("TASK")
                .legacySourceId("legacy-" + id)
                .createdAt(createdAt)
                .build();
    }
}
