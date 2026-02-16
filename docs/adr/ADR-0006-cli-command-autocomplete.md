# ADR-0006: CLI-like Commands with Autocomplete in Web Chat

- **Status:** In Progress
- **Date:** 2026-02-16
- **Owner:** Dashboard + Runtime team

## Context
Current chat UX supports slash commands conceptually, but lacks a discoverable, keyboard-first command palette with autocomplete similar to coding-agent CLIs.

We need:
1. command discovery,
2. argument hints,
3. predictable execution contract,
4. compatibility with existing backend command routing.

## Decision
Implement a **command registry + autocomplete UI** in dashboard chat input, backed by optional server-provided command metadata.

## Scope
### In
- Slash command suggestions while typing (`/` trigger).
- Keyboard navigation (`↑/↓`, `Enter`, `Tab`, `Esc`).
- Inline argument hints and examples.
- Validation before send for known commands.
- Fallback pass-through for unknown commands.

### Out (future)
- Full shell-like completion for arbitrary file paths.
- Multi-step wizard forms for complex commands.

## Target Architecture

### Frontend
- New data model `CommandSpec`:
  - `name: string`
  - `description: string`
  - `args: { name, required, hint, enumValues? }[]`
  - `examples: string[]`
- New component `CommandAutocomplete` used by `ChatInput`.
- Trigger behavior:
  - show when input starts with `/`.
  - filter by prefix.
  - render hints for selected command.

### Backend
- New endpoint:
  - `GET /api/commands`
  - Returns list of `CommandSpec`.
- `CommandRouter` remains source of truth for execution.
- If endpoint unavailable, frontend uses local static registry fallback.

## UX Contract
- Typing `/pla` suggests `/plan on`, `/plan status`, `/plan approve`, etc.
- `Tab` autocompletes selected command.
- Hover/selection shows arguments and examples.
- Invalid required args shows local warning; user may still send.

## Implementation Plan

### Phase A — Registry and API
- [x] Define shared command DTO in backend web layer.
- [x] Add `GET /api/commands` in controller.
- [x] Populate from `CommandRouter` command map (or dedicated provider).

### Phase B — Frontend Autocomplete
- [x] Add `getCommands()` to `dashboard/src/api`.
- [x] Add React Query hook `useCommands()`.
- [x] Implement `CommandAutocomplete` component.
- [x] Integrate into `ChatInput` with keyboard navigation.

### Phase C — Validation + Hinting
- [x] Parse input into command + args.
- [x] Show required/missing args warnings.
- [ ] Render examples in suggestion panel.

### Phase D — Tests
- [ ] Unit tests for filter + keyboard reducer.
- [ ] Component tests for selection/autocomplete flow.
- [ ] Integration test: command selected -> message sent correctly.

## Security & Reliability
- Client-side completion is advisory only.
- Execution authorization remains server-side.
- Unknown commands are never blocked by UI hard-fail.

## Acceptance Criteria
- User can execute command from keyboard without mouse.
- `/` commands are discoverable with descriptions.
- Existing command behavior remains backward-compatible.
