# Session-Scoped Delayed Actions Design

Durable delayed execution and proactive follow-up for session-bound work.

> **See also:** [Auto Mode](AUTO_MODE.md), [Configuration Guide](CONFIGURATION.md), [Webhooks Guide](WEBHOOKS.md), [Dashboard Guide](DASHBOARD.md).

## Overview

The bot already supports long-running work, background job orchestration, and proactive delivery through channel adapters. What it does not support well today is a durable "wake this session up later" primitive.

This gap shows up in two common scenarios:

1. A background worker finishes after the current turn has ended, but the assistant cannot proactively continue unless the user sends another message.
2. The user explicitly asks the agent to do something later, for example "remind me in 5 minutes" or "start generating the report at 14:00".

This document proposes a new **session-scoped delayed actions** subsystem that provides:

- durable scheduled wake-ups tied to a logical session
- proactive delivery when the channel and user preferences allow it
- delayed execution through the same per-session queue used by normal inbound traffic
- deterministic direct notifications for simple cases
- a small, pragmatic implementation that fits the current storage-based architecture

## Goals

- Support `REMIND_LATER`, `RUN_LATER`, `NOTIFY_ON_JOB_COMPLETE`, and `POLL_UNTIL_READY`.
- Re-enter the session through the existing shared queue so delayed work does not race user turns.
- Survive process restarts.
- Prevent the assistant from promising follow-up behavior the runtime cannot deliver.
- Resolve relative and timezone-sensitive schedules into exact UTC timestamps before persistence.
- Expose simple management operations for listing and cancelling scheduled actions.

## Non-Goals

- Replacing the existing cron-based Auto Mode scheduler.
- Building a distributed queue or multi-node scheduler.
- Providing exactly-once delivery across unreliable external channels.
- Solving generic workflow orchestration outside the scope of session-bound actions.
- Allowing the model to "remember timers" without creating a durable scheduled record.

## Existing Integration Points

The current codebase already provides most of the primitives needed for a clean design:

- [`SessionRunCoordinator.submit(...)`](../src/main/java/me/golemcore/bot/domain/service/SessionRunCoordinator.java) already routes synthetic messages through the same per-session queue as user traffic.
- [`InternalTurnService`](../src/main/java/me/golemcore/bot/domain/service/InternalTurnService.java) already shows the pattern for invisible internal messages with metadata.
- [`SessionIdentitySupport`](../src/main/java/me/golemcore/bot/domain/service/SessionIdentitySupport.java) already resolves logical conversation identity and transport chat identity.
- [`ChannelPort`](../src/main/java/me/golemcore/bot/port/inbound/ChannelPort.java) already supports proactive outbound delivery.
- [`UserPreferences`](../src/main/java/me/golemcore/bot/domain/model/UserPreferences.java) already contains `notificationsEnabled` and `timezone`.

Two existing components should **not** be reused as the primary implementation:

- [`AutoModeScheduler`](../src/main/java/me/golemcore/bot/auto/AutoModeScheduler.java) is focused on cron-driven auto mode and currently keeps a single global channel registration.
- [`ScheduleService`](../src/main/java/me/golemcore/bot/domain/service/ScheduleService.java) is a cron registry for goal/task automation, not a one-shot per-session wake-up service.

## User-Facing Scenarios

### Reminders

- "Remind me in 5 minutes to check the deployment."
- "At 6 PM tomorrow, ping me about the invoice."

### Delayed execution

- "In 5 minutes, start generating the weekly report."
- "At 14:00, continue processing the uploaded files."

### Background completion follow-up

- "When the video render finishes, send me the file."
- "Poll the external service every 30 seconds and notify me when the export is ready."

### Failure and confirmation handling

- "When the data sync is done, tell me whether it failed or succeeded."
- "If you need confirmation later for a destructive tool call, do not proceed silently."

## Design Principles

### 1. The model may request delayed behavior, but the runtime owns delivery

