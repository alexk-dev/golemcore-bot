package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.service.AutoModeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * REST endpoint for goals and tasks, consumed by the dashboard context panel.
 */
@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class GoalsController {

    private final AutoModeService autoModeService;

    @GetMapping
    public Mono<ResponseEntity<GoalsResponse>> getGoals() {
        boolean featureEnabled = autoModeService.isFeatureEnabled();
        boolean autoModeEnabled = autoModeService.isAutoModeEnabled();

        List<GoalDto> goals = autoModeService.getGoals().stream()
                .map(GoalsController::toDto)
                .toList();

        GoalsResponse response = new GoalsResponse(featureEnabled, autoModeEnabled, goals);
        return Mono.just(ResponseEntity.ok(response));
    }

    private static GoalDto toDto(Goal goal) {
        List<TaskDto> tasks = goal.getTasks().stream()
                .map(t -> new TaskDto(t.getId(), t.getTitle(), t.getStatus().name(), t.getOrder()))
                .toList();

        long completedTasks = goal.getCompletedTaskCount();
        int totalTasks = goal.getTasks().size();

        return new GoalDto(
                goal.getId(),
                goal.getTitle(),
                goal.getDescription(),
                goal.getStatus().name(),
                completedTasks,
                totalTasks,
                tasks);
    }

    record GoalsResponse(boolean featureEnabled, boolean autoModeEnabled, List<GoalDto> goals) {
    }

    record GoalDto(String id, String title, String description, String status,
            long completedTasks, int totalTasks, List<TaskDto> tasks) {
    }

    record TaskDto(String id, String title, String status, int order) {
    }
}
