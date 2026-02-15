# ADR-00XX: Deterministic "Work by Plan" via `plan_set_content` (SSOT Markdown)

- **Status:** Accepted
- **Date:** 2026-02-15
- **Decision Drivers:** Reliability, testability (BDD/TDD), hexagonal cleanliness, elimination of brittle text heuristics, ensure plan survives compaction, **single active plan UX**.

## Context
GolemCore Bot supports a planning workflow. The original implementation relied on an implicit heuristic:

> *“Finalize the plan when the LLM returns plain text with no tool calls.”*

This is fragile:
- Some models emit summary text **together with tool calls**.
- Some models keep emitting tool calls indefinitely (“just one more step”).
- "No tool calls" is an **absence-based signal**, hard to reason about and test.

During the discussion we clarified the real product goal:

- We do **not** want to restrict tools during planning. We cannot predict which tools are useful for analysis while drafting a plan.
- The purpose of plan-specific tools is to **persist a canonical plan artifact** in a way that is robust to context growth and auto-compaction.

We also clarified UX constraints:
- This bot is **single-user/single-chat by design**.
- The user should not have to manage `planId`s in everyday UX.
- There must be a clear notion of when "work by plan" is **active** and when it is **over**, otherwise the plan becomes an unexpected artifact.

## Goal
Introduce a deterministic, testable, and architecturally clean mechanism to:
1. Start a "work by plan" mode on `/plan on`.
2. Allow iterative plan drafting and updates.
3. Persist the plan as a **single Markdown document (SSOT)** at `plan_set_content`.
4. Keep the plan reliably accessible even after compaction.
5. End the mode explicitly on `/plan done` or `/reset`.

## Decision
We define a **"Work by Plan"** mode ("plan work") that is enabled explicitly by the user via `/plan on` and disabled via `/plan done` or `/reset`.

The canonical plan is persisted only via an explicit tool call:
- **`plan_set_content(plan_markdown=...)`**

### SSOT of the plan
- **Plan SSOT is one Markdown document** (string).
- Any notion of "steps" is part of the Markdown text; we do not require a structured step list as the canonical representation.

### Updates before approval
We adopt **Variant B**:
- `plan_set_content` is allowed when the plan is already `READY` (draft is finalized but not approved yet).
- Repeated `plan_set_content` calls **overwrite** the stored Markdown draft.

### Editing during execution
While executing the plan, the user might want to revise it.
We support this by allowing `plan_set_content` in `EXECUTING` too:
- The system creates a **new plan revision** based on the previous one and marks the previous plan as cancelled/superseded.
- UX still exposes only a single "active plan".

### Plan access tool
We introduce a read tool:
- **`plan_get()`** — returns the current canonical plan Markdown.

`plan_get` is advertised **only when plan work is active**, to avoid the plan becoming an unexpected artifact.

## Invariants (must hold)
1. **Mode gating invariant:**
   - Plan tools (`plan_set_content`, `plan_get`) are advertised **only when plan work is active**.
2. **Execution invariant:**
   - If plan tools are called when plan work is inactive, execution is denied (policy failure) and plan state is not mutated.
3. **SSOT invariant:**
   - The only canonical plan content is `Plan.markdown` (a single Markdown string).
4. **Compaction safety invariant:**
   - As long as plan work is active, the system prompt must include enough information to recover the plan (at minimum: "use `plan_get`"), so the plan cannot be lost due to history truncation/compaction.
5. **Single-active-plan invariant:**
   - At any point, there is at most one "active" plan for UX and tool exposure.

## Target Architecture

### 1) Domain model changes
- `Plan` gains a new persisted field:
  - `markdown` (String) — canonical plan document.

`PlanStep` may remain for legacy/execution history, but it is **not** the SSOT for plan drafting.

### 2) Runtime state
`PlanService` owns:
- `planWorkActive` (boolean)
- `activePlanId` (String)

Rules:
- `/plan on` creates a new plan, sets `planWorkActive=true`, sets `activePlanId`.
- `/plan done` or `/reset` disables plan work and clears `activePlanId`.