The assistant must never rely on implicit memory or future turns. If the model says it will follow up later, it must do so only after a durable action has been created and policy checks have passed.

### 2. Session re-entry must use the same queue as user input

Delayed internal turns must be submitted through `SessionRunCoordinator.submit(...)` so they serialize cleanly with user turns.

### 3. Notifications and delayed execution are related but distinct

- Some delayed actions should send a deterministic message or file directly.
- Some delayed actions should wake the LLM and let it continue through a normal turn.

The runtime should choose the minimal path required for the action.

### 4. Time must be resolved before scheduling

Relative or user-local time expressions must be converted into an exact `runAt` timestamp in UTC before storage. User confirmations should echo both the resolved local time and the timezone used.

### 5. Safety is re-evaluated at execution time

A user may explicitly ask for delayed execution, but policy must still be enforced when the action fires. Delayed execution is not a bypass around tool confirmation or channel constraints.

## High-Level Architecture

```text
User / Worker Event
        |
        v
schedule_session_action tool
or job completion event handler
        |
        v
DelayedSessionActionService
        |
        v
durable registry in storage
        |
        v
DelayedSessionActionScheduler
        |
        +-- direct delivery ------> ChannelPort
        |
        +-- internal wake-up -----> SessionRunCoordinator.submit(...)
                                      |
                                      v
                                   AgentLoop
                                      |
                                      v
                              normal response routing
```

## Core Components

### `DelayedSessionAction`

The durable domain object representing a one-shot scheduled action.

Recommended fields:

- `id`
- `channelType`
- `conversationKey`
- `transportChatId`
- `jobId`
- `kind`
- `deliveryMode`
- `status`
- `runAt`
- `leaseUntil`
- `attempts`
- `maxAttempts`
- `dedupeKey`
- `cancelOnUserActivity`
- `createdBy`
- `createdAt`
- `updatedAt`
- `lastError`
- `expiresAt`
- `payload`

### `DelayedSessionActionService`

Responsibilities:

- create actions
- validate schedule inputs
- resolve session identity
- lease due actions
- complete, cancel, retry, or dead-letter actions
- persist the registry atomically
- expose list and cancel operations for commands/UI

### `DelayedSessionActionScheduler`

Responsibilities:

- poll for due actions on a fixed interval
- lease due actions before dispatch
- dispatch direct notifications or synthetic session turns
- reschedule retryable failures
- recover unfinished leases after restart or scheduler crash

### `DelayedActionDispatcher`

Responsibilities:

- choose direct delivery vs internal turn execution
- construct synthetic messages for delayed session re-entry
- attach internal metadata and tracking IDs
- normalize runtime failures into retryable vs terminal categories

### `DelayedActionPolicyService`

Responsibilities:

- decide whether proactive messaging is allowed
- enforce user preferences such as `notificationsEnabled`
- enforce channel capabilities
- guard unsafe delayed execution
- define limits such as max pending actions per session and max delay window

## Action Kinds

### `REMIND_LATER`

Purpose:

- send a reminder message at a given time

Recommended default delivery mode:

- `DIRECT_MESSAGE`

### `RUN_LATER`

Purpose:

- wake the same session later and execute a stored instruction snapshot through the normal agent loop

Recommended default delivery mode:

- `INTERNAL_TURN`

### `NOTIFY_JOB_READY`

Purpose:

- send a status update or artifact when a known background job completes

Recommended default delivery mode:

- `DIRECT_MESSAGE`
- `DIRECT_FILE`
- `INTERNAL_TURN` only if contextual narration is required

### `POLL_JOB_STATUS`

Purpose:

- periodically check an external job when no completion callback exists

Recommended default delivery mode:

- `DIRECT_TOOLLESS_CHECK` or `INTERNAL_TURN`, depending on implementation

### `DELIVERY_RETRY`

Purpose:

- retry a failed proactive notification without losing the original action context

Recommended default delivery mode:

- same as the original action

## Delivery Modes

### `DIRECT_MESSAGE`

Use when:

