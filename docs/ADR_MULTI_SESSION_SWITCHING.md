# ADR: Multi-Session Switching Across Web and Telegram with Scoped Memory

Status: Accepted  
Date: 2026-02-22  
Owners: Core Platform, Dashboard UX

## Context

Current runtime behavior is tightly coupled to `channelType + chatId`:

1. `SessionService` session identity is effectively `"{channelType}:{chatId}"`.
2. `ResponseRoutingSystem` uses session chat ID as delivery target.
3. `SessionRunCoordinator` queueing is bound to channel/chat identity.
4. Telegram adapter always maps one Telegram chat to one logical session.
5. Memory V2 stores durable records globally (`memory/items/*.jsonl`) and does not enforce active session scope in retrieval.

This model is stable for single-threaded conversations, but it breaks down for parallel contexts (for example, one user keeps separate topic threads in one Telegram chat).

## Problem Statement

We need an architecture where:

1. One transport chat can have multiple logical sessions.
2. Session switching is low-friction in Web and Telegram.
3. Context retrieval is scoped to active session plus safe global memory.
4. Existing stored sessions and memory remain valid.

## Decision Drivers

1. Correct routing: never lose channel delivery address after session switch.
2. Context isolation: prevent accidental bleed between parallel sessions.
3. Backward compatibility: keep legacy session files and commands working.
4. Operational safety: rollout behind feature flags with rollback path.
5. UX speed: one-tap switching for recent sessions.

## Non-Goals

1. Multi-tenant SaaS isolation redesign beyond current product model.
2. Shared collaborative sessions between different authenticated operators.
3. Replacing Memory V2 ranking logic outside session/global scope rules.

## Considered Options

### Option A: Keep one Telegram session per chat, improve only Web

- Pros: smallest backend delta.
- Cons: does not solve the main Telegram requirement.
- Decision: Rejected.

### Option B: Keep model unchanged, use `/new` and `/reset` as pseudo-switch

- Pros: no schema changes.
- Cons: destroys local context and prevents quick return to prior thread.
- Decision: Rejected.

### Option C: Split transport identity and logical conversation identity

- Pros: channel-agnostic model, supports true switching, aligns with scoped memory.
- Cons: requires API/schema/adapter/pipeline updates.
- Decision: Accepted.

## Decision

Adopt dual identity with strict invariants:

1. `transportChatId`: where responses are delivered.
2. `conversationKey`: which logical session state is loaded/written.
3. `sessionId = "{channelType}:{conversationKey}"`.

### Routing Invariant

`transportChatId` is the only delivery target used by channel adapters and routing.

### Context Invariant

`conversationKey` is the only key used to load/persist message history.

### Compatibility Invariant

If `conversationKey` is absent, system falls back to legacy `chatId` semantics.

## Glossary

1. `transportChatId`: channel endpoint identity (Telegram chat ID, Web socket chat identity).
2. `conversationKey`: logical conversation thread ID.
3. `active session pointer`: mapping `(channelType, transportChatId) -> conversationKey`.
4. `scope` (memory): either `global` or session-scoped context.

## Architecture

### 1) Canonical Identifier Rules

1. `conversationKey` format: `^[a-zA-Z0-9_-]{8,64}$`.
2. `conversationKey` must not include `:` to avoid ambiguous `sessionId` parsing.
3. New keys are generated as URL-safe UUID-based tokens.
4. `sessionId` remains stable even if UI title changes.

### 2) Session Data Model

`AgentSession` gains explicit fields:

1. `conversationKey` (required after migration path).
2. `transportChatId` (required for correct routing).
3. Optional metadata: `title`, `preview`, `lastActivityAt`.

Proto/serialization evolution:

1. Add new protobuf fields with new field numbers only.
2. Keep legacy fields for backward parsing.
3. Lazy backfill on read: if missing values, derive from legacy `chatId`.

### 3) Active Session Pointer Storage

Introduce persistent pointer registry:

1. Key: channel-specific pointer key (see below).
2. Value: `conversationKey`.
3. Persistence: dedicated preferences file `preferences/session-pointers.json` (single source of truth).
4. Resolution policy: last-write-wins; read path must be deterministic.

