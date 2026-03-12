package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.domain.service.AutoRunHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchedulerRunsControllerTest {

    private AutoRunHistoryService autoRunHistoryService;
    private SchedulerRunsController controller;

    @BeforeEach
    void setUp() {
        autoRunHistoryService = mock(AutoRunHistoryService.class);
        controller = new SchedulerRunsController(autoRunHistoryService);
    }

    @Test
    void shouldListRuns() {
        AutoRunHistoryService.RunSummary summary = new AutoRunHistoryService.RunSummary(
                "run-1",
                "web:conv-1",
                "web",
                "conv-1",
                "client-1",
                "sched-1",
                "TASK",
                "task-1",
                "Write docs",
                "goal-1",
                "Launch",
                "task-1",
                "Write docs",
                "COMPLETED",
                2,
                Instant.parse("2026-03-11T10:00:00Z"),
                Instant.parse("2026-03-11T10:00:03Z"));
        when(autoRunHistoryService.listRuns("sched-1", null, null, 20)).thenReturn(List.of(summary));

        StepVerifier.create(controller.listRuns("sched-1", null, null, 20))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SchedulerRunsController.RunListResponse body = response.getBody();
                    assertEquals(1, body.runs().size());
                    assertEquals("run-1", body.runs().get(0).runId());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnRunDetail() {
        AutoRunHistoryService.RunDetail detail = new AutoRunHistoryService.RunDetail(
                "run-1",
                "web:conv-1",
                "web",
                "conv-1",
                "client-1",
                "sched-1",
                "TASK",
                "task-1",
                "Write docs",
                "goal-1",
                "Launch",
                "task-1",
                "Write docs",
                "COMPLETED",
                Instant.parse("2026-03-11T10:00:00Z"),
                Instant.parse("2026-03-11T10:00:03Z"),
                List.of(new AutoRunHistoryService.RunMessage(
                        "m1",
                        "assistant",
                        "done",
                        Instant.parse("2026-03-11T10:00:03Z"),
                        false,
                        false,
                        null,
                        "coding")));
        when(autoRunHistoryService.getRun("run-1")).thenReturn(Optional.of(detail));

        StepVerifier.create(controller.getRun("run-1"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("run-1", response.getBody().runId());
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectBlankRunId() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.getRun(" "));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }
}