- the output is deterministic
- no reasoning is needed
- the runtime already knows the final text

Examples:

- "Your export is ready."
- "Reminder: check the deployment."

### `DIRECT_FILE`

Use when:

- the runtime already has the artifact
- no contextual LLM output is needed

Examples:

- sending a generated file to Telegram
- sending a photo or document after background work completes

### `INTERNAL_TURN`

Use when:

- the assistant must reason over current session context
- the instruction should be executed through the normal tool loop
- the follow-up depends on session memory or recent messages

Examples:

- "In 5 minutes, continue working on the draft"
- "When the job is done, inspect the output and decide the next step"

## Data Model

Recommended enums:

```text
DelayedActionKind:
  REMIND_LATER
  RUN_LATER
  NOTIFY_JOB_READY
  POLL_JOB_STATUS
  DELIVERY_RETRY

DelayedActionDeliveryMode:
  DIRECT_MESSAGE
  DIRECT_FILE
  INTERNAL_TURN

DelayedActionStatus:
  SCHEDULED
  LEASED
  COMPLETED
  CANCELLED
  DEAD_LETTER
```

Recommended `payload` shapes:

### `REMIND_LATER`

```json
{
  "text": "Reminder: check the deployment",
  "sourceMessageId": "msg-123"
}
```

### `RUN_LATER`

```json
{
  "instruction": "Start generating the weekly report",
  "requestedBy": "user",
  "sourceMessageId": "msg-456",
  "allowSideEffects": true,
  "originalSummary": "User asked the bot to start the report in 5 minutes"
}
```

### `NOTIFY_JOB_READY`

```json
{
  "jobId": "job-789",
  "message": "The final video is ready.",
  "artifactPath": "artifacts/video/final.mp4",
  "artifactName": "ai-agents-video.mp4"
}
```

### `POLL_JOB_STATUS`

```json
{
  "jobId": "job-789",
  "pollOperation": "video_render_status",
  "retryDelaySeconds": 30
}
```

## Storage Strategy

### Recommended V1 storage

Use a single durable registry file backed by `StoragePort.putTextAtomic(...)`.

Proposed layout:

```text
automation/
└── delayed-actions.json
```

Recommended JSON shape:

```json
{
  "version": 1,
  "updatedAt": "2026-03-19T18:30:00Z",
  "actions": [
    {
      "id": "delay-123",
      "channelType": "telegram",
      "conversationKey": "tg:123:conv-a",
      "transportChatId": "123456789",
      "kind": "RUN_LATER",
      "deliveryMode": "INTERNAL_TURN",
      "status": "SCHEDULED",
      "runAt": "2026-03-19T18:35:00Z",
      "leaseUntil": null,
      "attempts": 0,
      "maxAttempts": 5,
      "dedupeKey": "session:tg:123:runlater:msg-456",
      "cancelOnUserActivity": true,
      "createdAt": "2026-03-19T18:30:00Z",
      "updatedAt": "2026-03-19T18:30:00Z",
      "payload": {
        "instruction": "Start generating the weekly report"
      }
    }
  ]
}
```

### Why a single registry is acceptable here

This repository already uses a storage-driven, single-process design for several runtime registries. A single atomic JSON registry is the simplest implementation that matches the current architecture.

It is acceptable for V1 because:

- pending delayed actions are expected to be low in count
- lease and update operations can be protected with a local service lock
- restart recovery is simple
- operational debugging is straightforward

If action volume later grows into the thousands or multi-process scheduling becomes necessary, the design can migrate to file-per-action or SQLite without changing the public feature surface.

## Scheduling Semantics

### Accepted scheduling forms

The runtime should support:

- `delaySeconds`
- `runAt` in ISO-8601 with timezone

The runtime may later support natural language parsing, but the persisted action must always store the resolved `runAt` in UTC.

### Relative time

For requests like "in 5 minutes", the scheduler tool should prefer `delaySeconds=300`.

### Absolute local time

