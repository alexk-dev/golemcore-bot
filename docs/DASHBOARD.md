# Dashboard Guide

How to use the built-in web dashboard for chat, setup, settings, scheduler management, sessions, logs, and embedded IDE workflows.

## Access

- Base URL: `http://localhost:8080/dashboard`
- WebSocket chat endpoint: `/ws/chat?token=...`
- WebSocket logs endpoint: `/ws/logs?token=...&afterSeq=...`

### Main Routes

- `/dashboard/chat` (also `/dashboard/`) - chat workspace
- `/dashboard/setup` - startup setup wizard
- `/dashboard/settings` - runtime config, model catalog, and integrations
- `/dashboard/webhooks` - webhook runtime settings and delivery tracking
- `/dashboard/scheduler` - cron schedules for auto mode goals/tasks
- `/dashboard/sessions` - browse and maintain sessions
- `/dashboard/ide` - embedded code editor
- `/dashboard/logs` - live and buffered logs
- `/dashboard/skills` - skill library
- `/dashboard/prompts` - prompt configuration
- `/dashboard/analytics` - analytics view
- `/dashboard/self-evolving` - SelfEvolving workspace
- `/dashboard/diagnostics` - diagnostics utilities

## Authentication

The dashboard uses a single admin account.

- Username: `admin`
- Credentials file: `preferences/admin.json`

### First Run Password

On first startup, if `preferences/admin.json` does not exist, the bot generates a temporary admin password and prints it in logs:

```text
DASHBOARD TEMPORARY PASSWORD (change after first login!)
Password: <generated>
```

### Provide a Known Password

If you want a predefined password, set:

- Spring property: `bot.dashboard.admin-password`
- Env var: `BOT_DASHBOARD_ADMIN_PASSWORD`

The password is hashed and then persisted in `preferences/admin.json`.

## Chat Transport

The dashboard chat is session-scoped and backed by `/ws/chat`. The web channel emits three message shapes:

- `assistant_chunk` - incremental text chunks
- `assistant_done` - finalized assistant message
- `system_event` - typing and runtime progress events

`system_event` now also carries runtime progress from the resilient tool loop. The payload includes `runtimeEventType` values such as:

- `LLM_STARTED` / `LLM_FINISHED`
- `TOOL_STARTED` / `TOOL_FINISHED`
- `RETRY_STARTED` / `RETRY_FINISHED`
- `COMPACTION_STARTED` / `COMPACTION_FINISHED`
- `TURN_STARTED` / `TURN_FINISHED` / `TURN_FAILED`

This is how the web UI can surface tool/LLM progress without waiting for the final reply.

## Setup Wizard

`/dashboard/setup` is the recommended first-run flow. It focuses on two required checks:

1. Configure at least one LLM provider with an API key.
2. Choose routing/tier models that match the configured providers.

Chat remains available even if setup is incomplete, but the wizard is the fastest way to reach a valid routing configuration.

## Settings Catalog

The Settings page is now organized into catalog blocks instead of a flat tab list:

- `Core` - general settings, LLM Providers, Model Catalog, Model Router
- `Extensions` - Plugin Marketplace plus plugin-contributed settings pages
- `Tools` - filesystem, shell, automation, goal management, voice routing
- `Runtime` - memory, skills, turn budget, usage, MCP, auto mode, updates
- `Runtime` also includes `SelfEvolving` for judge tiers, promotion policy, benchmark harvesting, and trace payload override
- `Advanced` - rate limiting, security, compaction

### SelfEvolving Settings

`Settings -> SelfEvolving` controls the native eval and promotion runtime:

- keep the whole pipeline disabled by default until explicitly enabled
- enable or disable the `SelfEvolving` pipeline
- choose judge tiers for primary, tiebreaker, and evolution passes
- configure approval-gated or automatic promotion behavior
- enable benchmark harvesting from production runs
- force trace payload capture for evaluation-relevant spans while keeping redaction active
- tune tactic search defaults such as `BM25-only` versus `hybrid`, rerank tier, personalization, negative memory, and optional embeddings

For local embeddings, `Settings -> Self-Evolving -> Tactics` is also the main diagnostics surface. It shows:

- whether the runtime is external or managed by the bot
- current runtime state such as ready, startup timeout, restart backoff, or outdated version
- selected model and whether that model is installed
- last degraded reason
- restart attempts and next retry timing when the bot owns the runtime
- install gating for the selected embedding model

Lifecycle rules exposed in the UI:

- the bot never installs or updates `ollama` itself
- the bot only stops a local `ollama` process if it started that process itself
- the first `5s` of startup are reserved for local runtime readiness; after that the bot stays healthy even if embeddings remain degraded
- model install is available only when the runtime is ready

Startup-only `bot.self-evolving.bootstrap.*` overrides win over editable runtime settings for the effective process configuration. This is the right place to force tactic search on a deployed worker without rewriting its stored preferences.

### SelfEvolving Workspace

`/dashboard/self-evolving` is the operator workspace for bot-local `SelfEvolving` state.

It shows:

- overview cards for runs, candidates, campaigns, and approval pressure
- a tactic search workspace that stays separate from `/dashboard/skills`
- search state banners for `Hybrid`, `BM25-only`, and `Embeddings degraded`
- readonly tactic result ranking with operator-visible `BM25`, vector, `RRF`, quality prior, `MMR`, negative-memory, personalization, and reranker signals
- a `Why this tactic` panel that exposes `success rate`, `benchmark win rate`, `regression flags`, `promotion state`, `recency`, and `golem-local usage success`
- a workspace-first artifact browser where `artifactStreamId` is the canonical identity for deep links, compare flows, and evidence joins
- an artifact catalog left rail for evolved `skills`, `prompts`, `routing policies`, `tool policies`, and `memory policies`
- a lineage rail that shows the full `candidate -> approved -> active -> reverted` history as rollout nodes over immutable content revisions
- diff, evidence, and impact tabs that combine semantic diff, raw diff fallback, benchmark impact, promotion history, and run-level evidence
- readonly run list with verdict summaries as supporting context
- candidate queue and current promotion states as supporting context
- benchmark lab campaigns harvested from production or curated suites, with selection wired back into the chosen artifact stream

The workspace is bot-local and remains the primary working screen. Hive mirrors the same artifact workspace as a readonly inspection view when the golem is connected.
Tactic search follows the same rule: tune and inspect it in the bot first, then use Hive as the shared readonly observation window.

Diff behavior is split deliberately:

- bounded rollout and default compare pairs are precomputed for fast navigation
- arbitrary revision compare is computed on demand and cached by normalized content hash pair
- transition compare remains node-aware, so rollout metadata and evidence do not get flattened into plain revision diff output

Plugin settings pages are discovered dynamically from `/api/plugins/settings/catalog`.

### Memory Settings

The `Runtime -> Memory` section is where you tune how much memory the model sees and how aggressively it is recalled.

Important controls:

- presets for common profiles such as fast coding, balanced coding, deep coding, research, ops, or disabled memory
- prompt budget and per-layer recall limits
- progressive disclosure mode: `index`, `summary`, `selective_detail`, `full_pack`
- prompt style: `compact`, `balanced`, `rich`
- reranking profile and diagnostics verbosity

Practical rule:
If memory feels noisy, start by switching to `summary`, lowering `*TopK`, or raising `detailMinScore`. If memory feels too vague, try `selective_detail` or a stronger preset such as `coding_deep`.

### LLM Providers

The `LLM Providers` section manages provider profiles under `llm.providers` in runtime config:

- API key
- `apiType`
- `baseUrl`
- request timeout

These profiles are reused by both the Model Router and the Model Catalog.

### Model Catalog

The `Model Catalog` section edits `models/models.json` through `/api/models`.

Available API endpoints:

- `GET /api/models`
- `PUT /api/models`
- `POST /api/models/{id}`
- `DELETE /api/models/{id}`
- `POST /api/models/reload`
- `GET /api/models/available`
- `GET /api/models/discover/{provider}`

Important behaviors:

- The catalog is grouped by provider profile.
- Live suggestions are fetched on demand from the selected provider API.
- Discovery supports configured OpenAI-compatible, Anthropic, and Gemini provider profiles.
- When the same raw model id exists under multiple providers, the UI can keep a provider-scoped id such as `provider/model`.

### Model Router

The `Model Router` section consumes only providers that are actually API-ready. The dashboard filters model pickers to providers with configured API keys and resolves tier models from `/api/models/available`.

### Plugin Marketplace

The `Plugin Marketplace` section is backed by:

- `GET /api/plugins/marketplace`
- `POST /api/plugins/marketplace/install`
- `POST /api/plugins/reload`
- `POST /api/plugins/{pluginId}/reload`
- `GET /api/plugins/settings/catalog`
- `GET /api/plugins/settings/sections/{routeKey}`
- `PUT /api/plugins/settings/sections/{routeKey}`
- `POST /api/plugins/settings/sections/{routeKey}/actions/{actionId}`

