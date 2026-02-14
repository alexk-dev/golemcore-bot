# ADR-0003: OutgoingResponse Preparation System + Transport Purity (Single Source of Truth)

## Status
Proposed

## Context
We introduced `OutgoingResponse` as a transport-oriented contract and removed the legacy “pending attachments” queue. This significantly improved separation of concerns:

- **Domain execution** (ToolLoop / plan / skills) decides **what the user should receive**.
- **Transport** (`ResponseRoutingSystem`) is responsible only for **how to deliver** the already prepared response.

However, the current code still has several architecture leaks:

1. **`AgentLoop` couples to routing by system name** (string-based hack): it prepares `OutgoingResponse` when encountering `ResponseRoutingSystem` in the pipeline.
2. **Multiple sources of truth** for “what to send” still exist (`LLM_RESPONSE`, `LLM_ERROR`, `VOICE_REQUESTED`, `VOICE_TEXT`, sometimes synthetic assistant messages).
3. `ResponseRoutingSystem` still writes status back via `ContextAttributes` (`RESPONSE_SENT`, `ROUTING_ERROR`).
4. Feedback guarantee in `AgentLoop` directly appends a synthetic assistant message to raw history.

We want to complete Variant B in the cleanest possible way:

- `OutgoingResponse` is the **single source of truth** for transport.
- `ResponseRoutingSystem` is **transport-only** and does not interpret LLM domain objects.
- Preparation of `OutgoingResponse` is a first-class pipeline responsibility, not a string-hack in `AgentLoop`.

## Goal
Establish a clean, explicit architecture where:

1. `OutgoingResponse` is the **only** contract consumed by transport.
2. Creation of `OutgoingResponse` is performed by a dedicated pipeline system: **`OutgoingResponsePreparationSystem`**.
3. Feedback guarantee is implemented without violating raw-history ownership rules.

## Decision

### 1) Introduce `OutgoingResponsePreparationSystem`
Add a new `AgentSystem` (order just before routing, e.g. `55`) responsible for producing `ContextAttributes.OUTGOING_RESPONSE`.

**Responsibilities**:
- If `OUTGOING_RESPONSE` is already present → do nothing (upstream systems already decided).
- Else, derive it from canonical inputs:
  - `LLM_ERROR` → user-friendly error message (`OutgoingResponse.textOnly(...)`)
  - `LLM_RESPONSE` (+ voice flags) → `OutgoingResponse` for normal completion
- Perform voice-prefix detection / voice auto-response policy (if these remain part of orchestration).

**Non-responsibilities**:
- Must not send messages (no transport).
- Must not write raw history.

This removes the current `AgentLoop` coupling:
- `AgentLoop` must not check system names to prepare outgoing responses.

### 2) Transport Single Source of Truth (strict)
`ResponseRoutingSystem` must treat `OUTGOING_RESPONSE` as the only input.

- No reading of `LLM_RESPONSE`, `LLM_ERROR`, legacy attachment queues, etc.
- No implicit fallbacks.

### 3) Make `ResponseRoutingSystem` output typed routing outcome (optional, cleanest variant)
To avoid “attribute bus” leakage, we should gradually replace:
- `ContextAttributes.RESPONSE_SENT`
- `ContextAttributes.ROUTING_ERROR`

with a typed result, e.g.:

- `RoutingOutcome { boolean sent; String error; List<AttachmentSendResult> ... }`

Stored in `AgentContext` via typed API (preferred) or via a single canonical attribute key.

### 4) Feedback guarantee: move from `AgentLoop` to a dedicated system (cleanest)
Currently, feedback guarantee is implemented in `AgentLoop.ensureFeedback()` and may append synthetic assistant messages to raw history.

Cleanest target:

- Introduce `FeedbackGuaranteeSystem` (order `58`–`59`, before routing) that:
  - ensures `OUTGOING_RESPONSE` exists for non-auto mode requests,
  - does **not** write raw history,
  - emits a minimal safe fallback message.

Raw history ownership stays with domain executors (ToolLoop, plan, etc).

If we still want to persist a synthetic assistant message, it should be done via a domain-level `HistoryWriter` port (not `AgentLoop` mutating `AgentSession` directly).

### 5) Things to remove / migrate away from

#### Remove string-based pipeline coupling
- Remove `ROUTING_SYSTEM_NAME` and the special-case “prepare outgoing when we reach routing system”.

#### Reduce reliance on scattered ContextAttributes
- Keep `ContextAttributes.OUTGOING_RESPONSE` as canonical transport input.
- Migrate `VOICE_REQUESTED` / `VOICE_TEXT` into the `OutgoingResponse` creation path.
- Consider typed API for routing outcomes, instead of multiple ad-hoc attribute keys.

#### Avoid raw history mutations in orchestration
- `AgentLoop` should orchestrate systems and persistence, not write synthetic messages.

## Target Architecture

### Pipeline (suggested orders)
- ... domain execution systems ...
- `MemoryPersistSystem` (50) (or after finalization)
- **`OutgoingResponsePreparationSystem` (55)**
- **`FeedbackGuaranteeSystem` (58/59)** (optional but recommended)
- `ResponseRoutingSystem` (60)

### Invariants
1. If `ResponseRoutingSystem.shouldProcess(context)` is true, it is because `OUTGOING_RESPONSE` exists and contains something sendable (text/voice/attachments).
2. Transport consumes only `OutgoingResponse`.
3. Domain executors own raw history; orchestration does not mutate raw history directly.

## Migration Plan

### Phase 1 — Introduce OutgoingResponsePreparationSystem
- [ ] Add `OutgoingResponsePreparationSystem` (order=55)
- [ ] Move logic from `AgentLoop.prepareOutgoingResponse(...)` into this system
- [ ] Remove the `AgentLoop` special-case coupling by system name
- [ ] Update tests:
  - [ ] BDD: `OutgoingResponse` prepared when `LLM_RESPONSE` exists
  - [ ] BDD: error path `LLM_ERROR` ⇒ outgoing error message
  - [ ] Ensure `ResponseRoutingSystem` remains transport-only

### Phase 2 — Feedback guarantee refactor
- [ ] Introduce `FeedbackGuaranteeSystem` (or keep in AgentLoop but stop writing raw history)
- [ ] Decide whether synthetic assistant messages should ever be written:
  - [ ] If yes, use `HistoryWriter` port
  - [ ] If no, keep raw history untouched

### Phase 3 — Typed routing outcomes (optional)
- [ ] Replace `RESPONSE_SENT` / `ROUTING_ERROR` with `RoutingOutcome` typed API
- [ ] Remove legacy attribute keys once downstream code is migrated

## Consequences

### Positive
- Removes hidden coupling in orchestration.
- Makes transport contract truly canonical and testable.
- Clarifies ownership of raw history vs delivery.

### Trade-offs
- Adds 1–2 pipeline systems (small complexity cost).
- Requires some test migrations.

## Alternatives Considered

1. **Keep preparation inside AgentLoop**
   - Rejected: forces orchestration to know pipeline internals and introduces string-coupling.

2. **Make ResponseRoutingSystem “smart” and prepare outgoing itself**
   - Rejected: transport becomes a domain interpreter again (violates Variant B).
