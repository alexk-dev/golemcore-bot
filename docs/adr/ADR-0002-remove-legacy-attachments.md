# ADR-0002: Remove Legacy Attachments; Use `OutgoingResponse.attachments` as the Only Transport Path

- **Status:** Accepted (implementation in progress)
- **Date:** 2026-02-14
- **Related docs:** [`docs/OUTGOING_RESPONSE.md`](../OUTGOING_RESPONSE.md)

---

## 1) Goal

Fully remove the legacy “pending attachments” mechanism and migrate to a clean, explicit architecture where:

- **Domain/orchestration** decides *what* the user should receive (text, voice intent, attachments).
- **Transport routing** (`ResponseRoutingSystem`) only executes delivery.
- **Attachments are delivered only via `OutgoingResponse.attachments`**.

This makes the system deterministic, testable, and consistent with the Hexagonal architecture boundaries.

---

## 2) Context / Problem

Historically, attachments (screenshots, files) were delivered through an implicit side-channel ("pending attachments"):

- Tools / tool execution could extract attachments and store them out-of-band.
- `ResponseRoutingSystem` attempted to discover and send them.

This created multiple issues:

1. **Multiple sources of truth** for outgoing data (text from `LLM_RESPONSE`, voice from attributes, attachments from another place).
2. **Tight coupling** between routing and upstream internals.
3. **Hidden side effects** and unclear ownership (who guarantees once-only delivery, ordering, and lifecycle?).
4. **Harder testing** (routing tests depended on implicit context state).

We already adopted Variant B: `OutgoingResponse` is the single transport contract.

---

## 3) Decision

### 3.1 Transport contract

Introduce and standardize on a single transport contract:

- `ContextAttributes.OUTGOING_RESPONSE` → `OutgoingResponse`

### 3.2 Single source of truth

If `OutgoingResponse` is present:

- `ResponseRoutingSystem` MUST treat it as the **only source of truth** for what to send (text/voice/attachments).
- Legacy pending-attachment queues MUST NOT be read.

### 3.3 Ownership

- **Domain executors** (ToolLoop / AgentLoop / plan systems) own:
  - producing and aggregating attachments,
  - producing the final outbound payload (`OutgoingResponse`),
  - writing raw history.

- **ResponseRoutingSystem** owns only:
  - channel selection,
  - sending text/voice/attachments in the defined order,
  - best-effort error recording.

---

## 4) Target Architecture

### 4.1 Data flow (high level)

1. Tools execute and may yield attachment data.
2. Domain orchestration aggregates deliverables for a turn.
3. Domain orchestration sets `OutgoingResponse` on the context.
4. `ResponseRoutingSystem` routes `OutgoingResponse`.

### 4.2 Responsibilities

#### Domain layer (orchestration)
- Decide **what** to send.
- Aggregate:
  - `text`
  - `voiceRequested` / `voiceText` (transport hint)
  - `attachments`
- Store as `OutgoingResponse`.

#### Transport layer (`ResponseRoutingSystem`)
- Decide **how** to send.
- Ordering:
  1) text
  2) voice (optional)
  3) attachments
- Never mutate raw history.

---

## 5) Migration Plan

### Phase 1 — Transport cutover (DONE)
- Remove any legacy attachment delivery path from `ResponseRoutingSystem`.
- Update routing tests to assert attachments are sent from `OutgoingResponse.attachments`.

### Phase 2 — Make attachments a first-class tool execution outcome (NEXT)
Today `ToolCallExecutionService.extractAttachment(...)` can detect attachments but does not publish them.

We will:

1. Extend tool execution result objects to carry extracted attachments explicitly.
   - Recommended: add `List<Attachment> extractedAttachments` to `ToolCallExecutionResult`.
2. Make `ToolCallExecutionService.execute(...)` return those attachments alongside the tool result.
3. Make the domain orchestrator (preferably `ToolLoopSystem`) aggregate attachments per turn and attach them to `OutgoingResponse`.

This removes the last remaining “implicit side-channel” temptation.

### Phase 3 — Remove remaining legacy API surface (cleanup)
- Remove any leftover references/documentation mentioning pending attachments.
- Add a regression test:
  - If a (hypothetical) legacy pending queue is present, routing must ignore it.

---

## 6) Non-Goals

- This ADR does not define provider-specific LLM metadata preservation.
  That belongs to **raw history + request-time views**.
- This ADR does not mandate a specific channel behavior for attachment failures.
  Routing remains best-effort and records `ROUTING_ERROR`.

---

## 7) Expected Outcomes

- A single, explicit transport contract for all channels.
- Clear separation of concerns:
  - domain decides *what*
  - transport executes *how*
- Stronger invariants and simpler tests.
- Fewer regressions during tool-loop refactors.