For requests like "tomorrow at 6 PM", the runtime should resolve the timestamp using the user's preferred timezone from [`UserPreferences.timezone`](../src/main/java/me/golemcore/bot/domain/model/UserPreferences.java).

### Confirmation copy

The assistant should confirm the schedule with exact resolved time, for example:

> Scheduled for 2026-03-19 14:35:00 America/New_York (2026-03-19T18:35:00Z).

This removes ambiguity around "today", "tomorrow", daylight saving time transitions, and timezone assumptions.

## Session Re-Entry Design

`RUN_LATER` and some contextual follow-ups should create a synthetic internal `Message` and submit it through `SessionRunCoordinator.submit(...)`.

Recommended message metadata:

- `ContextAttributes.MESSAGE_INTERNAL = true`
- `ContextAttributes.MESSAGE_INTERNAL_KIND = "delayed_action"`
- `ContextAttributes.TURN_QUEUE_KIND = ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP`
- `ContextAttributes.TRANSPORT_CHAT_ID = transportChatId`
- `ContextAttributes.CONVERSATION_KEY = conversationKey`
- new metadata: `delayed.action.id`
- new metadata: `delayed.action.kind`
- new metadata: `delayed.action.run_at`

Recommended synthetic message content:

```text
[DELAYED ACTION]
Kind: RUN_LATER
Scheduled at: 2026-03-19T18:35:00Z
Instruction: Start generating the weekly report.

This is a scheduled internal wake-up, not a fresh user message.
Continue using the current session state and respond normally.
```

This keeps the turn compatible with the current loop while making the origin explicit.

## Direct Delivery Design

When the runtime already knows what to send, it should not wake the LLM.

Examples:

- reminder text
- "job completed" status text
- sending a finished artifact

Advantages:

- cheaper
- faster
- less fragile
- no risk of the model rewriting deterministic system messages

## Policy Model

### Proactive notification policy

The runtime should only promise proactive follow-up when all of the following are true:

- delayed actions are enabled globally
- the channel supports outbound proactive delivery
- the user has `notificationsEnabled = true`
- the specific delivery mode is supported by the channel

Current implementation note:

- the `webhook` channel is explicitly unsupported for delayed actions

### Delayed execution policy

`RUN_LATER` is more sensitive than reminders. The runtime should distinguish:

- passive delayed work: reminder messages, status notifications
- active non-destructive work: internal analysis, background generation, plan continuation
- sensitive work: anything likely to trigger destructive or confirmation-gated tools

Recommended rule:

- allow passive delayed work
- allow active non-destructive delayed work
- do not silently bypass [`ToolConfirmationPolicy`](../src/main/java/me/golemcore/bot/domain/service/ToolConfirmationPolicy.java)

If a delayed internal turn later attempts a confirmation-gated tool call and no live confirmation path exists, the turn should stop and send a follow-up asking the user to confirm explicitly.

### Activity cancellation policy

Some actions should be cancelled if the user becomes active before they fire.

Examples:

- reminders related to a topic the user has already reopened
- stale "follow up in 5 minutes" nudges

Recommended field:

- `cancelOnUserActivity`

Recommended behavior:

- on each new user message, cancel matching delayed actions for the same session when configured to do so

## Tool Surface

### `schedule_session_action`

Add a new LLM-facing tool for all delayed scheduling requests.

Recommended schema:

```json
{
  "type": "object",
  "required": ["action_kind"],
  "properties": {
    "action_kind": {
      "type": "string",
      "enum": [
        "remind_later",
        "run_later",
        "notify_job_ready",
        "poll_job_status"
      ]
    },
    "delay_seconds": {
      "type": "integer",
      "minimum": 1,
      "description": "Relative delay in seconds."
    },
    "run_at": {
      "type": "string",
      "description": "Absolute ISO-8601 timestamp with timezone."
    },
    "instruction": {
      "type": "string",
      "description": "Instruction snapshot for delayed execution."
    },
    "message": {
      "type": "string",
      "description": "Deterministic reminder or notification text."
    },
    "job_id": {
      "type": "string"
    },
    "artifact_path": {
      "type": "string"
    },
    "artifact_name": {
      "type": "string"
    },
    "cancel_on_user_activity": {
      "type": "boolean"
    },
    "max_attempts": {
      "type": "integer",
      "minimum": 1
    },
    "dedupe_key": {
      "type": "string"
    }
  }
}
```