Implementation note (2026-02-22):

1. Implemented in `ActiveSessionPointerService`.
2. File path is `preferences/session-pointers.json` with atomic writes (`putTextAtomic`).
3. Runtime keys are:
   - `telegram|<chatId>`
   - `web|<username>|<clientInstanceId>`
4. For Web, `username` is derived from authenticated principal.
5. For Telegram, `chatId` is always server-derived from incoming update/callback.

#### 3.1 Pointer Registry Schema

```json
{
  "version": 1,
  "pointers": {
    "telegram|<chatId>": "<conversationKey>",
    "web|<username>|<clientInstanceId>": "<conversationKey>"
  },
  "updatedAt": "2026-02-22T12:00:00Z"
}
```

Write/read rules:

1. Writes use atomic persistence (`putTextAtomic`) to avoid partial state.
2. Service keeps in-memory cache (`ConcurrentHashMap`) for hot-path reads.
3. File updates are idempotent for repeated switch to same `conversationKey`.

Fallback behavior:

1. Pointer missing -> use legacy default conversation key derived from transport chat ID.
2. Pointer points to missing session -> create empty session lazily and continue.
3. Active session deleted -> repoint to most recently updated remaining session for same pointer domain; if none, create new default.

### 4) Runtime Flow Changes

#### 4.1 Telegram Inbound

1. Adapter resolves active pointer for incoming chat.
2. Builds message with:
   - transport identity preserved,
   - conversation key in context metadata or explicit field mapping.
3. Session pipeline loads/writes by conversation key.

Telegram pointer key:

1. `telegram|<chatId>`.
2. `<chatId>` is always derived from Telegram update on server side.
3. Client-provided chat identity is never trusted for pointer resolution.

#### 4.2 Web Inbound

1. Web client sends selected `conversationKey`.
2. Backend validates key and resolves session.
3. Delivery channel remains current Web chat transport identity.

Web pointer key:

1. `web|<username>|<clientInstanceId>`.
2. `username` comes from validated JWT principal.
3. `clientInstanceId` is stable per browser tab/device instance.
4. This avoids cross-tab pointer collisions for one authenticated user.

#### 4.3 Session Queueing

Queue key in `SessionRunCoordinator` must be logical session identity (`channelType + conversationKey`), not transport chat identity, to avoid cross-thread blocking.

### 5) API Contracts

Existing `/api/sessions` CRUD remains.

Additions:

1. `POST /api/sessions`  
   Create new logical session for channel+transport identity, optionally activate.
2. `GET /api/sessions/recent`  
   Return recent sessions for quick switching (`limit`, `channel` filters).
3. `GET /api/sessions/active`  
   Resolve current active session for transport identity.
4. `POST /api/sessions/active`  
   Switch active session.

Contract rules:

1. For Telegram, transport identity is derived server-side from update; client cannot override.
2. For Web, transport/session scope is derived from authenticated principal + channel context + client instance ID.
3. Switch operation is idempotent when target is already active.

### 6) Command and UX Semantics

#### 6.1 Telegram

Add `/sessions` and menu section:

1. Display current session and recent sessions.
2. Actions: switch, create, back.
3. Keep recent list short (default 5) with optional pagination.

Telegram callback safety:

1. Callback payload must fit Telegram 64-byte limit.
2. Use compact action tokens (`idx` or short key), never full long titles.

Command semantics in multi-session mode:

1. `/new`: create and activate a new session.
2. `/reset`: clear only the active session.
3. `/status`, `/compact`: operate on active session.

#### 6.2 Web

Sidebar adds `Chat Sessions` group:

1. `New chat` action.
2. Recent sessions list with active highlight.
3. One-click switch updates URL and chat window state.

`Sessions` page remains the full management surface.

### 7) Scoped Memory Model

Memory scope key:

1. `global`
2. `session:{channelType}:{conversationKey}`

Read path:

1. Retrieve session scope first.
2. Retrieve global scope second.
3. Apply existing ranking and budget constraints.
4. Guarantee at least one reserved quota slice for session-scoped items when present.

Write path:

