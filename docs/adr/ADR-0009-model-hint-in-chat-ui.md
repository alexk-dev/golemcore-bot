# ADR-0009: Model Hint Visibility in Chat (Which model answered)

- **Status:** Proposed
- **Date:** 2026-02-16
- **Owner:** Routing + Dashboard team

## Context
Users need transparency: for each assistant response, UI should show which model/tier produced the answer.

Current UI has tier selector but does not clearly label per-message answering model.

## Decision
Expose response-level model metadata from backend and render a compact hint badge in assistant message bubble.

## Scope
### In
- Message metadata includes:
  - `modelId`
  - `tier`
  - `provider`
- Chat UI hint badge for assistant messages.
- Tooltip with extra routing info when available.

### Out (future)
- Full routing timeline visualization.
- Token/cost chart per message.

## Target Architecture

### Backend transport
For websocket events (`assistant_chunk`, `assistant_done`) include metadata:
- `model`: `{ id, tier, provider }`
- optional `routingReason`

Data source priority:
1. `TurnOutcome` / `OutgoingResponse` metadata,
2. context attributes populated by model router,
3. fallback `unknown`.

### Frontend
- Extend incoming message type with optional model metadata.
- Update `ChatWindow` state to store metadata per assistant message.
- `MessageBubble` renders hint badge, e.g.:
  - `coding · claude-3.7-sonnet`.

## UX Contract
- Badge visible on every assistant response.
- If stream switches model mid-turn, final badge uses actual model that produced final answer.
- If metadata unavailable, show neutral `model: unknown` only in debug mode (not noisy by default).

## Implementation Plan

### Phase A — Backend payload enrichment
- [ ] Extend websocket JSON schema for assistant messages.
- [ ] Populate model metadata in `WebChannelAdapter` payload.
- [ ] Ensure non-web channels unaffected.

### Phase B — Frontend rendering
- [ ] Extend chat message state shape.
- [ ] Render model hint badge in `MessageBubble` for assistant role.
- [ ] Add compact tooltip with provider/tier/model.

### Phase C — Tests
- [ ] Backend tests for payload serialization.
- [ ] Frontend tests for hint rendering and fallback.
- [ ] Integration test with streaming chunks.

## Acceptance Criteria
- Assistant messages visibly show answering model.
- No regression in existing chat streaming behavior.
- Metadata fields remain optional and backward-compatible.
