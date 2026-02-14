# ADR-0005: TurnOutcome + Typed Pipeline Contracts (Full Design Purity Step)

## Status
Proposed

## Executive Summary
This ADR defines the next architectural step to reach **maximum design purity** after introducing:

- `OutgoingResponse` as the transport contract,
- `OutgoingResponsePreparationSystem` (order 58),
- `FeedbackGuaranteeSystem` (order 59),
- `ResponseRoutingSystem` (order 60, transport-only).

The remaining impurity is caused by **multiple “truths”** about what happened in a turn:

- Transport uses `OutgoingResponse`.
- Side-effect systems (`MemoryPersistSystem`, `RagIndexingSystem`) use `finalAnswerReady` + `LLM_RESPONSE`.
- Orchestration/diagnostics uses various `ContextAttributes` string keys (errors, routing outcomes, voice flags).

**Decision:** introduce a single typed domain-level outcome object `TurnOutcome` ("single source of truth") and typed result objects for routing and failures, then migrate downstream systems away from legacy attribute keys.

---

## Context

### Current State (after ADR-0003 and ADR-0004)
1. **Transport layer** is already clean:
   - `ResponseRoutingSystem` only reads `ContextAttributes.OUTGOING_RESPONSE`.

2. **Preparation and fallback** are clean:
   - `OutgoingResponsePreparationSystem` converts `LLM_RESPONSE`/`LLM_ERROR` (+ voice flags) into `OutgoingResponse`.
   - `FeedbackGuaranteeSystem` ensures a fallback `OutgoingResponse` exists.

3. Remaining design issues:
   - **Two canonical representations** of the "final answer": `OutgoingResponse.text` vs `LLM_RESPONSE.content`.
   - **Attribute-bus** still used for cross-system contracts (routing statuses, system errors).
   - Some systems still rely on legacy keys (`VOICE_REQUESTED`, `VOICE_TEXT`, `RESPONSE_SENT`, `ROUTING_ERROR`, `SYSTEM_ERROR*`).

### Why this is a problem
- The system lacks a single place where it is explicitly captured:
  - what the user-facing result is,
  - what should be persisted/indexed,
  - whether the response was delivered,
  - what failed (structured, not string attributes).

Without a typed outcome, the pipeline is difficult to reason about, and invariants are enforced only indirectly.

---

## Goals

### G1 — Single Source of Truth for the turn result
Introduce `TurnOutcome` as the canonical domain contract that represents a completed turn.

### G2 — Remove "string attribute bus" for core contracts
Replace scattered `ContextAttributes` keys used as contracts with typed APIs/value objects.

### G3 — Make side-effect systems consume the canonical outcome
Migrate `MemoryPersistSystem` and `RagIndexingSystem` away from `LLM_RESPONSE` dependence.

### G4 — Preserve hexagonal boundaries
- Domain executors produce outcomes.
- Transport consumes `OutgoingResponse`.
- Orchestration does not mutate raw history and does not interpret domain outputs.

---

## Non-Goals
- Replacing the entire `AgentContext` attribute map immediately.
- Redesigning tool-loop internals (already addressed in ToolLoop ADR/plan).
- Implementing a universal event-sourcing system.

---

## Decision

### 1) Introduce `TurnOutcome` (typed) in `AgentContext`
Add a typed property to `AgentContext`:

- `TurnOutcome turnOutcome` (nullable)

#### `TurnOutcome` definition
A proposed minimal-but-extensible model:

```java
@Value
@Builder
public class TurnOutcome {
  FinishReason finishReason;

  // Canonical “user-visible” content for side effects.
  String assistantText;

  // Transport contract (may include voice/attachments).
  OutgoingResponse outgoingResponse;

  // Observability / debugging.
  @Singular List<FailureEvent> failures;

  // Optional references.
  @Builder.Default boolean rawHistoryWritten = false;
  String model; // if applicable
  @Builder.Default boolean autoMode = false;

  // Delivery result (set by transport).
  RoutingOutcome routingOutcome;
}
```

Where:

