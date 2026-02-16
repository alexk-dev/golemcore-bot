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
import me.golemcore.bot.domain.service.RuntimeConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 * Configuration: RuntimeConfig (tools.goalManagementEnabled)
 *
 * @see me.golemcore.bot.domain.service.AutoModeService
 * @see me.golemcore.bot.auto.AutoModeScheduler
 */
@Component
@Slf4j
public class GoalManagementTool implements ToolComponent {

    // JSON Schema constants
    private static final String SCHEMA_TYPE = "type";
    private static final String SCHEMA_OBJECT = "object";
    private static final String SCHEMA_STRING = "string";
    private static final String SCHEMA_ARRAY = "array";
    private static final String SCHEMA_PROPERTIES = "properties";
    private static final String SCHEMA_DESCRIPTION = "description";
    private static final String SCHEMA_ENUM = "enum";
    private static final String SCHEMA_ITEMS = "items";
    private static final String SCHEMA_REQUIRED = "required";

    // Parameter names
    private static final String PARAM_OPERATION = "operation";
    private static final String PARAM_GOAL_ID = "goal_id";
    private static final String PARAM_TASK_ID = "task_id";
    private static final String PARAM_TITLE = "title";
    private static final String PARAM_DESCRIPTION = "description";
    private static final String PARAM_STATUS = "status";
    private static final String PARAM_RESULT = "result";
    private static final String PARAM_TASKS = "tasks";
    private static final String PARAM_CONTENT = "content";
    private static final String PARAM_DIARY_TYPE = "diary_type";

    private static final String ERR_MISSING_GOAL_ID = "Missing required parameter: goal_id";

    private final AutoModeService autoModeService;
    private final RuntimeConfigService runtimeConfigService;

    private Consumer<MilestoneEvent> milestoneCallback;

    public GoalManagementTool(RuntimeConfigService runtimeConfigService, AutoModeService autoModeService) {
        this.runtimeConfigService = runtimeConfigService;
        this.autoModeService = autoModeService;
        log.info("GoalManagementTool initialized");
    }

    public void setMilestoneCallback(Consumer<MilestoneEvent> callback) {
        this.milestoneCallback = callback;
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigService.isGoalManagementEnabled();
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("goal_management")
                .description("""
                        Manage goals, tasks, and diary for autonomous work mode.
                        Operations: create_goal, list_goals, plan_tasks, update_task_status, write_diary, \
                        complete_goal, delete_goal, delete_task, clear_completed.
                        """)
                .inputSchema(Map.of(
                        SCHEMA_TYPE, SCHEMA_OBJECT,
                        SCHEMA_PROPERTIES, buildProperties(),
                        SCHEMA_REQUIRED, List.of(PARAM_OPERATION)))
                .build();
    }