Recommended execution behavior:

- reject requests that provide neither `delay_seconds` nor `run_at`
- reject requests that provide both unless explicitly allowed
- resolve and validate the final UTC timestamp
- enforce max delay limits
- create the durable action
- return the created `actionId` and resolved `runAt`

Recommended tool result:

```json
{
  "actionId": "delay-123",
  "kind": "run_later",
  "resolvedRunAt": "2026-03-19T18:35:00Z",
  "deliveryMode": "INTERNAL_TURN"
}
```

### Optional management tools

Add later if needed:

- `list_scheduled_actions`
- `cancel_scheduled_action`
- `run_scheduled_action_now`

## Command Surface

Do not overload `/schedule`, which already means cron-based Auto Mode scheduling.

Recommended new commands:

- `/later list`
- `/later cancel <action_id>`
- `/later now <action_id>`

These commands should operate on the delayed action registry, not Auto Mode schedules.

## Runtime Flow

### Flow A: user asks for delayed execution

1. User says: "In 5 minutes, start generating the weekly report."
2. The model calls `schedule_session_action(action_kind=run_later, delay_seconds=300, ...)`.
3. The tool creates a durable `RUN_LATER` action.
4. The assistant confirms the exact scheduled time.
5. When due, the scheduler submits a synthetic internal turn through `SessionRunCoordinator.submit(...)`.
6. The turn executes normally through the agent loop.

### Flow B: background job completes

1. A worker finishes `job-789`.
2. The worker publishes a completion event or calls a small completion handler.
3. The handler creates an immediate `NOTIFY_JOB_READY` action with `runAt=now`.
4. The scheduler leases the action and dispatches it.
5. If the result is deterministic, it goes directly through `ChannelPort`.
6. If the result needs interpretation, it becomes an internal turn.

### Flow C: external system requires polling

1. The initial request creates `POLL_JOB_STATUS`.
2. The scheduler runs the poll action.
3. If the job is not ready, the action is rescheduled by updating `runAt`.
4. If the job is ready, the poll action completes and a `NOTIFY_JOB_READY` action is created.

## State Machine

```text
SCHEDULED
  |
  +-- due + leased ----------> LEASED
                                |
                                +-- success ----------> COMPLETED
                                |
                                +-- retryable error --> SCHEDULED (attempts++, runAt = retryAt)
                                |
                                +-- max attempts -----> DEAD_LETTER
                                |
                                +-- cancelled --------> CANCELLED
```

### Lease handling

Use `leaseUntil` so the scheduler can recover actions left mid-dispatch after process crash or abrupt shutdown.

Recommended rules:

- only lease actions whose `status = SCHEDULED`
- only recover expired leases after `leaseUntil < now`
- keep lease durations comfortably above normal dispatch time

## Retry Strategy

Recommended defaults:

- direct message failure: retry
- temporary channel outage: retry
- missing channel adapter: retry a few times, then dead-letter
- invalid payload: terminal failure
- missing artifact path: terminal failure
- policy denial: terminal failure

Suggested backoff:

- attempt 1: +30s
- attempt 2: +2m
- attempt 3: +10m
- attempt 4: +1h

## Runtime Configuration

Recommended new config block:

```yaml
delayedActions:
  enabled: true
  tickSeconds: 1
  maxPendingPerSession: 3
  maxDelay: "P30D"
  defaultMaxAttempts: 4
  leaseDuration: "PT2M"
  retentionAfterCompletion: "P7D"
  allowRunLater: true
```

Recommended behavior:

