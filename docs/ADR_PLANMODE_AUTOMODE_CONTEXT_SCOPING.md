# ADR: Session-Scoped PlanMode and Goal/Task-Scoped AutoMode Context

Status: Proposed  
Date: 2026-02-23  
Owners: Core Platform, Agent Runtime, Dashboard UX

## Context

The multi-session architecture already separates transport identity from logical conversation identity:

1. `transportChatId` is used for delivery.
2. `conversationKey` is used for session history and logical context.
3. Active pointer maps transport identity to active `conversationKey`.
4. Memory retrieval supports scoped session context.

This solved chat routing isolation for Web and Telegram sessions.  
However, runtime mode behavior is still inconsistent with this model:

1. `PlanMode` behaves as a global runtime switch instead of session-scoped state.
2. `AutoMode` currently couples execution context to transport/session inconsistently.
3. Goal-driven execution and standalone tasks do not have explicit, separate memory boundaries.
4. Dashboard plan controls are not fully session-aware in contract and caching semantics.

Without explicit integration, context bleed and UX confusion remain possible even after message routing isolation.

## Problem Statement

We need deterministic and auditable rules for Plan and Auto execution context:

1. `PlanMode` must be strictly isolated per active session.
2. `AutoMode` must support two valid execution patterns:
3. For tasks under one goal, tasks share a common goal context.
4. For standalone tasks, each task is isolated from other tasks.
5. All rules must be consistent across Telegram and Web.
6. No cross-session memory retrieval is allowed unless data was explicitly promoted to `global`.

## Decision Drivers

1. Correctness: no context bleed across sessions.
2. UX predictability: active session defines active plan state.
3. Execution quality: coordinated tasks under one goal can reuse shared intent and constraints.
4. Isolation quality: standalone tasks must not contaminate each other.
5. Operational simplicity: rollout without feature flags.
6. Backward compatibility: preserve existing session/message data and command behavior where possible.

## Non-Goals

1. Redesigning planner algorithms outside context scoping.
2. Introducing a new persistence backend.
3. Replacing Memory V3 extraction/ranking strategy.
4. Building cross-user collaborative shared goals.

## Considered Options

### Option A: Keep PlanMode and AutoMode global

Pros:

1. Lowest implementation effort.

Cons:

1. Violates session isolation goals.
2. Causes unpredictable UX in multi-session usage.

Decision: Rejected.

### Option B: Make both PlanMode and AutoMode session-scoped only

Pros:

1. Strong isolation by default.
2. Simpler than hierarchical model.

Cons:

1. Cannot share context between related tasks under one goal.
2. Forces redundant prompts for goal constraints and history.

Decision: Rejected.

### Option C: Session-scoped PlanMode + hierarchical AutoMode (`goal` and `task`)

Pros:

1. Aligns PlanMode with session UX.
2. Supports collaborative task execution within a goal.
3. Keeps standalone tasks isolated.
4. Compatible with scoped memory model.

Cons:

1. More runtime state and explicit contracts required.
2. Higher test matrix.

Decision: Accepted.

## Decision

### 1) PlanMode Scope

1. PlanMode state is keyed by `SessionIdentity`.
2. Enabling PlanMode in session `A` does not affect session `B`.
3. Plan finalization and plan status APIs are resolved in session scope only.

### 2) AutoMode Scope

AutoMode supports exactly two execution kinds:

1. `GOAL_RUN`: task belongs to a goal and can use shared goal context.
2. `TASK_RUN`: standalone task with isolated task context.

### 3) Memory Scope Hierarchy

Canonical scopes:

1. `global`
2. `session:{channelType}:{conversationKey}`
3. `goal:{channelType}:{conversationKey}:{goalId}`
4. `task:{taskId}`

### 4) Retrieval Policies

1. For `GOAL_RUN`: `task -> goal -> session -> global`.
2. For `TASK_RUN`: `task -> session -> global`.
3. Session scope is always bound to current `SessionIdentity`.
4. No retrieval from other session scopes is allowed.

### 5) Persist Policies

1. Task intermediate state is written to `task` scope.
2. Goal-level decisions and shared constraints are written to `goal` scope.
3. Session conversation memory remains in `session` scope.
4. Global memory requires explicit promotion policy.

## Canonical Domain Contracts

### 1) SessionIdentity

```java
public record SessionIdentity(String channelType, String conversationKey) {}
```

Rules:

1. `conversationKey` follows `^[a-zA-Z0-9_-]{8,64}$` for new keys.
2. Legacy keys are readable but new invalid keys are not generated.

### 2) AutoExecutionContext

```java
public record AutoExecutionContext(
        SessionIdentity sessionIdentity,
        AutoRunKind runKind,           // GOAL_RUN or TASK_RUN
        String runId,                  // unique scheduler run
        String goalId,                 // nullable for TASK_RUN
        String taskId                  // always present
) {}
```

Rules:

1. `taskId` is always required.
2. `goalId` is required only for `GOAL_RUN`.
3. All scheduler ticks and command execution paths must carry this context.

