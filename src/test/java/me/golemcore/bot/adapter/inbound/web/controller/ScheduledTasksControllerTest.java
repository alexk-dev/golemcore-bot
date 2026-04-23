package me.golemcore.bot.adapter.inbound.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import me.golemcore.bot.domain.model.ScheduleEntry;
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
}