- disable tool exposure when `enabled = false`
- reject new actions beyond `maxPendingPerSession` (capped at 3 active actions per session)
- keep only the latest active action for each duplicate delayed action identity
- clear active delayed actions for the session when `/stop` is requested
- reject schedules beyond `maxDelay`
- prune old terminal actions after `retentionAfterCompletion`

## Observability

Emit structured logs and runtime events for:

- action created
- action leased
- action dispatched
- action completed
- action retried
- action cancelled
- action dead-lettered

Recommended event fields:

- `actionId`
- `kind`
- `deliveryMode`
- `channelType`
- `conversationKey`
- `jobId`
- `attempt`
- `runAt`
- `delayMs`
- `result`

## Edge Cases

### Duplicate scheduling

The same user request or completion event may arrive twice. Use `dedupeKey` to collapse duplicates safely.

### User activity before execution

If `cancelOnUserActivity = true`, matching scheduled reminders should be cancelled when the user sends another message in the same session.

### Session identity drift

The stored `transportChatId` may become stale. The dispatcher should prefer current session identity when it can be resolved, and fall back to stored metadata only when needed.

### Channel cannot send first

The assistant must not promise automatic follow-up when the channel or deployment cannot proactively send messages. The action may still be stored, but the user-facing response must be honest about delivery behavior.

### Delayed action becomes unsafe

By the time `RUN_LATER` fires, the required work may now need confirmation or may no longer be valid. The turn should stop safely rather than forcing execution.

### Process restarts

The scheduler must reload the registry at startup and recover expired leases.

### Daylight saving time transitions

The stored `runAt` must already be in UTC. Local timezone math should happen only at schedule creation time.

## Rollout Plan

### Phase 1

- Add delayed action registry and scheduler
- Support `REMIND_LATER`
- Support `NOTIFY_JOB_READY` direct text delivery
- Add `/later list` and `/later cancel`

### Phase 2

- Add `RUN_LATER`
- Submit scheduled internal turns through `SessionRunCoordinator.submit(...)`
- Add metadata and context handling for delayed internal messages

### Phase 3

- Add `POLL_JOB_STATUS`
- Add direct file delivery
- Add delivery retry logic and dead-letter inspection

### Phase 4

- Add dashboard visibility and controls
- Add optional management tools for the LLM

## Testing Strategy

### Unit tests

- timestamp resolution and validation
- duplicate suppression with `dedupeKey`
- retry scheduling
- cancellation on user activity
- policy denial behavior

### Scheduler tests

- due action leasing
- expired lease recovery
- retryable vs terminal failure transitions
- no double dispatch when the same action is observed twice

### Session integration tests

- delayed internal turn enters `SessionRunCoordinator` and respects queue ordering
- delayed turn does not bypass a running user turn
- internal metadata is propagated into runtime context

### Delivery tests

- direct reminder delivery
- proactive file delivery
- notification disabled in user preferences
- unsupported proactive channel behavior

### Regression tests

- no interference with Auto Mode schedules
- no interference with internal retry queue semantics
- no misleading assistant copy when proactive follow-up is impossible

## Recommended First Implementation

The best first cut for this repository is:

1. Add `DelayedSessionAction`, `DelayedSessionActionService`, and `DelayedSessionActionScheduler`.
2. Persist actions in `automation/delayed-actions.json`.
3. Add `schedule_session_action` with support for `REMIND_LATER` and `RUN_LATER`.
4. Dispatch direct reminders through `ChannelPort`.
5. Dispatch delayed execution through `SessionRunCoordinator.submit(...)`.
6. Add `/later list` and `/later cancel`.

This delivers the core user value without forcing a large platform rewrite.

## Summary

The core idea is straightforward:

- the model may request delayed behavior
- the runtime persists it durably
- the scheduler wakes up later
- deterministic work is delivered directly
- contextual work re-enters the same session queue

That architecture solves both of the important product cases:

- "notify me later"
- "do this later"

without relying on the LLM to remember anything after the current turn ends.
