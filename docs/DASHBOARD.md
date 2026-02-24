# Dashboard Guide

How to use the built-in web dashboard for chat, sessions, plans, logs, and embedded IDE workflows.

## Access

- Base URL: `http://localhost:8080/dashboard`
- WebSocket chat endpoint (UI): `/ws/chat?token=...`
- WebSocket logs endpoint (Logs page): `/ws/logs?token=...&afterSeq=...`

### Main Routes

- `/dashboard/chat` (also `/dashboard/`) - chat workspace
- `/dashboard/sessions` - browse and maintain sessions
- `/dashboard/ide` - embedded code editor
- `/dashboard/logs` - live and buffered logs
- `/dashboard/settings` - runtime config and integrations
- `/dashboard/setup` - startup setup wizard (recommended setup flow)
- `/dashboard/skills` - skill library
- `/dashboard/prompts` - prompt configuration
- `/dashboard/analytics` - analytics view
- `/dashboard/diagnostics` - diagnostics utilities

## Authentication

The dashboard uses a single admin account.

- Username: `admin`
- Credentials file (persisted in workspace): `preferences/admin.json`

### First Run Password

On first startup (when `preferences/admin.json` does not exist), the bot generates a temporary admin password and prints it in logs:

```text
DASHBOARD TEMPORARY PASSWORD (change after first login!)
Password: <generated>
```

### Provide a Known Password (Optional)

If you want a predefined password (no temporary password in logs), set a plaintext password:

- Spring property: `bot.dashboard.admin-password`
- Env var: `BOT_DASHBOARD_ADMIN_PASSWORD`

The password is hashed automatically at startup and then stored in `preferences/admin.json`.

## Runtime Configuration

Most bot settings are edited in the dashboard and persisted to:

- `preferences/runtime-config.json`
- API: `GET /api/settings/runtime`, `PUT /api/settings/runtime`
- Plugin UI schemas API: `GET /api/settings/plugins/schemas`

Typical first setup:

1. Configure provider API keys and API types in `llm.providers`.
2. Choose tier models in `modelRouter`.
3. Optionally enable Telegram (`telegram.enabled`, `telegram.token`).

If startup setup is incomplete, chat remains available and dashboard shows a one-time session popup invitation to open `/dashboard/setup`.

### Plugin Settings UI (Declarative)

Plugin-related settings sections in `/dashboard/settings` are schema-driven:

- Schemas are defined in backend plugins as `PluginSettingsSectionSchema` contributions.
- Dashboard fetches schemas from `GET /api/settings/plugins/schemas`.
- Generic renderer builds form controls from schema field definitions.

Non-plugin settings sections can still use classic page components.

## Sessions and Active Conversation

Dashboard session APIs are under `/api/sessions`:

- `GET /api/sessions` - list sessions
- `GET /api/sessions/recent` - list recent sessions for a channel/client
- `GET /api/sessions/active` - resolve active conversation key
- `POST /api/sessions/active` - switch active conversation
- `POST /api/sessions` - create web session
- `GET /api/sessions/{id}` - session details/messages
- `POST /api/sessions/{id}/compact` - compact history
- `POST /api/sessions/{id}/clear` - clear messages
- `DELETE /api/sessions/{id}` - delete session

Notes:

- Web sessions are scoped by `clientInstanceId` and conversation key.
- The server maintains an active-session pointer and repairs stale pointers when possible.

## Plan Mode Panel

The chat sidebar uses `/api/plans` endpoints with a required `sessionId`:

- `GET /api/plans`
- `POST /api/plans/mode/on`
- `POST /api/plans/mode/off`
- `POST /api/plans/mode/done`
- `POST /api/plans/{planId}/approve`
- `POST /api/plans/{planId}/cancel`
- `POST /api/plans/{planId}/resume`

This keeps plan mode state and actions session-scoped in the dashboard.

## Embedded IDE

The dashboard includes a lightweight IDE based on CodeMirror and React Arborist.

### Features

- Tree view from filesystem workspace root (`bot.tools.filesystem.workspace`)
- Tree search and Quick Open dialog with recency/pin priority
- Multi-tab editing with dirty state tracking
- Create, rename, and delete from tree actions
- Syntax highlighting by extension
- Save and unsaved-close flow with confirmation
- Resizable file tree panel

### Keyboard Shortcuts

- Save: `Ctrl+S` / `Cmd+S`
- Quick Open: `Ctrl+P` / `Cmd+P`
- Close active tab: `Ctrl+W` / `Cmd+W`
- Prev/next tab: `Alt+Left` / `Alt+Right`

### Files API

All file endpoints require authenticated dashboard access.

- `GET /api/files/tree?path=` - list nodes for directory (`path` empty means workspace root)
- `GET /api/files/content?path=...` - get UTF-8 file content and metadata
- `POST /api/files/content` - create file
- `PUT /api/files/content` - save file
- `POST /api/files/rename` - rename/move path
- `DELETE /api/files?path=...` - delete file or directory recursively

Example create/save payload:

```json
{
  "path": "relative/path/to/file.txt",
  "content": "new content"
}
```

Rename payload:

```json
{
  "sourcePath": "old/name.txt",
  "targetPath": "new/name.txt"
}
```

### Files Security Model

- Paths are resolved relative to filesystem workspace root
- Absolute paths and path traversal are rejected
- Existing ancestry is checked with `toRealPath()` against workspace root
- Symlinks are not traversed in tree listing
- Workspace root cannot be renamed or deleted
- Non-UTF-8 files are rejected for editor reads
- Maximum editable file size is 2 MB

### Architecture References

- Backend: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/FilesController.java`
- Backend service: `src/main/java/me/golemcore/bot/domain/service/DashboardFileService.java`
- Frontend page: `dashboard/src/pages/IdePage.tsx`
- Frontend hooks/api: `dashboard/src/hooks/useFiles.ts`, `dashboard/src/api/files.ts`

See ADR: `docs/adr/0001-dashboard-embedded-ide.md`.

## Logs

The Logs page provides:

- Live stream over WebSocket (`/ws/logs`)
- Infinite scroll for buffered records (`GET /api/system/logs`)
- Client-side filters by level/logger/text

Runtime options:

- `bot.dashboard.logs.enabled`
- `bot.dashboard.logs.max-entries`
- `bot.dashboard.logs.default-page-size`
- `bot.dashboard.logs.max-page-size`

## MFA (Optional)

Dashboard admin account supports TOTP MFA:

- Status: `GET /api/auth/mfa-status`
- Setup: `POST /api/auth/mfa/setup`
- Enable: `POST /api/auth/mfa/enable`
- Disable: `POST /api/auth/mfa/disable`
