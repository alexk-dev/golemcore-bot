# Auto Mode Guide

How the bot works autonomously on long-term goals without user input.

> **See also:** [Configuration Guide](CONFIGURATION.md#auto-mode) for runtime config fields, [Quick Start](QUICKSTART.md) for setup, [Model Routing](MODEL_ROUTING.md) for how model tiers interact with auto mode.

---

## Overview

Auto Mode enables the bot to work independently on long-term goals. The user defines goals, the LLM breaks them into tasks, and a background scheduler periodically triggers the agent loop to make progress — all without continuous user input.

```
User: /auto on
User: /goal "Research and summarize latest AI news"

        ┌─────────────────────────────────┐
        │       AutoModeScheduler         │
        │   (daemon, every N minutes)     │
        └──────────────┬──────────────────┘
                       │ tick()
                       v
        ┌─────────────────────────────────┐
        │   Has active goals + pending    │──── No ──> skip
        │   tasks?                        │
        └──────────────┬──────────────────┘
                       │ Yes
                       v
        ┌─────────────────────────────────┐
        │   Create synthetic message:     │
        │   "[AUTO] Continue working on   │
        │    task: Research AI papers"    │
        │   metadata: {auto.mode: true}   │
        └──────────────┬──────────────────┘
                       │
                       v
        ┌─────────────────────────────────┐
        │         AgentLoop               │
        │   (normal pipeline execution)   │
        │                                 │
        │   System prompt includes:       │
        │   - Active goals + progress     │
        │   - Current task details        │
        │   - Recent diary entries        │
        │   - Autonomous work instructions│
        └──────────────┬──────────────────┘
                       │
                       v
        ┌─────────────────────────────────┐
        │   LLM uses tools:              │
        │   - goal_management (tasks,     │
        │     diary, status updates)      │
        │   - filesystem, shell, browser  │
        │   - brave_search, etc.          │
        └──────────────┬──────────────────┘
                       │
                       v
        ┌─────────────────────────────────┐
        │   Milestone notifications       │
        │   sent to user's Telegram       │
        │   "🤖 Task completed: ..."       │
        └─────────────────────────────────┘
```

---

## Core Concepts

### Goals

A goal represents a high-level objective the bot should work towards autonomously.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID string | Unique identifier |
| `title` | string | Short description |
| `description` | string | Optional detailed description |
| `status` | enum | `ACTIVE`, `COMPLETED`, `PAUSED`, `CANCELLED` |
| `tasks` | list | Ordered list of `AutoTask` objects |
| `createdAt` | Instant | Creation timestamp |
| `updatedAt` | Instant | Last modification timestamp |

**Limits:**
- Maximum concurrent active goals: 3 (configurable via runtime config `autoMode.maxGoals`)
- Goals are stored in `auto/goals.json` as a JSON array

> **Source:** `Goal.java`

### Tasks

Tasks are concrete work items within a goal. The LLM creates them via `plan_tasks` and updates their status as work progresses.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID string | Unique identifier |
| `goalId` | string | Reference to parent goal |
| `title` | string | What needs to be done |
| `description` | string | Optional details |
| `status` | enum | `PENDING`, `IN_PROGRESS`, `COMPLETED`, `FAILED`, `SKIPPED` |
| `result` | string | Completion notes or output |
| `order` | int | Execution priority within the goal (lower = first) |
| `createdAt` | Instant | Creation timestamp |
| `updatedAt` | Instant | Last modification timestamp |

**Limits:**
- Maximum tasks per goal: 20 (currently fixed)
- Tasks are stored inside the parent goal in `auto/goals.json`

**Task selection:** `getNextPendingTask()` returns the first `PENDING` task across all active goals, sorted by goal creation time (oldest first), then by task order within the goal.

> **Source:** `AutoTask.java`, `AutoModeService.java:195-202`

### Diary

The diary is a chronological log of the agent's autonomous work — thoughts, decisions, progress, errors. It provides continuity across ticks and helps the LLM recall what it has already done.

| Field | Type | Description |
|-------|------|-------------|
| `timestamp` | Instant | Entry time |
| `type` | enum | `THOUGHT`, `PROGRESS`, `OBSERVATION`, `DECISION`, `ERROR` |
| `content` | string | Entry text |
| `goalId` | string | Optional reference to goal |
| `taskId` | string | Optional reference to task |

**Storage format:** JSONL (one JSON object per line), split by UTC date:
```
auto/diary/2026-02-07.jsonl
auto/diary/2026-02-06.jsonl
```

**Automatic diary entries:** The service automatically writes `PROGRESS` entries when:
- A task status is updated to `COMPLETED` — records task title and result
- A goal is marked as `COMPLETED` — records goal title

> **Source:** `DiaryEntry.java`, `AutoModeService.java:224-265`

---

## The Tick Cycle

Every N minutes (default 15), `AutoModeScheduler` runs a tick. This is the core autonomous execution loop.

### Tick Decision Tree

```
tick()
  |
  +-- Is auto mode enabled? ── No ──> return
  |
  +-- Yes
  |
  +-- Get active goals
  |     |
  |     +-- Empty? ──> return (nothing to do)
  |     |
  |     +-- Has pending tasks?
  |           |
  |           +-- Yes: "[AUTO] Continue working on task: {title}"
  |           |
  |           +-- No: Any goals without tasks?
  |                 |
  |                 +-- Yes: "[AUTO] Plan tasks for goal: {title} (goal_id: {id})"
  |                 |
  |                 +-- No: return (all tasks done, all goals planned)
  |
  +-- Create synthetic Message:
  |     role: "user"
  |     content: message from above
  |     senderId: "auto"
  |     metadata: {"auto.mode": true}
  |     chatId: registered channel or "auto"
  |
  +-- agentLoop.processMessage(syntheticMessage)
```

> **Source:** `AutoModeScheduler.java:169-230`

### Timeout Protection

Each tick runs asynchronously with a **5-minute timeout**. If the agent loop takes longer (e.g., waiting on slow LLM or tool calls), the tick times out and logs an error. This prevents the scheduler thread from blocking indefinitely and missing subsequent ticks.

```java
CompletableFuture.runAsync(this::processAutoTick)
        .get(5, TimeUnit.MINUTES);
```

### Synthetic Message Identification

Auto-mode messages are marked with `metadata["auto.mode"] = true`. This marker is checked in two places:

1. **`SkillRoutingSystem`** (order=15) — skips skill routing entirely for auto messages. This prevents repeated skill re-selection and uses the default model tier instead.

2. **`ContextBuildingSystem`** (order=20) — when auto mode is detected:
   - Sets `modelTier` to runtime config `autoMode.modelTier` (default: `"default"`) if no tier was assigned
   - Injects auto mode context (goals, tasks, diary) into the system prompt

> **Source:** `SkillRoutingSystem.java:70-82`, `ContextBuildingSystem.java:153-156, 248-254`

---

## System Prompt Injection

When `ContextBuildingSystem` detects an auto-mode message, it calls `AutoModeService.buildAutoContext()` to generate a section injected into the system prompt. This gives the LLM awareness of goals, current task, and recent activity.

### Example Generated Context

```markdown
# Auto Mode

## Active Goals
1. **Deploy bot to production with monitoring** [ACTIVE] (2/5 tasks done)
   Description: Set up Docker, CI/CD pipeline, and Grafana dashboards
2. **Write user documentation** [ACTIVE] (0/0 tasks done)

## Current Task
**Configure Docker Compose for production** (goal: Deploy bot to production with monitoring)
Status: PENDING
Details: Create docker-compose.yml with proper env vars, volume mounts, and health checks

## Recent Diary (last 3)
- [14:30] PROGRESS: Completed task: Create Dockerfile — Multi-stage build, 180MB image
- [14:15] DECISION: Using Jib for Docker builds instead of manual Dockerfile
- [14:00] THOUGHT: Need to check if LightRAG container should be in same compose file

## Instructions
You are in autonomous work mode.
1. Work on the current task above using available tools
2. Use goal_management tool to update task status when done or write diary entries
3. When all tasks for a goal are done, mark the goal as COMPLETED
4. If you need to create new sub-tasks, use plan_tasks operation
5. Be concise and focused — record key findings in diary
```

The number of diary entries included in context is currently capped at 10.

> **Source:** `AutoModeService.java:269-329`

---

## Goal Management Tool

The `goal_management` tool is how the LLM interacts with the auto mode system during autonomous execution. It is a single tool with multiple operations.

### Operations

| Operation | Required Parameters | Optional Parameters | Description |
|-----------|-------------------|-------------------|-------------|
| `create_goal` | `title` | `description` | Create a new goal (respects max goals limit) |
| `list_goals` | — | — | List all goals with status and task counts |
| `plan_tasks` | `goal_id`, `tasks` (array of `{title, description}`) | — | Add tasks to a goal (respects max tasks limit) |
| `update_task_status` | `goal_id`, `task_id`, `status` | `result` | Update task status; triggers milestone on completion |
| `write_diary` | `content` | `diary_type`, `goal_id`, `task_id` | Write a diary entry (default type: `THOUGHT`) |
| `complete_goal` | `goal_id` | — | Mark goal as completed; triggers milestone notification |

### Status Values

**Task statuses:** `PENDING`, `IN_PROGRESS`, `COMPLETED`, `FAILED`, `SKIPPED`

**Goal statuses:** `ACTIVE`, `COMPLETED`, `PAUSED`, `CANCELLED`

**Diary types:** `THOUGHT`, `PROGRESS`, `OBSERVATION`, `DECISION`, `ERROR`

### Milestone Notifications

When a task or goal is completed, the tool triggers a **milestone callback** that sends a notification to the user's Telegram (or registered channel):

```
🤖 Task completed: Configure Docker Compose for production
(Goal: Deploy bot to production with monitoring — 3/5 done)
```

```
🤖 Goal completed: Deploy bot to production with monitoring
```

Notifications are sent via `AutoModeScheduler.sendMilestoneNotification()` using the channel registered when the user ran `/auto on`. Disable via runtime config `autoMode.notifyMilestones=false`.

> **Source:** `GoalManagementTool.java`, `AutoModeScheduler.java:144-167`

---

## Slash Commands

| Command | Description |
|---------|-------------|
| `/auto` | Show current auto mode status (ON/OFF) |
| `/auto on` | Enable auto mode, register channel for notifications |
| `/auto off` | Disable auto mode |
| `/goals` | List all goals with status icons and task progress |
| `/goal <description>` | Create a new goal with the given description as title |
| `/tasks` | List all goals with their tasks and statuses |
| `/diary [N]` | Show last N diary entries (default: 10, max: 50) |

### Status Icons

**Goals:**

| Icon | Status |
|------|--------|
| `▶️` | ACTIVE |
| `✅` | COMPLETED |
| `⏸️` | PAUSED |
| `❌` | CANCELLED |

**Tasks:**

| Icon | Status |
|------|--------|
| `[ ]` | PENDING |
| `[>]` | IN_PROGRESS |
| `[x]` | COMPLETED |
| `[!]` | FAILED |
| `[-]` | SKIPPED |

The `/status` command also includes auto mode information when the feature is enabled: auto mode state (ON/OFF), active goal count, and current task.

> **Source:** `CommandRouter.java`

---

## Storage Layout

All auto mode data is stored under the `auto/` directory within the workspace (`~/.golemcore/workspace/auto/`):

```
auto/
├── state.json                  # {"enabled": true}
├── goals.json                  # JSON array of Goal objects
└── diary/
    ├── 2026-02-07.jsonl        # Today's diary entries
    ├── 2026-02-06.jsonl        # Yesterday's entries
    └── ...
```

### state.json

Simple JSON object with the enabled flag:
```json
{"enabled": true}
```

Persisted whenever auto mode is toggled. Loaded on application startup by the scheduler.

### goals.json

JSON array of all goals (active, completed, cancelled, etc.) with their tasks:

```json
[
  {
    "id": "a1b2c3d4-...",
    "title": "Deploy bot to production",
    "description": "Set up Docker, CI/CD, and monitoring",
    "status": "ACTIVE",
    "tasks": [
      {
        "id": "e5f6a7b8-...",
        "goalId": "a1b2c3d4-...",
        "title": "Create Dockerfile",
        "description": "Multi-stage build with Jib",
        "status": "COMPLETED",
        "result": "180MB image, multi-stage build",
        "order": 0,
        "createdAt": "2026-02-07T14:00:00Z",
        "updatedAt": "2026-02-07T14:30:00Z"
      },
      {
        "id": "c9d0e1f2-...",
        "goalId": "a1b2c3d4-...",
        "title": "Configure Docker Compose",
        "status": "PENDING",
        "order": 1,
        "createdAt": "2026-02-07T14:00:00Z",
        "updatedAt": "2026-02-07T14:00:00Z"
      }
    ],
    "createdAt": "2026-02-07T13:50:00Z",
    "updatedAt": "2026-02-07T14:30:00Z"
  }
]
```

Goals are cached in memory (`volatile List<Goal>`) and lazy-loaded on first access. All mutations write through to disk immediately.

### diary/{date}.jsonl

One JSON object per line, one file per UTC date:

```jsonl
{"timestamp":"2026-02-07T14:00:00Z","type":"THOUGHT","content":"Need to check container dependencies","goalId":"a1b2c3d4-...","taskId":null}
{"timestamp":"2026-02-07T14:15:00Z","type":"DECISION","content":"Using Jib for builds","goalId":"a1b2c3d4-...","taskId":"e5f6a7b8-..."}
{"timestamp":"2026-02-07T14:30:00Z","type":"PROGRESS","content":"Completed task: Create Dockerfile — 180MB image","goalId":"a1b2c3d4-...","taskId":"e5f6a7b8-..."}
```

`getRecentDiary(count)` scans backwards from today through the last 7 days, collecting up to `count` entries in reverse chronological order, then reverses to chronological.

---

## Configuration

Edit `preferences/runtime-config.json`:

```json
{
  "autoMode": {
    "enabled": false,
    "tickIntervalSeconds": 30,
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

> **See:** [Configuration Guide — Auto Mode](CONFIGURATION.md#auto-mode) for a concise reference.

---

## Pipeline Integration

Auto mode integrates with the agent loop pipeline at specific points:

| Order | System | Auto Mode Behavior |
|-------|--------|-------------------|
| 10 | `InputSanitizationSystem` | Processes normally |
| 15 | `SkillRoutingSystem` | **Skips** — detects `metadata["auto.mode"]`, returns without routing |
| 18 | `AutoCompactionSystem` | Processes normally (compacts if context too large) |
| 20 | `ContextBuildingSystem` | **Sets model tier** from runtime config (`autoMode.modelTier`); **injects** goals, tasks, diary into system prompt |
| 25 | `DynamicTierSystem` | Can upgrade to `coding` if auto work triggers code activity |
| 30 | `ToolLoopExecutionSystem` | LLM calls and tool execution; uses the tier set by ContextBuildingSystem (or upgraded by DynamicTierSystem) |
| 50 | `MemoryPersistSystem` | Persists conversation |
| 60 | `ResponseRoutingSystem` | Sends response to channel (or no-ops if channel is "auto") |

---

## Lifecycle

### 1. Application Startup

```
@PostConstruct AutoModeScheduler.init()
    |
    +-- Feature enabled? (autoMode.enabled)
    |     |
    |     +-- No: log "disabled", return
    |     |
    |     +-- Yes:
    |           +-- Register milestone callback on GoalManagementTool
    |           +-- Load state from auto/state.json
    |           +-- Create daemon ScheduledExecutorService
    |           +-- Schedule tick() at fixed rate (intervalMinutes)
    |
    (scheduler running in background)
```

### 2. User Enables Auto Mode

```
User: /auto on
    |
    +-- CommandRouter.handleAuto("on")
    |     +-- autoModeService.enableAutoMode()
    |     |     +-- enabled = true
    |     |     +-- saveState(true) → auto/state.json
    |     +-- autoModeScheduler.registerChannel("telegram", chatId)
    |     +-- Reply: "Auto mode enabled. Working autonomously every 15 minutes."
    |
    (next tick will find enabled=true and process goals)
```

### 3. User Creates a Goal

```
User: /goal "Research AI news"
    |
    +-- CommandRouter.handleGoal("Research AI news")
    |     +-- autoModeService.createGoal(title, null)
    |     |     +-- Check active goal count < maxGoals (3)
    |     |     +-- Create Goal(UUID, title, ACTIVE)
    |     |     +-- Save to auto/goals.json
    |     +-- Reply: "Goal created: Research AI news"
    |
    (goal has no tasks yet — next tick will trigger task planning)
```

### 4. Scheduler Tick — Task Planning

```
tick() [15 minutes later]
    |
    +-- Active goals found, no pending tasks
    +-- Goal "Research AI news" has no tasks
    +-- Create message: "[AUTO] Plan tasks for goal: Research AI news (goal_id: abc123)"
    +-- agentLoop.processMessage(...)
    |
    +-- LLM receives system prompt with:
    |     "## Active Goals
    |      1. Research AI news [ACTIVE] (0/0 tasks done)
    |      ## Instructions
    |      You are in autonomous work mode..."
    |
    +-- LLM calls goal_management tool:
          operation: "plan_tasks"
          goal_id: "abc123"
          tasks: [
            {title: "Search for recent AI papers", description: "..."},
            {title: "Summarize key findings", description: "..."},
            {title: "Write report", description: "..."}
          ]
```

### 5. Scheduler Tick — Task Execution

```
tick() [30 minutes later]
    |
    +-- Next pending task: "Search for recent AI papers"
    +-- Create message: "[AUTO] Continue working on task: Search for recent AI papers"
    +-- agentLoop.processMessage(...)
    |
    +-- LLM receives system prompt with current task details
    +-- LLM calls brave_search, filesystem tools to do research
    +-- LLM calls goal_management:
    |     operation: "update_task_status"
    |     status: "COMPLETED"
    |     result: "Found 5 relevant papers from last week"
    |
    +-- Milestone notification sent:
          "🤖 Task completed: Search for recent AI papers
           (Goal: Research AI news — 1/3 done)"
```

### 6. User Checks Progress

```
User: /tasks
    |
    +-- Goal: Research AI news [ACTIVE]
          [x] Search for recent AI papers
          [ ] Summarize key findings
          [ ] Write report

User: /diary 5
    |
    +-- [14:45] PROGRESS: Completed task: Search for recent AI papers — Found 5 papers
        [14:40] OBSERVATION: ArXiv has 3 relevant papers on LLM routing
        [14:35] THOUGHT: Should focus on papers from last 7 days
        [14:30] DECISION: Using Brave Search for initial paper discovery
        [14:15] PROGRESS: Started working on goal: Research AI news
```

### 7. Application Shutdown

```
@PreDestroy AutoModeScheduler.shutdown()
    |
    +-- Cancel scheduled tick task (non-interrupting)
    +-- Shutdown executor with 5-second grace period
    +-- Force shutdownNow() if timeout exceeded
    +-- Log "Shut down"
```

---

## Architecture: Key Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `AutoModeService` | `domain.service` | Core service: goals, tasks, diary, state, context building |
| `AutoModeScheduler` | `auto` | Background daemon: tick scheduling, synthetic messages, milestone notifications |
| `GoalManagementTool` | `tools` | LLM tool: create_goal, list_goals, plan_tasks, update_task_status, write_diary, complete_goal |
| `Goal` | `domain.model` | Goal model with status and tasks list |
| `AutoTask` | `domain.model` | Task model with status, order, and result |
| `DiaryEntry` | `domain.model` | Diary entry with type and content |
| `CommandRouter` | `adapter.inbound.command` | Slash commands: /auto, /goals, /goal, /tasks, /diary |
| `ContextBuildingSystem` | `domain.system` | Injects auto context into system prompt, sets model tier |
| `SkillRoutingSystem` | `domain.system` | Skips routing for auto-mode messages |

---

## Debugging

### Log Messages

Auto mode produces detailed logs:

```
[AutoScheduler] Started with interval: 15 minutes
[AutoMode] Enabled
[AutoMode] Created goal 'Research AI news'
[AutoScheduler] Tick: processing auto mode
[AutoScheduler] Sending: [AUTO] Plan tasks for goal: Research AI news (goal_id: abc123)
[AutoMode] Added task 'Search for recent AI papers' to goal 'Research AI news'
[AutoMode] Updated task 'Search for recent AI papers' status to COMPLETED
[AutoScheduler] Sent milestone notification: Task completed: Search for recent AI papers
[AutoMode] Completed goal 'Research AI news'
```

When idle:
```
[AutoScheduler] No active goals, skipping
[AutoScheduler] All goals have tasks and all are done, skipping
```

On errors:
```
[AutoScheduler] Tick timed out after 5 minutes
[AutoScheduler] Tick failed: Connection refused
```

### Useful Commands

- `/status` — shows auto mode state, active goal count, current task
- `/goals` — overview of all goals with progress
- `/tasks` — detailed task list across all goals
- `/diary 20` — last 20 diary entries for recent activity log