    private static Map<String, Object> buildProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put(PARAM_OPERATION, Map.of(
                SCHEMA_TYPE, SCHEMA_STRING,
                SCHEMA_ENUM, List.of("create_goal", "list_goals", "plan_tasks",
                        "update_task_status", "write_diary", "complete_goal",
                        "delete_goal", "delete_task", "clear_completed"),
                SCHEMA_DESCRIPTION, "Operation to perform"));
        props.put(PARAM_GOAL_ID,
                Map.of(SCHEMA_TYPE, SCHEMA_STRING, SCHEMA_DESCRIPTION, "Goal ID (for task operations)"));
        props.put(PARAM_TASK_ID,
                Map.of(SCHEMA_TYPE, SCHEMA_STRING, SCHEMA_DESCRIPTION, "Task ID (for update_task_status)"));
        props.put(PARAM_TITLE, Map.of(SCHEMA_TYPE, SCHEMA_STRING, SCHEMA_DESCRIPTION, "Title for goal or task"));
        props.put(PARAM_DESCRIPTION,
                Map.of(SCHEMA_TYPE, SCHEMA_STRING, SCHEMA_DESCRIPTION, "Description for goal or task"));
        props.put(PARAM_STATUS, Map.of(SCHEMA_TYPE, SCHEMA_STRING, SCHEMA_DESCRIPTION,
                "New status for task (PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED)"));
        props.put(PARAM_RESULT, Map.of(SCHEMA_TYPE, SCHEMA_STRING, SCHEMA_DESCRIPTION, "Completion notes for task"));
        props.put(PARAM_TASKS,
                Map.of(SCHEMA_TYPE, SCHEMA_ARRAY, SCHEMA_DESCRIPTION,
                        "Array of {title, description} objects for plan_tasks",
                        SCHEMA_ITEMS, Map.of(SCHEMA_TYPE, SCHEMA_OBJECT, SCHEMA_PROPERTIES, Map.of(
                                PARAM_TITLE, Map.of(SCHEMA_TYPE, SCHEMA_STRING, SCHEMA_DESCRIPTION, "Task title"),
                                PARAM_DESCRIPTION,
                                Map.of(SCHEMA_TYPE, SCHEMA_STRING, SCHEMA_DESCRIPTION, "Task description")))));
        props.put(PARAM_CONTENT, Map.of(SCHEMA_TYPE, SCHEMA_STRING, SCHEMA_DESCRIPTION, "Diary entry content"));
        props.put(PARAM_DIARY_TYPE,
                Map.of(SCHEMA_TYPE, SCHEMA_STRING, SCHEMA_DESCRIPTION,
                        "Diary type: THOUGHT, PROGRESS, OBSERVATION, DECISION, ERROR"));
        return props;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("[GoalManagement] Execute: {}", parameters);

            if (!isEnabled()) {
                return ToolResult.failure("Goal management tool is disabled");
            }

