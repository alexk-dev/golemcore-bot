# Auto Mode Guide

How the bot works autonomously on long-running goals through explicit cron schedules.

> **See also:** [Configuration Guide](CONFIGURATION.md#auto-mode), [Dashboard Guide](DASHBOARD.md#scheduler), [Model Routing](MODEL_ROUTING.md).

## Overview

Auto Mode is now schedule-driven.

The bot does not simply "wake up every N minutes" and pick arbitrary work. Instead:

1. You enable auto mode.
2. You create goals and tasks.
3. You attach cron schedules to a goal or a task.
4. `AutoModeScheduler` evaluates due schedules and submits a synthetic auto message through the shared per-session run coordinator.

```text
Goal / Task
    |
    v
ScheduleEntry (cron + maxExecutions)
    |
    v
AutoModeScheduler
    |
    +-- due schedule? no  -> skip
    +-- due schedule? yes -> build synthetic [AUTO] message
    |
    v
SessionRunCoordinator
    |
    +-- same-session run active? yes -> queue behind current turn
    +-- same-session run active? no  -> start immediately
    |
    v
AgentLoop
    |
    +-- ContextBuildingSystem injects goals/tasks/diary
    +-- Tool loop executes normally
    +-- Goal updates + diary writes happen through goal_management
```

The scheduler currently evaluates due schedules every second. The legacy `autoMode.tickIntervalSeconds` field still exists in runtime config, but the backend normalizes execution to a 1-second polling loop.

## Core Concepts

### Goals

A goal is the top-level objective.

- Stored in `auto/goals.json`
- Max active goals: `autoMode.maxGoals` (default `3`)
- Status: `ACTIVE`, `COMPLETED`, `PAUSED`, `CANCELLED`

### Tasks

Tasks are ordered work items inside a goal.

- Stored inside the parent goal
- Status: `PENDING`, `IN_PROGRESS`, `COMPLETED`, `FAILED`, `SKIPPED`
- A goal schedule will continue the first pending task by `order`

### Diary

The diary is the continuity log for autonomous work.

- Stored as JSONL under `auto/diary/YYYY-MM-DD.jsonl`
- Types: `THOUGHT`, `PROGRESS`, `OBSERVATION`, `DECISION`, `ERROR`
- Recent diary entries are injected into the auto-mode prompt

### Schedules

Schedules are first-class runtime objects stored in `auto/schedules.json`.

Each `ScheduleEntry` contains:

- `id`
- `type`: `GOAL` or `TASK`
- `targetId`
- `cronExpression`
- `enabled`
- `maxExecutions`
- `executionCount`
- `lastExecutedAt`
- `nextExecutionAt`

Important behavior:

- `maxExecutions = -1` means unlimited runs
- exhausted schedules are automatically disabled
- schedules are evaluated in UTC using Spring cron parsing

## How Execution Works

`AutoModeScheduler` runs a background daemon thread and checks due schedules.

Scheduled auto messages no longer bypass normal turn orchestration. They are submitted to `SessionRunCoordinator`, which means:

- auto runs and human inbounds share the same per-session queue
- a long-running auto turn no longer races a same-session user message by entering `AgentLoop` directly
- scheduler code can still await completion of the submitted run and apply `taskTimeLimitMinutes`

### GOAL schedule

When a goal schedule fires:

1. If the goal is missing or not `ACTIVE`, it is skipped.
2. If the goal has pending tasks, the scheduler sends:

```text
[AUTO] Continue working on task: <task title> (goal: <goal title>, goal_id: <goalId>)
```

3. If the goal has no tasks yet, the scheduler sends:

```text
[AUTO] Plan tasks for goal: <goal title> (goal_id: <goalId>)
```

4. If all tasks are already done, nothing is dispatched.

### TASK schedule

When a task schedule fires:

1. The scheduler finds the parent goal.
2. If the task is already `COMPLETED` or `SKIPPED`, it is skipped.
3. Otherwise it sends:

```text
[AUTO] Work on task: <task title> (goal: <goal title>, task_id: <taskId>)
```

### Timeout and accounting

Each due schedule is processed with `autoMode.taskTimeLimitMinutes` as the hard timeout.

After the attempt:

- successful or failed processing still records an execution
- exhausted schedules are disabled automatically
- `nextExecutionAt` is recalculated from the cron expression

## Dashboard and Commands

### Dashboard Scheduler

`/dashboard/scheduler` is the easiest way to manage schedules.

It uses:

- `GET /api/scheduler`
- `POST /api/scheduler/schedules`
- `DELETE /api/scheduler/schedules/{scheduleId}`

The dashboard offers simplified schedule shapes:

- `daily`
- `weekdays`
- `weekly`
- `custom`

These are converted to cron expressions on the backend.

### Slash Commands

Auto mode and schedules can also be managed from chat:

- `/auto`
- `/auto on`
- `/auto off`
- `/goals`
- `/goal <description>`
- `/tasks`
- `/diary [N]`
- `/schedule help`
- `/schedule goal <goal_id> <cron> [repeat_count]`
- `/schedule task <task_id> <cron> [repeat_count]`
- `/schedule list`
- `/schedule delete <schedule_id>`

Examples:

```text
/schedule goal abc123 0 9 * * MON-FRI
/schedule task xyz789 */30 * * * *
```

The Telegram auto-management menu also exposes schedules and now supports guided schedule creation, including custom time and execution limits.

## Goal Management Tool

The `goal_management` tool remains the LLM-facing control surface for autonomous work.

Key operations:

- `create_goal`
- `list_goals`
- `plan_tasks`
- `update_task_status`
- `write_diary`
- `complete_goal`

Auto mode still depends on this tool for:

- task planning
- task status transitions
- diary continuity
- milestone completion callbacks

## Storage Layout

All auto mode data lives under `auto/` inside the workspace:

```text
auto/
├── state.json
├── goals.json
├── schedules.json
└── diary/
    ├── 2026-03-08.jsonl
    └── ...
```

### `state.json`

Simple enabled/disabled state persisted by `AutoModeService`.

```json
{"enabled": true}
```

### `goals.json`

JSON array of goals with embedded tasks.

### `schedules.json`

JSON array of `ScheduleEntry` records. Goals or tasks without schedules are never auto-run.

### `diary/*.jsonl`

One JSON object per line, split by UTC date.

## Configuration

Runtime config:

```json
{
  "autoMode": {
    "enabled": false,
    "tickIntervalSeconds": 1,
    "taskTimeLimitMinutes": 10,
    "autoStart": true,
    "maxGoals": 3,
    "modelTier": "default",
    "notifyMilestones": true
  },
  "tools": {
    "goalManagementEnabled": true
  }
}
```

Field notes:

1. `enabled`: feature flag for the scheduler/runtime.
2. `tickIntervalSeconds`: legacy persisted field; the scheduler currently polls every second.
3. `taskTimeLimitMinutes`: timeout per due schedule execution.
4. `autoStart`: enable auto mode state automatically on startup if the feature is enabled.
5. `maxGoals`: guardrail for concurrently active goals.
6. `modelTier`: preferred tier for auto messages when no tier is already assigned.
7. `notifyMilestones`: send completion notifications to the registered channel.

## Pipeline Integration

Auto messages enter the normal agent loop through `SessionRunCoordinator`, with extra metadata:

- `auto.mode = true`
- `auto.run.kind`
- `auto.goal.id`
- `auto.task.id`

Important pipeline effects:

| Order | System | Auto mode behavior |
|-------|--------|--------------------|
| 20 | `ContextBuildingSystem` | Injects goals, tasks, diary and sets tier from `autoMode.modelTier` |
| 25 | `DynamicTierSystem` | Can still upgrade to `coding` if work becomes code-heavy |
| 30 | `ToolLoopExecutionSystem` | Executes normally, including retries/runtime events |
| 50 | `MemoryPersistSystem` | Persists the autonomous exchange |
| 60 | `ResponseRoutingSystem` | Sends output to the registered channel when one exists |

## Milestone Notifications

When `notifyMilestones` is enabled, completed tasks and goals send channel notifications through the scheduler's registered channel:

```text
🤖 Task completed: Configure Docker Compose
🤖 Goal completed: Deploy bot to production
```

If no channel is registered, autonomous work can still run, but milestone delivery is skipped.

## Practical Flow

Typical sequence:

1. Enable auto mode with `/auto on`.
2. Create a goal with `/goal ...`.
3. Add tasks manually or let the first goal schedule trigger planning.
4. Create schedules from `/dashboard/scheduler` or `/schedule ...`.
5. Let the scheduler drive goal/task execution over time.

Without schedules, auto mode stores goals but does not execute them automatically.