### 3) PlanModeState

```java
public record PlanModeState(
        SessionIdentity sessionIdentity,
        boolean enabled,
        String activePlanId,
        Instant updatedAt
) {}
```

Rules:

1. Only one active plan per session.
2. Other sessions keep independent state.

## Required Changes by Component

### 1) Command Routing and Inbound Adapters

Files:

1. `src/main/java/me/golemcore/bot/adapter/inbound/command/CommandRouter.java`
2. `src/main/java/me/golemcore/bot/adapter/inbound/web/WebSocketChatHandler.java`
3. `src/main/java/me/golemcore/bot/adapter/inbound/telegram/TelegramAdapter.java`

Changes:

1. Always resolve `SessionIdentity` before handling `/plan` or `/auto`.
2. For Telegram, derive transport chat server-side and resolve active conversation via pointer service.
3. Bind slash-command path to the same `sessionChatId` mapping as normal messages.
4. Build `CommandContext` with explicit fields:
5. `transportChatId` for delivery only.
6. `sessionChatId` or `conversationKey` for logical operations.
7. `AutoExecutionContext` metadata when auto task is started.

### 2) Plan Service and Systems

Files:

1. `src/main/java/me/golemcore/bot/domain/service/PlanService.java`
2. `src/main/java/me/golemcore/bot/domain/system/ContextBuildingSystem.java`
3. `src/main/java/me/golemcore/bot/domain/system/PlanFinalizationSystem.java`
4. `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/PlansController.java`

Changes:

1. Replace global PlanMode state with `Map<SessionIdentity, PlanModeState>`.
2. Every PlanService API receives `SessionIdentity`.
3. `ContextBuildingSystem` checks plan mode by current session only.
4. `PlanFinalizationSystem` finalizes only within current session plan state.
5. `PlansController` endpoints become session-aware:
6. `GET /api/plans?sessionId=<conversationKey>`
7. `POST /api/plans/enable` with `sessionId`
8. `POST /api/plans/disable` with `sessionId`
9. Return payload includes `sessionId` and active session status.

### 3) Auto Scheduler and Runtime Loop

Files:

1. `src/main/java/me/golemcore/bot/auto/AutoModeScheduler.java`
2. `src/main/java/me/golemcore/bot/domain/service/PlanExecutionService.java`
3. `src/main/java/me/golemcore/bot/domain/loop/AgentLoop.java`
4. `src/main/java/me/golemcore/bot/domain/system/ContextBuildingSystem.java`
5. `src/main/java/me/golemcore/bot/domain/system/MemoryPersistSystem.java`

Changes:

1. Replace single scheduler channel state with run registry:
2. `runId -> AutoExecutionContext`
3. Synthetic inbound messages must use `conversationKey` as logical `chatId`.
4. Attach `goalId`, `taskId`, and `runKind` into context attributes.
5. For goal-driven tasks, use shared `goal` scope with task-local overlay.
6. For standalone tasks, skip goal scope entirely.

### 4) Memory Services

Files:

1. `src/main/java/me/golemcore/bot/domain/service/MemoryScopeSupport.java`
2. `src/main/java/me/golemcore/bot/domain/service/MemoryRetrievalService.java`
3. `src/main/java/me/golemcore/bot/domain/system/ContextBuildingSystem.java`
4. `src/main/java/me/golemcore/bot/domain/system/MemoryPersistSystem.java`

Changes:

1. Add canonical scope builders for `goal` and `task`.
2. Retrieval chain depends on `AutoExecutionContext.runKind`.
3. Persist writes route by data class:
4. Intermediate execution details -> task scope.
5. Shared goal insights -> goal scope.
6. General user preference facts -> session or explicit global promotion.
7. Keep backward compatibility for legacy items with no scope as `global`.

### 5) Dashboard Integration

Files:

1. `dashboard/src/hooks/usePlans.ts`
2. `dashboard/src/components/chat/PlanControlPanel.tsx`
3. `dashboard/src/api/plans.ts`
4. `dashboard/src/components/chat/ChatWindow.tsx`

Changes:

1. Plan state query key must include active session ID:
2. `['plan-control-state', sessionId]`
3. Plan enable/disable actions must pass current `sessionId`.
4. Switching sessions must invalidate only session-bound plan cache.
5. UI labels should explicitly show mode state for current chat session.

## Context Attribute Contract

Canonical keys must be added to `ContextAttributes` and used across systems:

1. `AUTO_RUN_KIND`
2. `AUTO_RUN_ID`
3. `AUTO_GOAL_ID`
4. `AUTO_TASK_ID`
5. `SESSION_IDENTITY_CHANNEL`
6. `SESSION_IDENTITY_CONVERSATION`

Rules:

1. No ad-hoc string literals in systems/tools for these values.
2. If typed fields exist in `AgentContext`, they must mirror canonical keys.

## API and Command Semantics

### 1) `/plan`

1. `/plan on` enables PlanMode only for active session.
2. `/plan off` disables PlanMode only for active session.
3. `/plan status` reports state for active session.

### 2) `/auto`

