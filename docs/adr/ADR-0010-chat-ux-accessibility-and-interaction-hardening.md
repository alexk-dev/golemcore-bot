# ADR-0010: Chat UX Accessibility and Interaction Hardening

- **Status:** Proposed
- **Date:** 2026-02-16
- **Owners:** UI/UX + Frontend + Backend API
- **Related:**
  - ADR-0006 (CLI commands + autocomplete)
  - ADR-0007 (image upload + DnD)
  - ADR-0008 (voice recording + ElevenLabs)
  - ADR-0009 (message rendering and model hints)

---

## Context

Current chat UX delivers core functionality (commands, images, voice, long history), but the interaction layer has quality gaps that impact reliability and user confidence:

1. **Accessibility deficits (A11y)**
   - Icon-only buttons without robust accessible naming.
   - Command autocomplete lacks semantic combobox/listbox roles.
   - Live state changes (uploading/recording/transcribing) are not announced via assistive tech.
   - Clickable badge used as action control.

2. **Command input friction**
   - Keyboard behavior can trigger accidental sends.
   - Command selection and acceptance behavior is inconsistent with user expectation.
   - Weak visual distinction between “chat text mode” and “command mode”.

3. **Error hierarchy and recovery are weak**
   - Advisory warnings and blocking errors are visually mixed.
   - Error copy is not consistently actionable.

4. **Image attachment UX lacks trust signals**
   - No explicit drag-over affordance.
   - No per-file upload status.
   - Removal behavior should be deterministic per attachment instance.

5. **Voice UX lacks explicit lifecycle controls**
   - Toggle-only recording interaction; no clear stop/cancel flow.
   - No recording timer.
   - No user confirmation step for transcript insertion.

6. **Message list behavior under high volume**
   - Current lazy prepend strategy is acceptable but requires stricter anchoring and scroll policy to avoid jumps.

7. **Information architecture in toolbar**
   - Advanced options (tier/force) are too prominent for primary chat flow.

8. **Renderer contract fragility**
   - Parsing inline markdown-like patterns via ad-hoc regex in bubbles will be brittle as features grow.

These issues are not blockers individually, but together reduce usability quality, accessibility compliance, and perceived product maturity.

---

## Decision

Adopt a **state-driven interaction model** for chat UI with explicit accessibility semantics, deterministic keyboard behavior, and structured presentation for command, attachment, and voice workflows.

### Decision pillars

1. **Accessibility-first UI contract**
   - All interactive controls must be semantic and keyboard-operable.
   - Live operational states must be announced (`aria-live`).
   - Command autocomplete must follow combobox/listbox patterns.

2. **Mode-aware composer UX**
   - Composer has explicit visual and behavioral modes:
     - Text mode
     - Command mode (`/` prefix)
     - Voice recording mode
     - Upload-in-progress mode

3. **Error model split**
   - Distinguish:
     - `ADVISORY` (non-blocking; e.g., missing command args)
     - `BLOCKING` (prevents action; e.g., invalid file type)
     - `RECOVERABLE` (retry/correct guidance)

4. **Deterministic media workflows**
   - Image and voice flows show progress, explicit transitions, and cancellation affordances.

5. **Progressive information architecture**
   - Advanced controls (tier/force) move into secondary panel/popover.

6. **Typed message rendering contract**
   - Move toward structured message parts (text/image/diff/metadata) from backend contract over time.
   - Keep regex parsing only as transitional fallback.

---

## Target UX Architecture

### 1) Chat Composer state machine

`IDLE -> TYPING_TEXT -> COMMAND_MODE -> (UPLOADING|RECORDING|TRANSCRIBING) -> READY_TO_SEND`

Rules:
- `/` at start enters `COMMAND_MODE`.
- `Esc` in command list closes suggestions but keeps text.
- `Enter` behavior:
  - If command option highlighted and popup open → accept command.
  - Else submit message.
- `Shift+Enter` always inserts newline.
- Advisory missing args does not block submit.

### 2) Accessible command autocomplete

- Input: `role="combobox"`, `aria-expanded`, `aria-controls`.
- Popup: `role="listbox"`.
- Item: `role="option"`, active option via `aria-activedescendant`.
- Keyboard:
  - `ArrowUp/ArrowDown` navigate
  - `Enter` accept
  - `Tab` accept
  - `Esc` dismiss

### 3) Attachment UX contract

- Drag-over zone with active visual state (“Drop images to attach”).
- Per-file upload chip states:
  - pending
  - uploading (progress)
  - uploaded
  - failed (retry/remove)
- Remove action uses stable `attachmentClientId` (not server id only).

### 4) Voice UX contract

- Controls: `Record`, `Stop`, `Cancel` (explicit actions, not only toggle icon).
- Visible elapsed timer while recording.
- Post-transcription insertion confirmation:
  - `Insert transcript`
  - `Discard`
  - optional `Replace existing text` toggle.

### 5) Error/Status presentation

