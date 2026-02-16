# ADR-0007: Image Upload + Drag-and-Drop in Chat

- **Status:** Proposed
- **Date:** 2026-02-16
- **Owner:** Dashboard + Media pipeline team

## Context
Coding-agent UX expects multimodal input: users paste screenshots, drag files, and ask the model to analyze visual context (errors, UI states, diagrams).

Current chat input is text-only.

## Decision
Add **image attachments** to chat with two entry points:
1. file picker,
2. drag-and-drop.

Images are uploaded first, then referenced in outbound chat message payload.

## Scope
### In
- PNG/JPEG/WebP upload.
- Drag-and-drop over chat window.
- Preview thumbnails and remove-before-send.
- Message payload includes attachment metadata.
- Assistant responses can reference uploaded images.

### Out (future)
- PDFs/video uploads.
- In-chat image annotation tools.

## Target Architecture

### Frontend
- `ChatInput` gains:
  - hidden `<input type="file" accept="image/*" multiple>`
  - drag/drop handlers.
  - attachment preview strip.
- New API client:
  - `POST /api/uploads/images`
  - response: `{ id, url, mimeType, size, width, height }[]`
- Send payload structure via websocket or REST fallback:
  - `{ text, sessionId, attachments: [{ id, kind: "image" }] }`

### Backend
- New upload controller endpoint `POST /api/uploads/images`.
- Storage adapter for uploaded binaries (filesystem now, pluggable later).
- Validation:
  - max size per image,
  - max count per message,
  - allowed MIME types.
- Persist attachment references in message model.

### Domain
- Extend message metadata for attachments.
- Ensure tool/LLM adapters can include image references in request format per provider capabilities.

## UX Contract
- Dragging image over chat shows drop overlay.
- Uploaded images appear as thumbnails with remove (X).
- Sending message clears local attachment queue only on successful dispatch.

## Implementation Plan

### Phase A — Upload API + storage
- [ ] Add upload endpoint and media storage service.
- [ ] Return normalized metadata DTO.
- [ ] Add cleanup policy (TTL or session-linked lifecycle).

### Phase B — Chat input integration
- [ ] Add attach button + DnD area.
- [ ] Add preview/remove states.
- [ ] Upload before send; include refs in outbound payload.

### Phase C — Message rendering
- [ ] Render sent image thumbnails in user bubbles.
- [ ] Render assistant-generated image attachments when present.

### Phase D — Tests
- [ ] Backend validation tests (size/type/count).
- [ ] Frontend DnD tests.
- [ ] End-to-end: upload -> send -> persisted message refs.

## Security
- MIME sniffing + extension checks.
- Strip executable content and block SVG scripts.
- Enforce server-side limits regardless of client checks.

## Acceptance Criteria
- User can upload by click and by drag/drop.
- Images are visible before send and removable.
- Message with image arrives and persists reliably.