            try {
                String operation = (String) parameters.get(PARAM_OPERATION);

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
                case "delete_goal" -> deleteGoal(parameters);
                case "delete_task" -> deleteTask(parameters);
                case "clear_completed" -> clearCompleted();
                default -> ToolResult.failure("Unknown operation: " + operation);
                };
            } catch (RuntimeException e) {
                log.error("[GoalManagement] Error: {}", e.getMessage(), e);
                return ToolResult.failure("Error: " + e.getMessage());
            }
        });
    }

    private ToolResult createGoal(Map<String, Object> params) {
        String title = (String) params.get(PARAM_TITLE);
        String description = (String) params.get(PARAM_DESCRIPTION);

        if (title == null || title.isBlank()) {
            return ToolResult.failure("Missing required parameter: title");
        }

        try {
            Goal goal = autoModeService.createGoal(title, description);
            return ToolResult.success("Goal created: " + goal.getTitle() + " (id: " + goal.getId() + ")",
                    Map.of(PARAM_GOAL_ID, goal.getId(), PARAM_TITLE, goal.getTitle()));
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
        String goalId = (String) params.get(PARAM_GOAL_ID);
        if (goalId == null || goalId.isBlank()) {
            return ToolResult.failure(ERR_MISSING_GOAL_ID);
        }

        Object tasksObj = params.get(PARAM_TASKS);
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
            String title = (String) taskDef.get(PARAM_TITLE);
            String description = (String) taskDef.get(PARAM_DESCRIPTION);

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
        String goalId = (String) params.get(PARAM_GOAL_ID);
        String taskId = (String) params.get(PARAM_TASK_ID);
        String statusStr = (String) params.get(PARAM_STATUS);
        String result = (String) params.get(PARAM_RESULT);

        if (goalId == null || goalId.isBlank()) {
            return ToolResult.failure(ERR_MISSING_GOAL_ID);
        }
        if (taskId == null || taskId.isBlank()) {
            return ToolResult.failure("Missing required parameter: task_id");
        }
        if (statusStr == null || statusStr.isBlank()) {
            return ToolResult.failure("Missing required parameter: status");
        }

        AutoTask.TaskStatus status;
        try {
            status = AutoTask.TaskStatus.valueOf(statusStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("Invalid status: " + statusStr +
                    ". Valid: PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED");
        }

        autoModeService.updateTaskStatus(goalId, taskId, status, result);

        Consumer<MilestoneEvent> callback = milestoneCallback;
        if (status == AutoTask.TaskStatus.COMPLETED && callback != null) {
            autoModeService.getGoal(goalId).ifPresent(goal -> {
                String taskTitle = goal.getTasks().stream()
                        .filter(t -> t.getId().equals(taskId))
                        .map(AutoTask::getTitle)
                        .findFirst()
                        .orElse("unknown");
                String message = String.format("Task completed: %s%n(Goal: %s — %d/%d done)",
                        taskTitle, goal.getTitle(),
                        goal.getCompletedTaskCount(), goal.getTasks().size());
                callback.accept(new MilestoneEvent(message));
            });
        }

        return ToolResult.success("Task status updated to " + status);
    }

    private ToolResult writeDiary(Map<String, Object> params) {
        String content = (String) params.get(PARAM_CONTENT);
        String diaryTypeStr = (String) params.get(PARAM_DIARY_TYPE);

        if (content == null || content.isBlank()) {
            return ToolResult.failure("Missing required parameter: content");
        }

        DiaryEntry.DiaryType type = DiaryEntry.DiaryType.THOUGHT;
        if (diaryTypeStr != null && !diaryTypeStr.isBlank()) {
            try {
                type = DiaryEntry.DiaryType.valueOf(diaryTypeStr.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                log.debug("Invalid diary type '{}', defaulting to THOUGHT", diaryTypeStr);
            }
        }

        String goalId = (String) params.get(PARAM_GOAL_ID);
        String taskId = (String) params.get(PARAM_TASK_ID);

        autoModeService.writeDiary(DiaryEntry.builder()
                .type(type)
                .content(content)
                .goalId(goalId)
                .taskId(taskId)
                .build());

        return ToolResult.success("Diary entry written.");
    }

    private ToolResult completeGoal(Map<String, Object> params) {
        String goalId = (String) params.get(PARAM_GOAL_ID);
        if (goalId == null || goalId.isBlank()) {
            return ToolResult.failure(ERR_MISSING_GOAL_ID);
        }

        autoModeService.completeGoal(goalId);

        Consumer<MilestoneEvent> callback = milestoneCallback;
        if (callback != null) {
            autoModeService.getGoal(goalId).ifPresent(goal -> {
                String message = "Goal completed: " + goal.getTitle();
                callback.accept(new MilestoneEvent(message));
            });
        }

        return ToolResult.success("Goal marked as completed.");
    }

    private ToolResult deleteGoal(Map<String, Object> params) {
        String goalId = (String) params.get(PARAM_GOAL_ID);
        if (goalId == null || goalId.isBlank()) {
            return ToolResult.failure(ERR_MISSING_GOAL_ID);
        }

        autoModeService.deleteGoal(goalId);
        return ToolResult.success("Goal deleted: " + goalId);
    }

    private ToolResult deleteTask(Map<String, Object> params) {
        String goalId = (String) params.get(PARAM_GOAL_ID);
        String taskId = (String) params.get(PARAM_TASK_ID);

        if (goalId == null || goalId.isBlank()) {
            return ToolResult.failure(ERR_MISSING_GOAL_ID);
        }
        if (taskId == null || taskId.isBlank()) {
            return ToolResult.failure("Missing required parameter: task_id");
        }

        autoModeService.deleteTask(goalId, taskId);
        return ToolResult.success("Task deleted: " + taskId + " from goal: " + goalId);
    }

    private ToolResult clearCompleted() {
        int removed = autoModeService.clearCompletedGoals();
        return ToolResult.success("Cleared " + removed + " completed/cancelled goals.");
    }

    public record MilestoneEvent(String message) {
    }
}
