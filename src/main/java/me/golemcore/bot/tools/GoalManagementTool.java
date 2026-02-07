/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Tool for managing goals, tasks, and diary entries in auto mode.
 *
 * <p>
 * This tool enables the autonomous agent to:
 * <ul>
 * <li>Create goals with descriptions
 * <li>List all goals with status
 * <li>Plan tasks for a goal (break down into steps)
 * <li>Update task status (pending/in_progress/completed/failed/skipped)
 * <li>Write diary entries (progress notes, reflections)
 * <li>Complete goals
 * </ul>
 *
 * <p>
 * Operations:
 * <ul>
 * <li>create_goal - Create a new goal
 * <li>list_goals - List all goals
 * <li>plan_tasks - Break down goal into tasks
 * <li>update_task_status - Update task status
 * <li>write_diary - Write diary entry
 * <li>complete_goal - Mark goal as completed
 * </ul>
 *
 * <p>
 * Configuration: {@code bot.tools.goal-management.enabled}
 *
 * @see me.golemcore.bot.domain.service.AutoModeService
 * @see me.golemcore.bot.auto.AutoModeScheduler
 */
@Component
@Slf4j
public class GoalManagementTool implements ToolComponent {

    private final AutoModeService autoModeService;
    private final boolean enabled;

    private Consumer<MilestoneEvent> milestoneCallback;

    public GoalManagementTool(BotProperties properties, AutoModeService autoModeService) {
        this.enabled = properties.getTools().getGoalManagement().isEnabled();
        this.autoModeService = autoModeService;
        log.info("GoalManagementTool enabled: {}", enabled);
    }

