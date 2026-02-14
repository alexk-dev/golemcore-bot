# OutgoingResponse — Transport Contract (Variant B)

This document describes the **transport-oriented response contract** used by the pipeline to deliver messages to the user.

> **Goal:** make `ResponseRoutingSystem` a *pure transport executor*.
> The decision **what** to send must be made upstream (domain orchestration), while routing only decides **how** to send it (which channel port, ordering, error handling).

---

## 1) Motivation

Historically, the project relied on multiple implicit, overlapping “sources of truth” for outgoing user-facing output:

- `ContextAttributes.LLM_RESPONSE` (LLM content)
- `ContextAttributes.VOICE_REQUESTED` / `ContextAttributes.VOICE_TEXT`
- (in some flows) downstream systems inferred what to send by inspecting `LlmResponse` provider specifics

This created:

- brittle coupling between systems (routing depended on LLM internals),
- duplicated delivery paths,
- hard-to-test behavior,
- and unclear ownership for “final output”.

**Variant B** introduces a single transport contract: `OutgoingResponse`.

---

## 2) Definition

`OutgoingResponse` is a **transport-oriented value object** stored in `AgentContext`:

- `ContextAttributes.OUTGOING_RESPONSE` → `OutgoingResponse`

It represents the complete set of user-facing deliverables for a pipeline run.

### Current fields

- `text: String`
  - Plain text to send to the user.
- `voiceRequested: boolean`
  - Transport-level hint: attempt TTS delivery.
- `voiceText: String`
  - Explicit text to speak (if blank, routing may fall back to `text`).
- `attachments: List<Attachment>`
  - Files/photos to send after the main text/voice.
- `skipAssistantHistory: boolean` (default `true`)
  - Guard rail: transport must not mutate raw history.

---

## 3) Contract: Semantics and Invariants

### 3.1 Single Source of Truth (transport)

If `OutgoingResponse` exists in the context, it MUST be treated as **the single source of truth** for transport decisions.

Implications:

- `ResponseRoutingSystem` reads **only** from `OutgoingResponse`. No legacy attribute fallbacks exist.

### 3.2 Ordering

When `OutgoingResponse` is routed:

1. Send **text** (if present and not blank)
2. Send **voice** (if `voiceRequested == true` and non-blank text to speak is available)
3. Send **attachments** (if present)

This ordering is intentional:

- Text is the most reliable fallback.
- Voice is optional and can fail due to quotas.
- Attachments are sent after the main message to avoid “orphaned files” without context.

### 3.3 Raw history ownership

`OutgoingResponse` does **not** imply that the assistant message must be appended to raw history.

- Raw history is owned by **domain executors** (e.g., ToolLoop / AgentLoop / other domain systems).
- `ResponseRoutingSystem` must be **transport-only**.

`skipAssistantHistory` exists to make this intention explicit and to prevent regressions.

### 3.4 Skill transitions

If `AgentContext.skillTransitionRequest` is present, routing should skip sending user-facing output.

Rationale:

- A pipeline transition is a control-flow operation; sending output during transition can cause duplicate/confusing messages.

---

## 4) Responsibilities by Layer (Hexagonal)

### Domain orchestration (upstream)
**Examples:** `ToolLoopSystem`, `AgentLoop`, plan systems.

Responsibilities:

- decide what the user should receive,
- aggregate multi-step outcomes (LLM + tools + policies),
- build a complete `OutgoingResponse` (text/voice/attachments),
- ensure feedback guarantee policies (if needed),
- write raw history **once** (canonical log).

### Transport execution (downstream)
**System:** `ResponseRoutingSystem`

Responsibilities:

- resolve `ChannelPort` by `(channelType, chatId)`
- send text / voice / attachments according to ordering
- handle channel exceptions and store best-effort routing error (`ContextAttributes.ROUTING_ERROR`)
- never mutate raw history

---

## 5) Attachment Pipeline (completed)

Attachments flow through the pipeline as first-class values:

1. `ToolCallExecutionService.extractAttachment()` detects attachments from tool results and returns them as `Attachment` objects.
2. `ToolCallExecutionResult` carries `extractedAttachment` alongside the tool result.
3. `ToolExecutionOutcome` carries the attachment through the tool loop.
4. `DefaultToolLoopSystem` accumulates attachments per turn and includes them in `OutgoingResponse`.
5. `ResponseRoutingSystem` sends attachments **only** from `OutgoingResponse.attachments`.

---

## 6) Architecture notes

### 6.1 Routing is now OutgoingResponse-only
`ResponseRoutingSystem` has no legacy fallback paths. It reads exclusively from `OutgoingResponse`.

Voice prefix detection and auto-voice logic are handled upstream in `AgentLoop.prepareOutgoingResponse()`, which builds the `OutgoingResponse` from LLM response attributes before routing runs.

### 6.2 Tests: enforced "transport-only" invariants
BDD/contract tests assert:

- routing sends only based on `OutgoingResponse`
- routing does not append assistant messages to session history
- routing ignores any unknown attributes (regression guard)

---

## 7) Examples

### Text-only

```java
context.setAttribute(ContextAttributes.OUTGOING_RESPONSE,
    OutgoingResponse.builder().text("Hello").build());
```

### Voice-only

```java
context.setAttribute(ContextAttributes.OUTGOING_RESPONSE,
    OutgoingResponse.builder()
        .voiceRequested(true)
        .voiceText("Short spoken summary")
        .build());
```

### Text + attachments

```java
Attachment screenshot = Attachment.builder()
    .type(Attachment.Type.IMAGE)
    .data(bytes)
    .filename("screenshot.png")
    .mimeType("image/png")
    .build();

context.setAttribute(ContextAttributes.OUTGOING_RESPONSE,
    OutgoingResponse.builder()
        .text("Here is the screenshot")
        .attachment(screenshot)
        .build());
```

---

## 8) Non-goals

- This contract is not meant to preserve provider-specific LLM metadata.
  That belongs to **raw history + request-time views**.
- This contract is not a replacement for plan/loop domain state.
  It only represents what should be sent outward.
