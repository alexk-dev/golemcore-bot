# ADR-00XX: Deterministic Plan Mode Finalization via `plan_finalize` Tool

- **Status:** Proposed
- **Date:** 2026-02-15
- **Decision Drivers:** Reliability, testability (BDD/TDD), hexagonal cleanliness, elimination of brittle text heuristics, single-source-of-truth transport contracts.

## Context
GolemCore Bot has a **Plan Mode** that collects proposed tool calls as plan steps and requires user approval before executing them. The current finalization logic relies on an implicit heuristic:

> *“Finalize the plan when the LLM returns plain text with no tool calls.”*

This is fragile in practice:
- Some models emit summary text **together with tool calls**.
- Some models keep emitting tool calls indefinitely (“just one more step”).
- “No tool calls” is an **absence-based signal**, which is hard to reason about and test.

Additionally, the current approach encourages downstream systems to infer planning completion from the shape/content of the LLM message rather than from an explicit domain signal.

We want Plan Mode to be:
- **Deterministic** (explicit completion signal)
- **Easy to test** (machine-readable event)
- **Architecturally clean** (hexagonal, with clear responsibilities)
- Compatible with the direction of the refactor: **typed turn outcomes / OutgoingResponse as transport SSOT**.

## Decision
Introduce a dedicated tool call **`plan_finalize`** used *only in Plan Mode* as the explicit, machine-readable signal that planning is complete.

### Core behavior
- While Plan Mode is active:
  - Regular tool calls are **intercepted** and recorded as `PlanStep`s (not executed).
  - When the model believes the plan is complete, it must call **`plan_finalize`**.
- `plan_finalize` triggers plan finalization:
  - Plan transitions `COLLECTING → READY` (or is cancelled if empty).
  - A plan summary is produced for the user.
  - A `PlanReadyEvent` is published (for approval UI), and execution remains blocked until user approval.

### Why a tool call (not a text marker)
- Structured and reliable (no regex, no language sensitivity).
- Aligns with tool-loop architecture (tools = explicit state transitions).
- Enables strict BDD assertions.
- Reduces coupling to model-specific formatting of summary text.

## Invariants (must hold)
1. **Advertisement invariant:** `plan_finalize` is advertised to the LLM **only when Plan Mode is active**.
2. **Execution invariant:** If `plan_finalize` is called while Plan Mode is **inactive**, it must be denied (policy failure) and must **not** mutate plan state.
3. **No side effects invariant:** Calling `plan_finalize` must never execute external tools or perform external side effects; it only affects plan state and user-facing output.

## Target Architecture

### 1) New tool: `plan_finalize`
- **Type:** Domain tool (no side effects outside plan state)
- **Availability:** Advertised to the LLM **only when Plan Mode is active**.
- **Execution policy:** Must be allowed without confirmation; it does not execute external actions.
- **Guardrail:** If called outside Plan Mode, it must return a failure (policy denied).

**Suggested schema** (minimal):
```json
{
  "name": "plan_finalize",
  "description": "Finalize the current plan. Use when you have finished proposing plan steps. This does not execute tools.",
  "input": {
    "type": "object",
    "properties": {
      "summary": { "type": "string", "description": "Optional brief plan summary for the user." }
    }
  }
}
```

**Notes**
- `summary` is optional. We can support both:
  - summary provided as `summary` argument, or
  - summary provided as regular assistant content in the same LLM response.

### 2) Pipeline responsibility split

#### ToolLoopExecutionSystem / ToolLoop
- Remains responsible for:
  - LLM calls
  - tool call orchestration
  - appending assistant/tool messages to raw history

#### Plan collection & finalization
Two viable placements (choose based on current code reality and refactor stage):

**Option A (recommended): Plan interception/finalization is a dedicated system**
- Add `PlanModeInterceptionSystem` (order near ToolLoop, before plan finalization/outgoing response preparation).
- It inspects `LLM_RESPONSE.toolCalls`:
  - If toolCall.name == `plan_finalize`: triggers plan finalization path.
  - Else: records each tool call as a plan step and generates synthetic tool results `[Planned]`.

**Option B (migration-friendly): Intercept in ToolLoop, but isolate policy**
- Keep the interception inside ToolLoop short-term, but delegate to a `PlanModeToolPolicy` component.

ADR endorses **Option A** for hexagonal cleanliness.

### 3) Finalization rules
When `plan_finalize` is observed:
1. Resolve active plan.
2. If plan has 0 steps: cancel plan, deactivate plan mode.
3. Else:
   - finalize plan (`COLLECTING → READY`)
   - publish `PlanReadyEvent`
   - set `ContextAttributes.PLAN_APPROVAL_NEEDED = planId`
   - produce user-facing response (via `OutgoingResponse` or via a canonical `TurnOutcome` field)

### 4) Transport contract
Plan finalization must **not** mutate `LlmResponse` in-place.

Preferred output:
- `OutgoingResponse.text` includes:
  - optional LLM summary text
  - generated plan card / quick commands

