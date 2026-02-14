# ADR-0004: FeedbackGuaranteeSystem — Detailed Design (Transport-Safe Feedback Guarantee)

## Status
Accepted

## Problem / Motivation
In a user-facing agent, **silence is a bug**.

Even in a well-structured pipeline, there are failure modes where a user can receive no response:

- a system throws unexpectedly and downstream systems do not produce a final message,
- tool loop stops early and upstream doesn't prepare an `OutgoingResponse`,
- `LLM_ERROR` is set but no system converts it into a user-visible message,
- routing succeeds/fails in unusual ways (timeouts, missing channel, etc.).

Historically, feedback guarantee was implemented inside `AgentLoop` by:

1. directly mutating raw history (`session.addMessage(assistant)`),
2. setting a synthetic response in context,
3. directly invoking the routing system.

This violated the desired separation of concerns:

- Orchestration (`AgentLoop`) should not own raw history.
- Transport (`ResponseRoutingSystem`) should not decide *what* to send.
- Domain executors (ToolLoop, plan flow) own raw-history writes.

We already introduced:

- `OutgoingResponse` as the single transport contract,
- `OutgoingResponsePreparationSystem` to prepare it without string-based coupling.

We now complete the architecture by introducing a dedicated pipeline system:

- **`FeedbackGuaranteeSystem`**

## Goals

### Primary goal
Guarantee that for a normal inbound user message (non-auto), the pipeline produces a minimal `OutgoingResponse` if none exists.

### Secondary goals
- Keep **transport purity**: this system must not send, only prepare `OutgoingResponse`.
- Keep **raw-history purity**: this system must not append synthetic assistant messages into raw history.
- Be easy to test with BDD-style scenarios.

## Non-goals
- Guaranteeing that a message was physically delivered to the user (network / API can still fail).
- Replacing typed routing outcomes (`RESPONSE_SENT` / `ROUTING_ERROR`) — that is a separate ADR.
- Implementing elaborate, model-driven error explanations (optional future enhancement).

## Architecture Overview

### Where it sits in the pipeline
Recommended ordering:

- Domain execution systems (ToolLoop, plan, etc.)
- `OutgoingResponsePreparationSystem` **(order=58)**
- `FeedbackGuaranteeSystem` **(order=59)**
- `ResponseRoutingSystem` **(order=60)**

Rationale:

- Preparation should translate domain outputs (`LLM_RESPONSE`, `LLM_ERROR`, voice flags) into transport contract.
- Feedback guarantee should run only if **no transport contract exists** after preparation.
- Routing should always operate on `OutgoingResponse` only.

### Inputs
- `context.messages` — used to determine whether this is an auto-mode iteration.
- `ContextAttributes.OUTGOING_RESPONSE` — presence indicates that upstream already decided what to send.

### Output
- Writes `ContextAttributes.OUTGOING_RESPONSE` when missing.

## Contract

### Invariants
1. **Never override** an existing `OutgoingResponse`.
2. Do not run in auto mode.
3. Do not mutate raw history.
4. Produce only safe, minimal content (no leaking of internal stacktraces).

### Definition: Auto mode
Auto mode is detected via the last message in `context.messages`:

- `message.metadata["auto.mode"] == true`

When auto mode is true, the guarantee does nothing.

## System Design

### Class signature
`me.golemcore.bot.domain.system.FeedbackGuaranteeSystem implements AgentSystem`

Dependencies:
- `UserPreferencesService` — provides localized fallback text.

### `getOrder()`
Returns `59`.

### `shouldProcess(context)`
Logic:

- If `OUTGOING_RESPONSE` exists → `false` (already satisfied)
- If auto mode context → `false` (internal processing)
- Else → `true`

### `process(context)`
Logic:

1. If `OUTGOING_RESPONSE` exists or auto mode → noop.
2. Else set `OUTGOING_RESPONSE = OutgoingResponse.textOnly(<generic fallback>)`.

Fallback text is retrieved via:

- `preferencesService.getMessage("system.error.generic.feedback")`

### Logging
- Emits an INFO message when producing a fallback, to support operational debugging:
  - `"[FeedbackGuarantee] Producing fallback OutgoingResponse ..."`

Avoid logging exception details here (this system is not the error collector).

## Interaction with Other Components

### With `OutgoingResponsePreparationSystem`
- Preparation is responsible for normal derivations from `LLM_RESPONSE`/`LLM_ERROR`.
- Guarantee runs only if preparation produced nothing.

### With `ResponseRoutingSystem`
- Routing reads only `OutgoingResponse`.
- Guarantee does not send.

### With `AgentLoop`
We keep a minimal last-resort in `AgentLoop.ensureFeedback()` for cases where routing failed and we want to attempt again.

But we deliberately remove raw-history mutation from orchestration.

Target direction:
- Over time, move more feedback paths into dedicated systems and reduce `AgentLoop.ensureFeedback()` responsibilities.

### With memory/RAG systems
`FeedbackGuaranteeSystem` does not set `finalAnswerReady` and does not touch `LLM_RESPONSE`.

This is intentional:
- A feedback message is a transport fallback, not a domain "final answer".

## Testing Strategy

### Unit tests (must-have)
- Auto mode: shouldProcess=false
- Existing outgoing: shouldProcess=false and must not override
- Missing outgoing in normal mode: shouldProcess=true and produces fallback

### BDD / pipeline tests (recommended)
- Given a context where all upstream systems did nothing, Then ResponseRoutingSystem still sends fallback.
- Given LLM_ERROR set, Then Preparation handles it and Guarantee does not run.

## Future Extensions

### Typed routing outcomes
Replace `RESPONSE_SENT`/`ROUTING_ERROR` with a typed `RoutingOutcome`.

### Smarter fallback
Optionally, a future variant may:

- collect `SYSTEM_ERROR*` attributes,
- produce a more specific user-facing message,
- still avoid leaking stack traces.

That should be implemented as a separate system (e.g. `ErrorInterpretationSystem`) to keep FeedbackGuarantee minimal.

## Consequences

### Pros
- Enforces the non-silence invariant without violating hexagonal boundaries.
- Removes raw-history writes from orchestration.
- Keeps transport pure and testable.

### Cons / trade-offs
- Adds another pipeline system (small operational complexity).
- Feedback guarantee might produce a generic message even when the failure could be explained better.

## Rollout / Migration

1. Introduce `FeedbackGuaranteeSystem` (this change).
2. Ensure ordering before routing.
3. Keep `AgentLoop.ensureFeedback()` for now (safety net), but remove raw-history mutation (done).
4. Later: consider moving error interpretation to a dedicated system and making `AgentLoop.ensureFeedback()` smaller.