    public void setMilestoneCallback(Consumer<MilestoneEvent> callback) {
        this.milestoneCallback = callback;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("goal_management")
                .description("""
                        Manage goals, tasks, and diary for autonomous work mode.
                        Operations: create_goal, list_goals, plan_tasks, update_task_status, write_diary, complete_goal.
                        """)
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", buildProperties(),
                        "required", List.of("operation")))
                .build();
    }

    private static Map<String, Object> buildProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("operation", Map.of(
                "type", "string",
                "enum", List.of("create_goal", "list_goals", "plan_tasks",
                        "update_task_status", "write_diary", "complete_goal"),
                "description", "Operation to perform"));
        props.put("goal_id", Map.of("type", "string", "description", "Goal ID (for task operations)"));
        props.put("task_id", Map.of("type", "string", "description", "Task ID (for update_task_status)"));
        props.put("title", Map.of("type", "string", "description", "Title for goal or task"));
        props.put("description", Map.of("type", "string", "description", "Description for goal or task"));
        props.put("status", Map.of("type", "string", "description",
                "New status for task (PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED)"));
        props.put("result", Map.of("type", "string", "description", "Completion notes for task"));
        props.put("tasks",
                Map.of("type", "array", "description", "Array of {title, description} objects for plan_tasks",
                        "items", Map.of("type", "object", "properties", Map.of(
                                "title", Map.of("type", "string", "description", "Task title"),
                                "description", Map.of("type", "string", "description", "Task description")))));
        props.put("content", Map.of("type", "string", "description", "Diary entry content"));
        props.put("diary_type",
                Map.of("type", "string", "description", "Diary type: THOUGHT, PROGRESS, OBSERVATION, DECISION, ERROR"));
        return props;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("[GoalManagement] Execute: {}", parameters);

            if (!enabled) {
                return ToolResult.failure("Goal management tool is disabled");
            }

            try {
                String operation = (String) parameters.get("operation");

                if (operation == null) {
                    return ToolResult.failure("Missing required parameter: operation");
                }

                return switch (operation) {
                case "create_goal" -> createGoal(parameters);
                case "list_goals" -> listGoals();
                case "plan_tasks" -> planTasks(parameters);
                case "update_task_status" -> updateTaskStatus(parameters);
                case "write_diary" -> writeDiary(parameters);
                case "complete_goal" -> completeGoal(parameters);
                default -> ToolResult.failure("Unknown operation: " + operation);
                };
            } catch (Exception e) {
                log.error("[GoalManagement] Error: {}", e.getMessage(), e);
                return ToolResult.failure("Error: " + e.getMessage());
            }
        });
    }

    private ToolResult createGoal(Map<String, Object> params) {
        String title = (String) params.get("title");
        String description = (String) params.get("description");

        if (title == null || title.isBlank()) {
            return ToolResult.failure("Missing required parameter: title");
        }

        try {
            Goal goal = autoModeService.createGoal(title, description);
            return ToolResult.success("Goal created: " + goal.getTitle() + " (id: " + goal.getId() + ")",
                    Map.of("goal_id", goal.getId(), "title", goal.getTitle()));
        } catch (IllegalStateException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    private ToolResult listGoals() {
        List<Goal> goals = autoModeService.getGoals();
        if (goals.isEmpty()) {
            return ToolResult.success("No goals yet.");
        }

        String list = goals.stream()
                .map(g -> {
                    long completed = g.getCompletedTaskCount();
                    int total = g.getTasks().size();
                    return String.format("- [%s] %s (%d/%d tasks) id:%s",
                            g.getStatus(), g.getTitle(), completed, total, g.getId());
                })
                .collect(Collectors.joining("\n"));

        return ToolResult.success("Goals (" + goals.size() + "):\n" + list);
    }

    @SuppressWarnings("unchecked")
    private ToolResult planTasks(Map<String, Object> params) {
        String goalId = (String) params.get("goal_id");
        if (goalId == null || goalId.isBlank()) {
            return ToolResult.failure("Missing required parameter: goal_id");
        }

        Object tasksObj = params.get("tasks");
        if (tasksObj == null) {
            return ToolResult.failure("Missing required parameter: tasks");
        }

        List<Map<String, Object>> tasksList;
        if (tasksObj instanceof List<?>) {
            tasksList = (List<Map<String, Object>>) tasksObj;
        } else {
            return ToolResult.failure("Parameter 'tasks' must be an array");
        }

        if (tasksList.isEmpty()) {
            return ToolResult.failure("Tasks list is empty");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Added tasks:\n");
        for (int i = 0; i < tasksList.size(); i++) {
            Map<String, Object> taskDef = tasksList.get(i);
            String title = (String) taskDef.get("title");
            String description = (String) taskDef.get("description");

            if (title == null || title.isBlank()) {
                return ToolResult.failure("Task at index " + i + " missing title");
            }

            try {
                AutoTask task = autoModeService.addTask(goalId, title, description, i + 1);
                sb.append(String.format("- %s (id: %s)%n", task.getTitle(), task.getId()));
            } catch (Exception e) {
                return ToolResult.failure("Failed to add task '" + title + "': " + e.getMessage());
            }
        }

        return ToolResult.success(sb.toString());
    }

    private ToolResult updateTaskStatus(Map<String, Object> params) {
        String goalId = (String) params.get("goal_id");
        String taskId = (String) params.get("task_id");
        String statusStr = (String) params.get("status");
        String result = (String) params.get("result");

        if (goalId == null || goalId.isBlank()) {
            return ToolResult.failure("Missing required parameter: goal_id");
        }
        if (taskId == null || taskId.isBlank()) {
            return ToolResult.failure("Missing required parameter: task_id");
        }
        if (statusStr == null || statusStr.isBlank()) {
            return ToolResult.failure("Missing required parameter: status");
        }

        AutoTask.TaskStatus status;
        try {
            status = AutoTask.TaskStatus.valueOf(statusStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("Invalid status: " + statusStr +
                    ". Valid: PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED");
        }

        autoModeService.updateTaskStatus(goalId, taskId, status, result);

        if (status == AutoTask.TaskStatus.COMPLETED && milestoneCallback != null) {
            autoModeService.getGoal(goalId).ifPresent(goal -> {
                String taskTitle = goal.getTasks().stream()
                        .filter(t -> t.getId().equals(taskId))
                        .map(AutoTask::getTitle)
                        .findFirst()
                        .orElse("unknown");
                String message = String.format("Task completed: %s%n(Goal: %s — %d/%d done)",
                        taskTitle, goal.getTitle(),
                        goal.getCompletedTaskCount(), goal.getTasks().size());
                milestoneCallback.accept(new MilestoneEvent(message));
            });
        }

        return ToolResult.success("Task status updated to " + status);
    }

    private ToolResult writeDiary(Map<String, Object> params) {
        String content = (String) params.get("content");
        String diaryTypeStr = (String) params.get("diary_type");

        if (content == null || content.isBlank()) {
            return ToolResult.failure("Missing required parameter: content");
        }

        DiaryEntry.DiaryType type = DiaryEntry.DiaryType.THOUGHT;
        if (diaryTypeStr != null && !diaryTypeStr.isBlank()) {
            try {
                type = DiaryEntry.DiaryType.valueOf(diaryTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                // default to THOUGHT
            }
        }

        String goalId = (String) params.get("goal_id");
        String taskId = (String) params.get("task_id");

        autoModeService.writeDiary(DiaryEntry.builder()
                .type(type)
                .content(content)
                .goalId(goalId)
                .taskId(taskId)
                .build());

        return ToolResult.success("Diary entry written.");
    }

    private ToolResult completeGoal(Map<String, Object> params) {
        String goalId = (String) params.get("goal_id");
        if (goalId == null || goalId.isBlank()) {
            return ToolResult.failure("Missing required parameter: goal_id");
        }

        autoModeService.completeGoal(goalId);

        if (milestoneCallback != null) {
            autoModeService.getGoal(goalId).ifPresent(goal -> {
                String message = "Goal completed: " + goal.getTitle();
                milestoneCallback.accept(new MilestoneEvent(message));
            });
        }

        return ToolResult.success("Goal marked as completed.");
    }

    public record MilestoneEvent(String message) {
    }
}