If the system is moving towards canonical `TurnOutcome`, then:
- add `TurnOutcome.planIdReadyForApproval` (or similar) and derive `OutgoingResponse` from it.

## Detailed Implementation Plan (Phases)

### Phase 0 — Documentation + prompt contracts (no code behavior change)
- [ ] Update plan mode prompt/context (`PlanService.buildPlanContext()`):
  - Explicitly instruct the model:
    - propose steps as tool calls
    - when finished, call `plan_finalize`
    - do not execute tools in plan mode

### Phase 1 — Introduce `plan_finalize` tool (plumbing)
- [ ] Add `PlanFinalizeTool` implementation (`tools/` or `domain/component` depending on existing conventions).
- [ ] Register the tool in tool registry / tool list provider.
- [ ] Ensure it is advertised to the LLM **only** when plan mode is active (via `ContextBuildingSystem`).

**Behavior of PlanFinalizeTool**
- It should **not** execute external actions.
- It should return a `ToolResult.success("[Plan finalized]")` (or similar) for raw history completeness.
- It may store the optional summary argument into context attributes for downstream usage.
- If plan mode is inactive, it returns `ToolResult.failure(kind=POLICY_DENIED, ...)`.

### Phase 2 — Implement deterministic finalization trigger
- [ ] Update plan processing to key off `plan_finalize` tool call rather than “no tool calls”.

Concrete changes:
- [ ] Replace `PlanFinalizationSystem.shouldProcess()` heuristic.
- [ ] New logic: process when LLM tool calls contain a `plan_finalize`.

### Phase 3 — Move plan interception out of ToolLoop (clean split)
- [ ] Add `PlanModeInterceptionSystem` (new pipeline system).
- [ ] It must:
  - detect plan mode active
  - read `LLM_RESPONSE.toolCalls`
  - for each tool call:
    - if `plan_finalize`: finalize plan
    - else record step + write synthetic tool result `[Planned]`
  - ensure tool calls are **not executed** in plan mode

This system becomes the single place responsible for:
- plan step recording
- synthetic tool results for plan steps
- plan finalization via tool signal

### Phase 4 — Transport + SSOT alignment
- [ ] Remove in-place mutations of `LlmResponse` in plan finalization.
- [ ] Produce `OutgoingResponse` (or set typed outcome) with:
  - user-visible plan card
  - quick commands (`/plan approve <id>` etc.)

### Phase 5 — Tighten tests (BDD/TDD)
Add/adjust tests to lock the new contract:

**Unit tests**
- [ ] `PlanModeInterceptionSystemTest`:
  - tool calls recorded as plan steps
  - synthetic tool results appended
  - `plan_finalize` causes `PlanService.finalizePlan()`

- [ ] `PlanFinalizeToolTest`:
  - returns success tool result
  - writes optional summary attribute (if implemented)
  - denies outside plan mode

**BDD scenarios (pipeline-level)**
- [ ] Scenario: Plan mode collects steps, then `plan_finalize` finalizes and emits approval event.
- [ ] Scenario: `plan_finalize` with 0 collected steps cancels plan.
- [ ] Scenario: LLM emits summary text + `plan_finalize` tool call in same response → finalization still happens.
- [ ] Scenario: Plan mode active but LLM never calls `plan_finalize` → plan remains COLLECTING (user can `/plan off` or continue).

### Phase 6 — Cleanup and removal of legacy heuristics
- [ ] Delete the old “no tool calls” finalization heuristic.
- [ ] Ensure docs (`docs/CONFIGURATION.md`, plan docs) reflect the new deterministic behavior.

## Full List of Expected Code Changes

### New
- `tools/PlanFinalizeTool.java` (or equivalent)
- `domain/system/PlanModeInterceptionSystem.java` (recommended)
- Tests for both
- Message keys (i18n) if new strings are introduced

### Modified
- `PlanService.buildPlanContext()` — prompt text updated to instruct `plan_finalize`.
- `ContextBuildingSystem` — ensure conditional tool availability + plan prompt injection remain consistent.
- `PlanFinalizationSystem` — switch trigger to `plan_finalize` (or reduce responsibilities if replaced by interception system).
- Tool loop / execution path — ensure plan mode never executes real tools.
- Documentation: `docs/CONFIGURATION.md` (and any Plan Mode doc pages).

### Removed/Deprecated
- “Finalize when plain text and no tool calls” logic (after migration).

## Consequences

### Positive
- Deterministic finalization; fewer model-behavior edge cases.
- Stronger separation of concerns (Plan Mode logic isolated).
- Easier automated testing and debugging.
- Less coupling to language/format of the plan summary.

### Negative / Trade-offs
- Requires model compliance with a new tool contract (prompting must be explicit).
- Adds a new tool and potentially a new pipeline system.

## Notes on Single-User Assumption
This ADR does not require multi-user support. However, using `plan_finalize` as an explicit signal **reduces ambiguity** even in single-user mode and improves reliability.