- `FinishReason` is a typed enum: `SUCCESS`, `ERROR`, `ITERATION_LIMIT`, `DEADLINE`, `POLICY_DENIED`, `PLAN_MODE`, etc.
- `FailureEvent` is a structured failure record (see below).
- `RoutingOutcome` is a structured transport result (see below).

#### Invariants
- If `turnOutcome != null`, then it is the **only** canonical object for downstream systems.
- `turnOutcome.outgoingResponse` MUST be non-null for user-facing (non-auto) turns.
- `turnOutcome.assistantText` is the canonical text used for memory/RAG indexing.

### 2) Introduce `RoutingOutcome` (typed) written by `ResponseRoutingSystem`
Replace `ContextAttributes.RESPONSE_SENT` and `ContextAttributes.ROUTING_ERROR` with a typed object.

Proposed model:

```java
@Value
@Builder
public class RoutingOutcome {
  boolean attempted;
  boolean sentText;
  boolean sentVoice;
  int sentAttachments;
  String errorMessage; // optional, user-safe summary
}
```

**Transport rule:** `ResponseRoutingSystem` must only:

- read `OutgoingResponse`,
- send it,
- record `RoutingOutcome` (typed) in `AgentContext` (preferably via `context.setRoutingOutcome(...)` or as part of `TurnOutcome`).

### 3) Introduce `FailureEvent` (typed) for system failures
Replace string keys like `SYSTEM_ERROR + systemName`.

Proposed model:

```java
public record FailureEvent(
  FailureSource source,
  String component,
  FailureKind kind,
  String message,
  Instant timestamp
) {}

public enum FailureSource { SYSTEM, LLM, TOOL, TRANSPORT }
public enum FailureKind { EXCEPTION, TIMEOUT, VALIDATION, POLICY, RATE_LIMIT, UNKNOWN }
```

`AgentLoop` catches system exceptions and records them as `FailureEvent` objects instead of writing string attributes.

### 4) Make `OutgoingResponse` a component of `TurnOutcome` (not a parallel truth)
`OutgoingResponse` remains the **transport contract**, but for design purity:

- `OutgoingResponse` should be stored primarily inside `TurnOutcome`.
- `ContextAttributes.OUTGOING_RESPONSE` can remain temporarily for backward compatibility, but the migration target is:
  - transport reads from `context.getTurnOutcome().getOutgoingResponse()`.

(We may keep the attribute key as an adapter bridge during migration.)

### 5) Migrate side-effect systems to consume `TurnOutcome`

#### `MemoryPersistSystem`
Instead of:
- `finalAnswerReady` + `LLM_RESPONSE.content`

Use:
- `TurnOutcome.assistantText`

#### `RagIndexingSystem`
Use:
- `TurnOutcome.assistantText`

This guarantees that what we persist/index matches the canonical outcome.

### 6) Remove legacy voice flags as contracts
Currently `OutgoingResponsePreparationSystem` reads `VOICE_REQUESTED` and `VOICE_TEXT`.

Target architecture:

- Producer systems decide voice intent by writing `OutgoingResponse` (or `TurnOutcome`) directly.
- Preparation system should become simpler over time and stop relying on scattered voice flags.

---

## Target Pipeline Architecture

Recommended final orders:

- Domain systems (tool loop, plan, skills...)
- `TurnOutcomeFinalizationSystem` (new, order ~56–58)
  - builds `TurnOutcome` from domain state
  - ensures `assistantText` and `outgoingResponse` are coherent
- (optional) `FeedbackGuaranteeSystem` (only if `turnOutcome` missing/outgoing missing)
- `ResponseRoutingSystem` (60)
  - sends based on `turnOutcome.outgoingResponse`
  - records `routingOutcome`

Notes:
- `OutgoingResponsePreparationSystem` may be folded into `TurnOutcomeFinalizationSystem` once voice flags and LLM_RESPONSE dependencies are removed.

---

## API / Data Model Changes (Detailed)

### AgentContext
Add typed fields + methods:

- `TurnOutcome getTurnOutcome()` / `setTurnOutcome(TurnOutcome)`
- `RoutingOutcome getRoutingOutcome()` or store in TurnOutcome
- `List<FailureEvent> getFailures()` / `addFailure(FailureEvent)`

