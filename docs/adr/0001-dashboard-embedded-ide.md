# ADR-0001: Embedded lightweight IDE in Dashboard (CodeMirror + React Arborist)

- **Status:** Accepted
- **Date:** 2026-02-23
- **Owners:** Dashboard + Web API maintainers

## Context

We need an in-dashboard lightweight IDE for file access and code editing with syntax highlighting.

Current state:
- Dashboard does not expose a dedicated file management API for direct UI editing.
- Existing `filesystem` tool is designed for agent tool-calls, not a typed dashboard UI contract.
- We need a solution under permissive licensing requirements (MIT or Apache).

Constraints:
- Keep dashboard responsive and lightweight.
- Respect workspace sandbox boundaries and prevent path traversal/symlink escape.
- Follow existing dashboard architecture: `api/` → `hooks/` → `pages/` + reusable components.
- Keep implementation extensible for future LSP, search, and diff features.

## Decision

Implement an embedded IDE with:

1. **Editor:** CodeMirror 6 via `@uiw/react-codemirror` (MIT)
2. **File tree:** `react-arborist` (MIT)
3. **Backend API:** new `/api/files` endpoints for typed file operations
4. **Sandbox root:** reuse filesystem tool workspace (`bot.tools.filesystem.workspace`)

## Why this decision

### CodeMirror vs Monaco (for now)

- **CodeMirror chosen** for MVP because it is lighter and faster to integrate in the current dashboard.
- It provides robust syntax highlighting and extensibility with lower bundle/runtime overhead.
- Monaco remains a possible future upgrade path for deeper IDE features.

### Dedicated `/api/files` API

- Dashboard needs deterministic, typed contracts and predictable error handling.
- Reusing agent tools through LLM/tool loop would add unnecessary indirection for UI operations.
- A direct API simplifies frontend state management and improves UX.

## API scope (MVP)

- `GET /api/files/tree?path=` — returns directory tree from sandbox root
- `GET /api/files/content?path=` — returns text content + metadata
- `PUT /api/files/content` — writes file content

Initial non-goals for MVP:
- Full-text search
- Git operations
- LSP diagnostics/completions
- Binary editing

## Security model

- Resolve all paths relative to configured sandbox root.
- Reject absolute paths and traversal attempts.
- Validate resolved/real paths remain inside sandbox root.
- Reject directory reads as file content.
- Enforce text-file max size for editor reads.

## Frontend UX model (MVP)

- Left file tree panel.
- Multi-tab editor.
- Syntax highlighting by file extension.
- Dirty-state indicator and save action.
- Refresh tree action.

## Consequences

### Positive

- Fast delivery of practical IDE functionality.
- Clean separation between dashboard UI and agent tools.
- Strong security posture for file access.
- Future-ready architecture for richer IDE features.

### Trade-offs

- No advanced IDE semantics in MVP (LSP/goto definition/etc.).
- Additional backend API surface area to maintain.

## Follow-up roadmap

1. File create/rename/delete actions with confirmations.
2. Global search panel.
3. Diff editor and history snapshots.
4. Optional LSP bridge (e.g., Java via existing JDT LS infrastructure).

## Licensing

Selected libraries are MIT licensed:
- `codemirror`
- `@uiw/react-codemirror`
- `react-arborist`
