package me.golemcore.bot.adapter.inbound.web.controller;

import java.time.Instant;
import java.util.List;
import me.golemcore.bot.application.scheduler.SchedulerFacade;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    private final SchedulerFacade schedulerFacade;

    public SchedulerController(SchedulerFacade schedulerFacade) {
        this.schedulerFacade = schedulerFacade;
    }

    @GetMapping
    public Mono<ResponseEntity<SchedulerStateResponse>> getState() {
        SchedulerFacade.SchedulerStateView state = schedulerFacade.getState();
        return Mono.just(ResponseEntity.ok(toSchedulerStateResponse(state)));
    }

    @PostMapping("/schedules")
    public Mono<ResponseEntity<ScheduleDto>> createSchedule(@RequestBody CreateScheduleRequest request) {
        try {
            SchedulerFacade.ScheduleView created = schedulerFacade.createSchedule(toCreateScheduleCommand(request));
            return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(toScheduleDto(created)));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    @PutMapping("/schedules/{scheduleId}")
    public Mono<ResponseEntity<ScheduleDto>> updateSchedule(
            @PathVariable String scheduleId,
            @RequestBody UpdateScheduleRequest request) {
        try {
            SchedulerFacade.ScheduleView updated = schedulerFacade.updateSchedule(
                    scheduleId,
                    toUpdateScheduleCommand(request));
            return Mono.just(ResponseEntity.ok(toScheduleDto(updated)));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    @DeleteMapping("/schedules/{scheduleId}")
    public Mono<ResponseEntity<DeleteScheduleResponse>> deleteSchedule(@PathVariable String scheduleId) {
        try {
            schedulerFacade.deleteSchedule(scheduleId);
            return Mono.just(ResponseEntity.ok(new DeleteScheduleResponse(scheduleId)));
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    private SchedulerStateResponse toSchedulerStateResponse(SchedulerFacade.SchedulerStateView state) {
        return new SchedulerStateResponse(
                state.featureEnabled(),
                state.autoModeEnabled(),
                state.goals().stream().map(this::toGoalDto).toList(),
                state.standaloneTasks().stream().map(this::toTaskDto).toList(),
                state.schedules().stream().map(this::toScheduleDto).toList(),
                state.reportChannelOptions().stream().map(this::toReportChannelOptionDto).toList());
    }

    private GoalDto toGoalDto(SchedulerFacade.GoalView goal) {
        return new GoalDto(
                goal.id(),
                goal.title(),
                goal.description(),
                goal.prompt(),
                goal.status(),
                goal.completedTasks(),
                goal.totalTasks(),
                goal.tasks().stream().map(this::toTaskDto).toList());
    }

    private TaskDto toTaskDto(SchedulerFacade.TaskView task) {
        return new TaskDto(
                task.id(),
                task.goalId(),
                task.title(),
                task.description(),
                task.prompt(),
                task.status(),
                task.order(),
                task.standalone());
    }

    private ScheduleDto toScheduleDto(SchedulerFacade.ScheduleView entry) {
        return new ScheduleDto(
                entry.id(),
                entry.type(),
                entry.targetId(),
                entry.targetLabel(),
                entry.cronExpression(),
                entry.enabled(),
                entry.clearContextBeforeRun(),
                toScheduleReportDto(entry.report()),
                entry.maxExecutions(),
                entry.executionCount(),
                entry.createdAt(),
                entry.updatedAt(),
                entry.lastExecutedAt(),
                entry.nextExecutionAt());
    }

    private ScheduleReportDto toScheduleReportDto(SchedulerFacade.ScheduleReportView report) {
        if (report == null) {
            return null;
        }
        return new ScheduleReportDto(
                report.channelType(),
                report.chatId(),
                report.webhookUrl(),
                report.webhookBearerToken());
    }

    private ScheduleReportChannelOptionDto toReportChannelOptionDto(
            SchedulerFacade.ScheduleReportChannelOptionView option) {
        return new ScheduleReportChannelOptionDto(option.type(), option.label(), option.suggestedChatId());
    }

    private SchedulerFacade.CreateScheduleCommand toCreateScheduleCommand(CreateScheduleRequest request) {
        if (request == null) {
            return null;
        }
        return new SchedulerFacade.CreateScheduleCommand(
                request.targetType(),
                request.targetId(),
                request.frequency(),
                request.days(),
                request.time(),
                request.maxExecutions(),
                request.mode(),
                request.cronExpression(),
                request.clearContextBeforeRun(),
                toScheduleReportRequest(request.report()));
    }

    private SchedulerFacade.UpdateScheduleCommand toUpdateScheduleCommand(UpdateScheduleRequest request) {
        if (request == null) {
            return null;
        }
        return new SchedulerFacade.UpdateScheduleCommand(
                request.targetType(),
                request.targetId(),
                request.frequency(),
                request.days(),
                request.time(),
                request.maxExecutions(),
                request.mode(),
                request.cronExpression(),
                request.enabled(),
                request.clearContextBeforeRun(),
                toScheduleReportPatchRequest(request.report()));
    }

    private SchedulerFacade.ScheduleReportRequest toScheduleReportRequest(ScheduleReportRequest report) {
        if (report == null) {
            return null;
        }
        return new SchedulerFacade.ScheduleReportRequest(
                report.channelType(),
                report.chatId(),
                report.webhookUrl(),
                report.webhookBearerToken());
    }

    private SchedulerFacade.ScheduleReportPatchRequest toScheduleReportPatchRequest(ScheduleReportPatchRequest report) {
        if (report == null) {
            return null;
        }
        return new SchedulerFacade.ScheduleReportPatchRequest(
                report.operation(),
                toScheduleReportRequest(report.config()));
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    public record CreateScheduleRequest(
            String targetType,
            String targetId,
            String frequency,
            List<Integer> days,
            String time,
            Integer maxExecutions,
            String mode,
            String cronExpression,
            Boolean clearContextBeforeRun,
            ScheduleReportRequest report) {

        public CreateScheduleRequest(
                String targetType,
                String targetId,
                String frequency,
                List<Integer> days,
                String time,
                Integer maxExecutions) {
            this(targetType, targetId, frequency, days, time, maxExecutions,
                    null, null, null, null);
        }

        public CreateScheduleRequest(
                String targetType,
                String targetId,
                String frequency,
                List<Integer> days,
                String time,
                Integer maxExecutions,
                String mode,
                String cronExpression) {
            this(targetType, targetId, frequency, days, time, maxExecutions, mode, cronExpression, null, null);
        }

        public CreateScheduleRequest(
                String targetType,
                String targetId,
                String frequency,
                List<Integer> days,
                String time,
                Integer maxExecutions,
                String mode,
                String cronExpression,
                Boolean clearContextBeforeRun) {
            this(targetType, targetId, frequency, days, time, maxExecutions, mode, cronExpression,
                    clearContextBeforeRun, null);
        }
    }

    public record UpdateScheduleRequest(
            String targetType,
            String targetId,
            String frequency,
            List<Integer> days,
            String time,
            Integer maxExecutions,
            String mode,
            String cronExpression,
            Boolean enabled,
            Boolean clearContextBeforeRun,
            ScheduleReportPatchRequest report) {

        public UpdateScheduleRequest(
                String targetType,
                String targetId,
                String frequency,
                List<Integer> days,
                String time,
                Integer maxExecutions,
                String mode,
                String cronExpression,
                Boolean enabled) {
            this(targetType, targetId, frequency, days, time, maxExecutions, mode, cronExpression, enabled, null,
                    null);
        }

        public UpdateScheduleRequest(
                String targetType,
                String targetId,
                String frequency,
                List<Integer> days,
                String time,
                Integer maxExecutions,
                String mode,
                String cronExpression,
                Boolean enabled,
                Boolean clearContextBeforeRun) {
            this(targetType, targetId, frequency, days, time, maxExecutions, mode, cronExpression, enabled,
                    clearContextBeforeRun, null);
        }
    }

    public record SchedulerStateResponse(
            boolean featureEnabled,
            boolean autoModeEnabled,
            List<GoalDto> goals,
            List<TaskDto> standaloneTasks,
            List<ScheduleDto> schedules,
            List<ScheduleReportChannelOptionDto> reportChannelOptions) {
    }

    public record GoalDto(
            String id,
            String title,
            String description,
            String prompt,
            String status,
            long completedTasks,
            int totalTasks,
            List<TaskDto> tasks) {
    }

    public record TaskDto(
            String id,
            String goalId,
            String title,
            String description,
            String prompt,
            String status,
            int order,
            boolean standalone) {
    }

    public record ScheduleDto(
            String id,
            String type,
            String targetId,
            String targetLabel,
            String cronExpression,
            boolean enabled,
            boolean clearContextBeforeRun,
            ScheduleReportDto report,
            int maxExecutions,
            int executionCount,
            Instant createdAt,
            Instant updatedAt,
            Instant lastExecutedAt,
            Instant nextExecutionAt) {
    }

    public record ScheduleReportDto(
            String channelType,
            String chatId,
            String webhookUrl,
            String webhookBearerToken) {
    }

    public record ScheduleReportRequest(
            String channelType,
            String chatId,
            String webhookUrl,
            String webhookBearerToken) {
    }

    public record ScheduleReportPatchRequest(
            String operation,
            ScheduleReportRequest config) {
    }

    public record ScheduleReportChannelOptionDto(
            String type,
            String label,
            String suggestedChatId) {
    }

    public record DeleteScheduleResponse(String scheduleId) {
    }
}