1. `/auto on` starts `TASK_RUN` when no goal context is supplied.
2. `/auto on` starts `GOAL_RUN` when goal context exists in command payload or execution metadata.
3. `/auto off` stops runs bound to active session unless explicit run ID is provided.
4. `/auto status` returns active runs for current session and marks run kind.

### 3) Transport and Routing

1. Response delivery target remains `transportChatId`.
2. Logical state and memory always resolve from `SessionIdentity`.
3. Outgoing events to Web must include `sessionId` for UI filtering.

## Invariants

1. PlanMode in session `A` never changes state in session `B`.
2. Auto run output from session `A` is never delivered to session `B`.
3. Standalone task `T1` never reads task scope of `T2`.
4. Goal tasks under same goal can read shared goal scope.
5. Goal scope is always bounded by session identity and cannot cross sessions.
6. Only explicit promotion writes to `global`.

## Rollout Plan (Without Feature Flags)

### Phase 1: Domain Contracts and Storage

1. Add `SessionIdentity`, `AutoExecutionContext`, and scope builders.
2. Refactor PlanService to session-scoped state.
3. Add context attribute constants and adapters.

### Phase 2: Routing and Scheduler

1. Update command-path session binding for Telegram and Web.
2. Implement `runId -> AutoExecutionContext` registry in scheduler.
3. Ensure synthetic messages always carry logical session conversation key.

### Phase 3: Memory Chain Enforcement

1. Add goal/task scope persistence and retrieval.
2. Enforce retrieval order by run kind.
3. Add strict filtering to reject foreign session scopes.

### Phase 4: Dashboard

1. Make plan API and query cache fully session-aware.
2. Add UX state transitions on session switch.
3. Validate no stale state after chat switching.

### Phase 5: Observability and Hardening

1. Add metrics and structured logs.
2. Add integration and concurrency tests.
3. Run strict verification pipeline.

## Testing Strategy

### 1) Unit Tests

1. `PlanService`: per-session state isolation.
2. `MemoryScopeSupport`: canonical scope generation for session/goal/task.
3. `MemoryRetrievalService`: retrieval order for `GOAL_RUN` and `TASK_RUN`.
4. `AutoModeScheduler`: run registry behavior and cleanup.

### 2) Integration Tests

1. First slash command in new session routes correctly and binds mapping.
2. `/plan on` in session A does not affect session B.
3. `/auto on` standalone creates isolated task memory.
4. Two tasks under same goal share goal scope but keep task-private details.
5. Deleting active session repairs pointer and preserves invariants.

### 3) WebSocket and UI Tests

1. Outgoing WS events always include `sessionId`.
2. UI filters events to active session only.
3. Switching session updates plan panel to correct session state.

### 4) Edge and Concurrency Tests

1. Simultaneous session switch and `/plan on`.
2. Simultaneous `/auto off` and scheduler tick.
3. Stale pointer during command execution.
4. Legacy conversation key read compatibility.
5. Disconnect cleanup with multiple bound session routes.

## Observability

Add metrics:

1. `plan.mode.enabled.count` with labels: `channel`, `session`.
2. `plan.mode.active.sessions` gauge.
3. `auto.run.started.count` with labels: `runKind`, `channel`.
4. `auto.run.completed.count` with labels: `runKind`, `status`.
5. `memory.scope.goal.selected.count`
6. `memory.scope.task.selected.count`
7. `memory.scope.cross_session_blocked.count`
8. `routing.session_mismatch.blocked.count`

Add structured logs:

1. `session_identity`
2. `run_id`
3. `goal_id`
4. `task_id`
5. `run_kind`
6. `transport_chat_hash`
7. `event`

## Migration and Backward Compatibility

1. No mandatory offline migration.
2. Existing session files remain valid.
3. Existing legacy memory without scope remains readable as `global`.
4. Existing commands remain available; semantics become session-aware.
5. Auto runs started before deploy are not resumed and must be restarted.

## Risks and Mitigations

1. Risk: scheduler and command race conditions.
2. Mitigation: atomic run registry operations and deterministic stop semantics.
3. Risk: accidental retrieval from wrong scope.
4. Mitigation: strict scope filter with explicit session identity checks.
5. Risk: UI stale plan state after fast switching.
6. Mitigation: session-keyed caching and targeted invalidation.
7. Risk: increased complexity in debugging context chain.
8. Mitigation: structured logs with run/session identifiers and scope traces.

## Acceptance Criteria

1. Enabling PlanMode in one session never changes another session.
2. Auto standalone tasks do not share task context with each other.
3. Goal tasks share goal context only within same session and same `goalId`.
4. No message from session A appears in active UI session B.
5. Memory retrieval never returns foreign session scope items.
6. Dashboard plan state always reflects current selected session.
7. Full test suite passes with strict profile (`verify -P strict`).

## References

1. `docs/ADR_MULTI_SESSION_SWITCHING.md`
2. `docs/ADR_MEMORY_V3.md`
3. `docs/AUTO_MODE.md`
4. `docs/MEMORY.md`
