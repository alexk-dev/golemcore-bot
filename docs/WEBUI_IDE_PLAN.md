# WebUI IDE — Implementation Plan

Branch: `feat/webui-ide-improvement`
Approach: TDD — test first, implement, refactor.

Goal: turn the dashboard webui into a unified multi-panel workspace with three pillars — Files, Agent Chats (multi-session), Terminal — in a single layout. This is a **user environment** (the logged-in user's own machine-side UX), not an agent sandbox, so the terminal runs without extra isolation layers.

Legend: `[ ]` pending · `[~]` in progress · `[x]` done

---

## Phase 1 — Multi-session chat UI

Infra in `chatRuntimeStore` already supports multiple sessions; this phase adds the UI and persistence for switching between them.

- [x] 1.1 Store tests: extend `chatSessionStore` with `openSessionIds: string[]`, `openSession(id)`, `closeSession(id)`, persistence
- [x] 1.2 Implement store changes until 1.1 passes
- [x] 1.3 Component test for `ChatSessionTabs` (render, click switches active, close button, new-chat button)
- [x] 1.4 Implement `ChatSessionTabs.tsx` until 1.3 passes
- [x] 1.5 Wire `ChatSessionTabs` into `ChatWindow` above conversation
- [x] 1.6 Hotkey tests + implementation: `Alt+N` new, `Alt+W` close, `Alt+1..9` switch (Cmd+T/Cmd+W are browser-reserved in a web page, so we use Alt-based bindings)
- [x] 1.7 Playwright e2e: open 2 sessions, switch, close, verify persistence (WebSocket message send deferred — covered at unit level)
- [x] 1.R Review fixes: F1 input-focus guard on hotkeys, F2 closeSession unknown/invalid id, F3 storage normalization helper, F4 WAI-ARIA arrow/Home/End navigation with refs-based focus

## Phase 2 — Unified Workspace layout

Single `/workspace` route combining file explorer + editor + chat + (optional) terminal.

- [x] 2.1 Add `react-resizable-panels` dependency (v4.10.0 uses `Group`/`Panel`/`Separator`)
- [x] 2.2 Store tests: `workspaceLayoutStore` (panel sizes, chat/terminal visibility, persistence)
- [x] 2.3 Implement `workspaceLayoutStore` with clamping + `normalizeStoredWorkspaceLayout` helper
- [x] 2.4 Component test: `WorkspacePage` renders all three panes and respects visibility flags
- [x] 2.5 Implement `pages/WorkspacePage.tsx` reusing `IdePage` + `ChatWindow` + placeholder `TerminalPane`
- [x] 2.6 Add route `/workspace` in `App.tsx`; redirect `/ide` → `/workspace?focus=editor`, `/chat` → `/workspace?focus=chat`
- [x] 2.7 Sidebar nav entry for Workspace (replaces legacy IDE entry)
- [x] 2.8 Playwright e2e: workspace defaults, redirect coverage, toggle-and-reload persistence
- [x] 2.R Review fixes: R1 wire `Panel onResize` → `setChatSize`/`setTerminalSize`, R2 honor `?focus=chat|editor`, R5 `embedded` prop on `ChatWindow` (drop CSS coupling), R6 `role="toolbar"` + `aria-label`, R7 `clampSize` per-field fallback

## Phase 3 — Terminal

User-facing shell — no sandboxing. Uses pty4j on the JVM side, xterm.js on the front.

### 3a — Backend (pty4j + WebSocket)

- [x] 3.1 Add pty4j dependency to `pom.xml`
- [x] 3.2 JUnit: `TerminalSessionTest` — create PTY, write input, read output, resize, close
- [x] 3.3 Implement `TerminalSession` wrapper around pty4j
- [x] 3.4 JUnit: `TerminalConnectionTest` — protocol parsing (`input`/`resize`/`close` → `output`/`exit`), lifecycle cleanup on disconnect (+ `WebSocketConfigTest` covers route + JWT wiring)
- [x] 3.5 Implement `TerminalWebSocketHandler` + register route (`/ws/terminal`)
- [~] 3.6 Integration test: covered by `TerminalConnectionTest` (real PTY running `echo` and decoding `output` frames) — skipping a separate WebFlux WS integration test

### 3b — Frontend (xterm.js)

- [x] 3.7 Add `@xterm/xterm`, `@xterm/addon-fit`, `@xterm/addon-web-links`
- [x] 3.8 Store test: `terminalStore` — tabs, active terminal, WS status
- [x] 3.9 Implement `terminalStore`
- [x] 3.10 Component test: `TerminalPane` — mounts xterm, writes incoming frames, sends input on keypress
- [x] 3.11 Implement `components/terminal/TerminalPane.tsx` + `TerminalTabs.tsx`
- [x] 3.12 Wire terminal bottom panel into `WorkspacePage`, toggle `Ctrl+` `` ` ``
- [x] 3.13 Playwright e2e: open terminal, create second tab, close tab, Ctrl+` toggle

## Phase 4 — Editor integrations

- [x] 4.1 Inline diff accept/reject using `@codemirror/merge` for agent edits
- [x] 4.2 `@`-mention file autocomplete in `ChatInput` (driven by `getFileTree`)
- [x] 4.3 Pass `ideStore.openedTabs` as context in chat turns
- [x] 4.4 Agent command `/run` → send to active terminal via `terminalStore`

---

## TDD protocol

Every task follows: **red** (failing test) → **green** (minimum impl) → **refactor**. A task is `[x]` only when:

1. Its test(s) pass.
2. Full `npm run test` (or JUnit module) is green.
3. Typecheck/lint clean for touched files.