### 3) Tools
#### 3.1 `plan_set_content`
- **Advertised:** only when plan work is active.
- **Input schema:**
  - `plan_markdown` (required string) — full canonical plan draft.
  - (optional) `title` (string) — may be used to set `Plan.title`.

- **Semantics:**
  - If status is `COLLECTING`: set `markdown`, move to `READY`.
  - If status is `READY`: overwrite `markdown` (update draft).
  - If status is `EXECUTING`: create a new plan revision (new plan), cancel/supersede old, set new as active, set `READY`.
  - If status is terminal or unsupported: deny.

#### 3.2 `plan_get`
- **Advertised:** only when plan work is active.
- **Execution:** returns `Plan.markdown` (and optionally metadata like status/title).

### 4) Pipeline systems
#### 4.1 ToolLoopExecutionSystem / ToolLoop
Tool loop executes LLM + tool calls as usual.

Important: plan tools are **control/read** tools; they should not cause external side effects.

#### 4.2 Plan finalization system
`PlanFinalizationSystem` finalizes/updates the plan draft when it sees `plan_set_content` tool call.

It extracts the tool arguments from `LLM_RESPONSE.toolCalls` and persists `Plan.markdown`.

#### 4.3 Context building
`ContextBuildingSystem`:
- Injects a **compact plan-mode context** when plan work is active.
- Advertises `plan_set_content` and `plan_get` only when plan work is active.

Minimum required context:
- "Plan work is active. Use `plan_get` to load the canonical plan. When you have a new draft, call `plan_set_content(plan_markdown=...)`."

## Detailed Implementation Plan (Phases)

### Phase 0 — ADR + prompt contract updates
- [ ] Update `PlanService.buildPlanContext()` text:
  - remove claims that tools are not executed
  - instruct to use `plan_get` + `plan_set_content(plan_markdown=...)`

### Phase 1 — Data model & persistence
- [ ] Add `Plan.markdown` persisted field.
- [ ] Ensure existing `plans.json` load/save remains compatible (missing field → null).

### Phase 2 — Tools
- [ ] Update `plan_set_content` schema to require `plan_markdown`.
- [ ] Add `plan_get` tool.
- [ ] Advertisement gating: only when plan work active.

### Phase 3 — Finalization behavior
- [ ] Update `PlanFinalizationSystem`:
  - trigger on `plan_set_content` tool call
  - extract `plan_markdown` (and optional `title`)
  - implement Variant B (overwrite draft when READY)
  - implement revision creation when EXECUTING

### Phase 4 — Commands / lifecycle
- [ ] `/plan on` must set **plan work active** and create the initial plan.
- [ ] `/plan done` must end plan work and clear active plan.
- [ ] `/reset` must end plan work and clear active plan.

### Phase 5 — Tests (BDD/TDD)
- [ ] `PlanSetContentToolTest`:
  - denied when plan work inactive
  - schema includes required `plan_markdown`
- [ ] `PlanGetToolTest`:
  - denied when plan work inactive
  - returns markdown when active
- [ ] `PlanFinalizationSystemTest`:
  - COLLECTING → READY with markdown persisted
  - READY → READY overwrite
  - EXECUTING → revision created, old cancelled
- [ ] Context builder tests:
  - plan tools advertised only when plan work active

## Consequences
### Positive
- Deterministic and testable plan persistence.
- Plan survives compaction because it is stored out-of-band and can be reloaded via `plan_get`.
- No artificial restriction of tools during drafting.
- Clear lifecycle boundaries: plan exists only when explicitly active.

### Trade-offs
- Requires the model to comply with a structured tool contract (`plan_set_content(plan_markdown=...)`).
- Adds additional domain surface (plan_get + revision semantics).

## Notes on Single-User Assumption
The single-user constraint simplifies state management (one active plan). The architecture still uses explicit signals and strict invariants to reduce ambiguity and improve debuggability.