1. Turn episodic records default to session scope.
2. Promotion to `global` allowed only for durable item types and confidence thresholds.
3. Items without scope are interpreted as `global` for backward compatibility.

### 8) Guardrails and Limits

1. Max sessions per `(channelType, transportChatId)` (default 200) to prevent unbounded growth.
2. Title length cap and sanitization for UI safety.
3. Recent list limit default 5, server-enforced max 20.
4. Pointer switch rate metric and optional lightweight rate limit.

### 9) Security and Access Control

1. Session switch/create must be authorized in caller context.
2. Caller may only operate on sessions bound to same channel+transport ownership.
3. Transport identity for Telegram is server-derived from incoming update, not request payload.
4. Web endpoints must bind to authenticated principal context to avoid horizontal access.

### 10) Observability

Add metrics:

1. `sessions.create.count`
2. `sessions.switch.count`
3. `sessions.active.pointer.miss.count`
4. `sessions.active.pointer.stale.count`
5. `memory.scope.session.selected.count`
6. `memory.scope.global.selected.count`
7. `memory.scope.filtered.count`

Add logs:

1. Switch event with channel, transport hash, conversation key.
2. Pointer fallback reasons.
3. Scope composition diagnostics in context-building.

## Rollout Plan

Phase 1: Data model and API foundation  
Phase 2: Telegram `/sessions` + menu  
Phase 3: Web sidebar quick switch  
Phase 4: Scoped memory enablement

Feature flags:

1. `sessions.multi.enabled`
2. `telegram.sessions.menu.enabled`
3. `web.sidebar.recentSessions.enabled`
4. `memory.sessionScope.enabled`

## Migration Strategy

1. No mandatory offline migration.
2. Lazy upgrade on read:
   - infer `conversationKey` from legacy `chatId`,
   - infer `transportChatId` from legacy `chatId`.
3. Persist normalized form on next save.
4. Active pointer registry bootstraps lazily from first incoming message per transport identity.

## Rollback Strategy

1. Disable `sessions.multi.enabled` to return to legacy identity (`conversationKey := transportChatId`).
2. Existing normalized session records remain readable in legacy mode.
3. Memory scope feature can be disabled independently; retrieval then behaves as global-only fallback.

## Testing Strategy

### Backend

1. Session ID parsing/validation and backward compatibility.
2. Active pointer resolution, stale pointer handling, idempotent switch.
3. Routing uses transport identity after switch.
4. Queue isolation by logical session key.

### Telegram

1. `/sessions` command flow and callback switching.
2. Callback payload length and parsing.
3. `/new` and `/reset` semantics in multi-session mode.

### Web

1. Sidebar recent sessions rendering and active highlight.
2. URL/session synchronization and reload persistence.
3. Quick switch does not drop active WebSocket channel.

### Memory

1. Session scope retrieval precedence.
2. Global promotion constraints.
3. Legacy no-scope item compatibility.

## Risks and Mitigations

1. Risk: incorrect response routing after switch.  
   Mitigation: hard routing invariant on `transportChatId`, plus tests.
2. Risk: context leakage between sessions.  
   Mitigation: scope filters mandatory in retrieval path.
3. Risk: Telegram menu payload overflow.  
   Mitigation: compact callback tokens and bounded labels.
4. Risk: stale or missing active pointers.  
   Mitigation: deterministic fallback and stale-pointer metrics.
5. Risk: operational regressions during rollout.  
   Mitigation: phased flags and independent rollback paths.

## Consequences

Positive:

1. True multi-session workflows across Web and Telegram.
2. Faster task switching with explicit active-context control.
3. Better contextual relevance through session-scoped memory.

Negative:

1. Higher complexity across adapters, session service, and APIs.
2. Additional persistence/state for active pointers.
3. Larger test matrix and migration coverage.

## Open Questions

1. Final default for session cap per transport identity.
2. Session title lifecycle: auto-title only vs explicit rename endpoint.
3. Whether scoped memory quotas should be static or runtime configurable.

## References

1. `docs/MEMORY.md`
2. `docs/ADR_MEMORY_V3.md`
3. `docs/DASHBOARD.md`