Important behaviors:

- Official integrations are installed as runtime plugins, not bundled core features.
- Marketplace metadata comes from the configured local repository directory when present, otherwise from the configured remote repository.
- Backend installation verifies the artifact, writes it into the plugin directory, and reloads the plugin runtime.
- Plugin-specific configuration pages are exposed as normal Settings sections after installation.

## Skills Page

`/dashboard/skills` is split into two workspaces:

- `Installed` for editing loaded skills
- `Marketplace` for browsing and installing skill artifacts

Backend API:

- `GET /api/skills`
- `GET /api/skills/detail?name=...`
- `PUT /api/skills/detail?name=...`
- `DELETE /api/skills/detail?name=...`
- `GET /api/skills/detail/mcp-status?name=...`
- `GET /api/skills/marketplace`
- `POST /api/skills/marketplace/install`

Important behaviors:

- The marketplace is artifact-based, not file-based.
- Each artifact belongs to a maintainer namespace such as `golemcore/code-reviewer`.
- Artifacts can be either `skill` or `pack`.
- Pack artifacts install multiple runtime skills such as `golemcore/devops-pack/deploy-review`.
- The marketplace source can be changed directly in the UI:
  - sandbox path inside the tool workspace
  - local repository path on disk
  - remote repository URL + branch
- Sandbox mode accepts a path relative to the bot sandbox workspace.
- Sandbox mode accepts either the repository root or the `registry/` directory itself.
- Local directory mode accepts either the repository root or the `registry/` directory itself.
- Repository mode defaults to the configured `golemcore-skills` repository.
- Remote repository mode currently expects a GitHub repository URL.
- Installed marketplace artifacts are written under `workspace/skills/marketplace/...` and reloaded immediately.
- The `Installed` tab can edit both manual skills and marketplace-installed skills because the backend resolves updates by stored skill location, not by assuming `name/SKILL.md`.

## Webhooks Page

`/dashboard/webhooks` is a dedicated workspace (outside Settings) for:

- enabling/disabling webhook runtime,
- managing bearer token / payload limits / timeout,
- creating custom mappings (`/api/hooks/{name}`),
- monitoring callback delivery attempts,
- retrying failed callback deliveries,
- sending test callback payloads.

Delivery data uses authenticated dashboard APIs under `/api/webhooks/deliveries*`.

## Scheduler

`/dashboard/scheduler` is the UI for cron-based auto mode schedules.

Backend API:

- `GET /api/scheduler`
- `POST /api/scheduler/schedules`
- `DELETE /api/scheduler/schedules/{scheduleId}`

The UI lets you target either a goal or a task and create one of these schedule patterns:

- `daily`
- `weekdays`
- `weekly`
- `custom`

Each schedule tracks:

- target id and label
- cron expression
- `maxExecutions`
- `executionCount`
- `lastExecutedAt`
- `nextExecutionAt`

If auto mode is disabled in runtime config, the Scheduler page stays visible but creation is blocked.

## Sessions and Active Conversation

Dashboard session APIs are under `/api/sessions`:

- `GET /api/sessions`
- `GET /api/sessions/recent`
- `GET /api/sessions/active`
- `POST /api/sessions/active`
- `POST /api/sessions`
- `GET /api/sessions/{id}`
- `POST /api/sessions/{id}/compact`
- `POST /api/sessions/{id}/clear`
- `DELETE /api/sessions/{id}`

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

Plan mode stays session-scoped in the dashboard.

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

- `GET /api/files/tree?path=`
- `GET /api/files/content?path=...`
- `POST /api/files/content`
- `PUT /api/files/content`
- `POST /api/files/rename`
- `DELETE /api/files?path=...`

Example save payload:

```json
{
  "path": "relative/path/to/file.txt",
  "content": "new content"
}
```

### Files Security Model

- Paths are resolved relative to the filesystem workspace root
- Absolute paths and traversal are rejected
- Existing ancestry is checked with `toRealPath()` against the workspace root
- Symlinks are not traversed in tree listing
- Workspace root cannot be renamed or deleted
- Non-UTF-8 files are rejected for editor reads
- Maximum editable file size is 2 MB

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

- `GET /api/auth/mfa-status`
- `POST /api/auth/mfa/setup`
- `POST /api/auth/mfa/enable`
- `POST /api/auth/mfa/disable`