- Advisory panel (neutral): command hints, missing args.
- Error panel (warning/danger): upload/voice failures with action hints.
- Live region for async status updates (uploading/transcribing completed/failed).

### 6) Message list behavior

- Preserve anchor while prepending old messages.
- Auto-scroll only if user is near bottom.
- When user scrolled up, show “new messages” pill instead of forced scroll.

### 7) Toolbar simplification

- Keep connection status visible.
- Move model tier + force into “Advanced” popover/gear menu.
- Add inline help text for Force semantics.

---

## Detailed Implementation Plan

## Phase A — Accessibility and Semantics (must)

- [ ] Replace non-semantic clickable badge with `<button>`.
- [ ] Add `aria-label` for image/mic/send/remove controls.
- [ ] Add live region for async states (`uploading`, `recording`, `transcribing`, `errors`).
- [ ] Implement combobox/listbox semantics in `CommandAutocomplete` + input.
- [ ] Ensure visible focus styles for keyboard navigation.

Files (expected):
- `dashboard/src/components/chat/ChatInput.tsx`
- `dashboard/src/components/chat/CommandAutocomplete.tsx`
- `dashboard/src/components/chat/ChatWindow.tsx`
- `dashboard/src/styles/*.scss`

## Phase B — Keyboard and Composer Behavior (must)

- [ ] Introduce explicit key handling policy (`Enter`, `Tab`, `Esc`, arrows).
- [ ] Add command acceptance on `Enter` when suggestion highlighted.
- [ ] Add command mode visual indicator in composer.
- [ ] Avoid accidental submit when suggestion popup is active and no explicit acceptance intent.

## Phase C — Error Model and Copy (must)

- [ ] Introduce UI-level `ComposerNotice` model (`advisory | blocking | recoverable`).
- [ ] Split rendering of advisory vs error blocks.
- [ ] Standardize copy with actionable guidance (retry/remove/permissions).

## Phase D — Attachment UX Hardening (should)

- [ ] Add drag-over visual state and drop hint.
- [ ] Add per-file states and progress UI.
- [ ] Use stable client-generated IDs for attachment chips.
- [ ] Add retry for failed uploads.

## Phase E — Voice UX Hardening (should)

- [ ] Replace pure toggle with explicit record lifecycle controls.
- [ ] Add elapsed timer.
- [ ] Add transcript confirmation before insertion.
- [ ] Add clear failure actions for permission/device issues.

## Phase F — Message List and Toolbar IA (should)

- [ ] Add “new messages” indicator while user is reading older history.
- [ ] Tune auto-scroll policy to prevent disruptive jumps.
- [ ] Move tier/force into advanced section with help tooltip.

## Phase G — Structured Message Rendering Contract (future-proof)

- [ ] Define backend-friendly typed message part schema for web chat events:
  - `text`, `image`, `diff`, `meta`
- [ ] Migrate bubble renderer from regex parsing to typed parts.
- [ ] Keep compatibility fallback during migration period.

---

## Testing Strategy

### Unit/UI tests (Vitest + Testing Library)
- [ ] Keyboard navigation and acceptance behavior for autocomplete.
- [ ] `Esc` dismissal and focus persistence.
- [ ] A11y checks for roles/attributes on command list.
- [ ] Attachment flow: invalid type, oversize, retry/remove.
- [ ] Voice flow: permission denied, stop/cancel, transcript confirm.
- [ ] Notices rendering hierarchy (advisory vs blocking).

### Integration tests
- [ ] Chat send flow with image refs and command mode transitions.
- [ ] Scroll behavior with prepend and incoming messages.

### Non-functional
- [ ] Basic accessibility smoke checks (axe or equivalent).
- [ ] Performance sanity on long transcript and large message history.

---

## Backward Compatibility

- Existing command transport and APIs remain unchanged.
- Existing upload and voice endpoints remain unchanged.
- UI behavior improves without breaking message protocol.
- Structured message part schema (Phase G) will be additive first, then default.

---

## Risks and Mitigations

1. **Risk:** Increased UI complexity from state machine.
   - **Mitigation:** Keep reducer/state transitions explicit and covered by tests.

2. **Risk:** A11y regressions during rapid iteration.
   - **Mitigation:** Add role/aria assertions in tests; include accessibility checklist in PR template.

3. **Risk:** Voice UX inconsistency across browsers.
   - **Mitigation:** Capability detection + graceful fallback messages.

4. **Risk:** Renderer migration impacts existing content formatting.
   - **Mitigation:** Add compatibility parser and feature-flag rollout.

---

## Definition of Done

- [ ] All Phase A-C tasks implemented and tested.
- [ ] No non-semantic click-only controls remain in chat core flows.
- [ ] Command composer behavior predictable with keyboard-only usage.
- [ ] Upload and voice flows provide explicit progress + recoverability.
- [ ] Message list scroll behavior stable under prepend/stream updates.
- [ ] UX copy reviewed and consistent.
- [ ] ADR status updated from `Proposed` to `Accepted` after implementation.