Migration strategy:
- Keep `attributes` map for now.
- Stop adding new "contract" keys there.

### Systems

#### AgentLoop
- Replace `context.setAttribute(SYSTEM_ERROR + systemName, ...)` with `context.addFailure(...)`.
- Reduce/remove `ensureFeedback` logic as systems become responsible for producing `TurnOutcome`.

#### ResponseRoutingSystem
- Reads only transport contract (`OutgoingResponse` from `TurnOutcome`).
- Writes only typed `RoutingOutcome`.

#### MemoryPersistSystem / RagIndexingSystem
- Read from `TurnOutcome`.

---

## What Can Be Removed / What We Must Move Away From

### Must remove (contract-level)
- `ContextAttributes.RESPONSE_SENT`
- `ContextAttributes.ROUTING_ERROR`
- `ContextAttributes.SYSTEM_ERROR + <systemName>` pattern

### Must migrate away from
- `ContextAttributes.VOICE_REQUESTED`
- `ContextAttributes.VOICE_TEXT`

### Should remain temporarily (migration bridge)
- `ContextAttributes.OUTGOING_RESPONSE` (until transport reads from `TurnOutcome` directly)
- `ContextAttributes.LLM_RESPONSE` (until all side-effect systems and preparation paths are migrated)

---

## Testing Strategy (TDD/BDD)

### Unit tests
- `TurnOutcome` builder invariants.
- `RoutingOutcome` creation on successful send and failures.

### BDD pipeline scenarios (must-have)
1. **Happy path:** tool loop produces final answer → TurnOutcome created → routing sends → memory + rag consume TurnOutcome.
2. **LLM_ERROR path:** LLM_ERROR set → TurnOutcome created with ERROR finishReason and fallback message.
3. **No output path:** no LLM_RESPONSE, no outgoing → FeedbackGuarantee creates TurnOutcome/outgoing fallback.
4. **Transport failure path:** routing fails → RoutingOutcome captures failure, feedback does not mutate raw history.

### Regression scenarios
- Plan mode interception (no tool execution).
- Voice prefix path.
- Attachments-only response.

---

## Migration Plan (Phased)

### Phase 1 — Introduce typed models without breaking legacy
- [ ] Add `TurnOutcome`, `RoutingOutcome`, `FailureEvent` models.
- [ ] Add typed fields/APIs to `AgentContext`.
- [ ] Start recording `FailureEvent` from `AgentLoop` in parallel with existing attributes.

### Phase 2 — TurnOutcomeFinalization
- [ ] Add `TurnOutcomeFinalizationSystem`.
- [ ] Ensure it runs before routing.
- [ ] Populate `assistantText` and `outgoingResponse`.

### Phase 3 — Migrate side-effects
- [ ] Update `MemoryPersistSystem` to read `TurnOutcome.assistantText`.
- [ ] Update `RagIndexingSystem` to read `TurnOutcome.assistantText`.

### Phase 4 — Migrate transport outcomes
- [ ] Update `ResponseRoutingSystem` to write `RoutingOutcome`.
- [ ] Remove `RESPONSE_SENT` and `ROUTING_ERROR` usages.

### Phase 5 — Delete legacy contract keys
- [ ] Remove `SYSTEM_ERROR*` usage and related tests.
- [ ] Remove voice attribute flags contract once producers are migrated.

---

## Consequences

### Positive
- Establishes a single canonical turn-level truth.
- Strongly improves testability and reasoning.
- Eliminates most cross-system coupling through string attributes.

### Negative / trade-offs
- Adds new domain models and some migration overhead.
- Requires careful ordering and transitional bridging.

---

## Alternatives Considered

1. **Keep current split:** OutgoingResponse for transport, LLM_RESPONSE for side-effects.
   - Rejected: two truths, hidden invariants.

2. **Make transport system compute TurnOutcome.
   - Rejected: transport becomes domain interpreter.

3. **Event-sourcing everything.
   - Rejected: too heavy for this project’s goals.
