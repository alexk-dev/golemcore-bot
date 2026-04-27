package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.domain.service.AutoRunHistoryService;
import me.golemcore.bot.domain.service.StringValueSupport;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Scheduler run-history endpoints for dashboard troubleshooting.
 */
@RestController
@RequestMapping("/api/scheduler")
public class SchedulerRunsController {

    private final AutoRunHistoryService autoRunHistoryService;

    public SchedulerRunsController(AutoRunHistoryService autoRunHistoryService) {
        this.autoRunHistoryService = autoRunHistoryService;
    }

    @GetMapping("/runs")
    public Mono<ResponseEntity<RunListResponse>> listRuns(
            @RequestParam(required = false) String scheduleId,
            @RequestParam(required = false) String goalId,
            @RequestParam(required = false) String taskId,
            @RequestParam(defaultValue = "20") int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, 100));
        List<AutoRunHistoryService.RunSummary> summaries = autoRunHistoryService.listRuns(
                scheduleId,
                goalId,
                taskId,
                normalizedLimit);
        return Mono.just(ResponseEntity.ok(new RunListResponse(summaries)));
    }

    @GetMapping("/runs/{runId}")
    public Mono<ResponseEntity<AutoRunHistoryService.RunDetail>> getRun(@PathVariable String runId) {
        if (StringValueSupport.isBlank(runId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runId is required");
        }

        AutoRunHistoryService.RunDetail detail = autoRunHistoryService.getRun(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        return Mono.just(ResponseEntity.ok(detail));
    }

    public record RunListResponse(List<AutoRunHistoryService.RunSummary> runs) {
    }
}
