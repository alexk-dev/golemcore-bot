# CLI Client Implementation Plan

Branch: `feat/cli-client`
Base: `origin/main`
Approach: TDD — red, green, refactor for every behavioral slice.

Goal: implement `golemcore-bot cli` as a production-ready local coding-agent runtime adapter over the existing GolemCore runtime. The CLI must stay an inbound adapter and presentation layer: command parsing, terminal/TUI rendering, JSON/NDJSON output, and runtime attachment live at the edge; agent loop, sessions, memory, RAG, MCP, model routing, tracing, tools, and security remain in shared runtime/application layers.

Legend: `[ ]` pending · `[~]` in progress · `[x]` done

## Current Implementation Slice

- [x] Created the `feat/cli-client` worktree branch from `origin/main`.
- [x] Added a dedicated `golemcore-bot-cli` adapter module and wired it into the Maven reactor.
- [x] Added stable first-slice CLI DTO contracts, event taxonomy, permission/run/session/patch/trust records, and contract tests.
- [x] Added the first Picocli root command surface with global option binding, deterministic help, non-zero "not implemented" command stubs, `--version`, and structured `doctor` text/JSON output.
- [x] Split the first CLI slice into Picocli adapter, application input boundaries/use cases, domain command state, presenters, router catalog, and config packages with architecture tests.
- [x] Added launcher/runtime application dispatch so `golemcore-bot cli ...` is a first-class entrypoint next to `web`.
- [x] Updated module dependency architecture tests so runtime modules cannot depend on the CLI adapter.
- [x] Verified the slice with targeted tests and local Maven `clean verify`.
- [ ] Next implementation slice: project discovery, trust registry, config resolution, and real adapters behind the current command shell.

---

## TDD Protocol

- [ ] TDD.1 For every item below, write the smallest failing test first and observe the expected failure before implementation; commit only green slices unless an explicit red/green split is agreed.
- [ ] TDD.2 Implement only enough production code to pass the test.
- [ ] TDD.3 Refactor after green while preserving architecture boundaries.
- [ ] TDD.4 A checklist item is complete only when targeted tests, affected module tests, and architecture checks pass.
- [ ] TDD.5 Prefer fake ports and fake runtime adapters in tests; do not use live providers, real model calls, real MCP servers, or external network by default.
- [ ] TDD.6 For command behavior, assert observable output, exit code, stable DTO shape, and no prompt in non-interactive mode.
- [ ] TDD.7 For security-sensitive behavior, add a regression test before any permissive implementation change.

## Non-Negotiable Architecture Rules

- [x] A.1 CLI command classes are thin: parse args, build request DTO, call use case/input boundary, render presenter output, return exit code.
- [ ] A.2 Shared DTOs, ports, view models, and boundary contracts live in `golemcore-bot-contracts` or the appropriate runtime module, not in app-only adapters.
- [ ] A.3 Runtime/core modules never depend on CLI packages, Picocli, terminal libraries, HTTP clients used only by attach mode, or launcher classes.
- [x] A.4 Presenters never call filesystem, shell, network, model providers, MCP, Git, LSP, or storage directly.
- [x] A.5 Use cases depend on ports and shared models, not concrete local filesystem, shell, Git, LSP, MCP, terminal, or HTTP implementations.
- [ ] A.6 `AgentLoop`, context building, tool loop, memory, RAG, dynamic tier routing, tracing, and sessions are reused rather than bypassed.
- [ ] A.7 Public event/API DTOs do not expose `AgentContext` or adapter-internal types.
- [ ] A.8 Any cross-system pipeline keys introduced for CLI/coding-agent behavior use canonical `ContextAttributes` contracts.
- [x] A.9 Module placement is explicit and tested: prefer a dedicated `golemcore-bot-cli` adapter module, with shared contracts/use cases in contracts/runtime modules and Spring composition in `golemcore-bot-app`.
- [ ] A.10 Storage schemas are explicit, versioned where needed, and covered by compatibility tests before adapters write user or project state.

---

## Phase 0 — Architecture Guardrails and Contracts

- [x] 0.1 Red: update Maven dependency graph and architecture tests for explicit CLI placement, runtime-to-CLI dependency prohibition, presenter purity, and use-case-to-port rules.
- [x] 0.2 Green: add the minimal module/package skeleton required by the tests: `golemcore-bot-cli` for CLI adapters/presentation where feasible, shared use cases/ports in runtime/contracts modules, and app-only Spring wiring in `golemcore-bot-app`.
- [x] 0.3 Red: add package ownership tests for `adapter.inbound.picocli`, `adapter.inbound.tui`, `adapter.outbound.terminal`, `adapter.outbound.pty`, `adapter.outbound.localfs`, `presentation`, `router`, and `config`.
- [x] 0.4 Green: add placeholder packages/classes only where needed to satisfy ownership tests without implementing behavior early.
- [ ] 0.5 Red: add DTO serialization tests for `CliInvocation`, `RunRequest`, `RunResult`, `CliEvent`, `PermissionRequest`, `PermissionDecision`, `PatchSet`, `ProjectIdentity`, `ProjectTrust`, `CliSessionRef`, `AgentProfile`, `WorkspaceSnapshot`, `LspDiagnosticPack`, `ContextBudgetReport`, and `ToolExecutionRecord`.
- [ ] 0.6 Green: implement stable DTOs with Jackson-friendly constructors/builders and no framework-specific leakage.
- [ ] 0.7 Red: add event taxonomy/schema tests for `run.started`, `run.title.updated`, `assistant.delta`, `assistant.message.completed`, `plan.updated`, `context.budget.updated`, `context.hygiene.reported`, `memory.pack.loaded`, `rag.results.loaded`, `model.selected`, `tool.requested`, `tool.permission.requested`, `tool.started`, `tool.output.delta`, `tool.completed`, `patch.proposed`, `patch.applied`, `lsp.diagnostics.updated`, `terminal.session.started`, `run.cancelled`, `run.completed`, and `run.failed`.
- [ ] 0.8 Green: implement versionable `CliEvent` contract constants/builders and JSON/NDJSON compatibility fixtures.
- [x] 0.9 Red: add exit-code mapping tests for success, invalid args, config error, provider auth error, permission denial, tool failure, model failure, timeout, runtime unavailable, untrusted project, patch conflict, network/MCP failure, and verification failure.
- [x] 0.10 Green: implement `CliExitCodes` and presenter-safe error mapping.
- [x] 0.11 Red: add command naming contract tests that the root command advertises `run`, `serve`, `attach`, `session`, `agent`, `auth`, `providers`, `models`, `tier`, `mcp`, `skill`, `plugin`, `tool`, `permissions`, `project`, `config`, `memory`, `rag`, `auto`, `lsp`, `terminal`, `git`, `patch`, `github`, `trace`, `stats`, `doctor`, `export`, `import`, `completion`, `upgrade`, `uninstall`, and `acp`.
- [x] 0.12 Green: add command stubs that return deterministic "not implemented" exit behavior until each feature slice is built.
- [ ] 0.13 Red: add fake runtime/LLM/session/permission/project/config/storage ports for unit and integration tests.
- [ ] 0.14 Green: implement reusable test fakes and golden output fixtures.
- [ ] 0.15 Red: add command convention tests for `ls`/`rm` aliases, hidden `del` alias where supported, `--json`/`--format json` on list/show commands, `--dry-run` on destructive commands, and explicit `--interactive` as the only way non-interactive mode may prompt.
- [ ] 0.16 Green: implement shared command convention helpers and validation messages.
- [~] 0.17 Red: add outbound port contract tests for `AgentRuntimePort`, `ProjectDiscoveryPort`, `ProjectConfigPort`, `SecretStorePort`, `TerminalPort`, `TuiPort`, `PtyPort`, `FileSystemPort`, `ShellPort`, `GitPort`, `PatchPort`, `LspPort`, `McpRegistryPort`, `ClipboardPort`, `BrowserOpenPort`, `TraceSinkPort`, `MetricsPort`, and `UpdatePort`.
- [~] 0.18 Green: add port interfaces or adapters only at the correct boundary, reusing existing ports where they already exist.
- [~] 0.19 Red: add inbound boundary contract tests for `CliCommandInputBoundary`, `RunPromptInputBoundary`, `SessionInputBoundary`, `AgentProfileInputBoundary`, `PermissionInputBoundary`, `PatchInputBoundary`, `McpInputBoundary`, `ModelRoutingInputBoundary`, `MemoryInputBoundary`, `RagInputBoundary`, `AutoModeInputBoundary`, and `TraceInputBoundary`.
- [~] 0.20 Green: expose inbound use case interfaces in shared runtime/application packages without Picocli, terminal, web, or Telegram dependencies.

## Phase 1 — Launcher, Root CLI, Project Detection, Doctor

- [ ] 1.1 Red: launcher test proves `golemcore-bot cli --help` is accepted and forwarded to the staged/bundled runtime without requiring web startup.
- [ ] 1.2 Green: extend launcher dispatch so `cli` is a first-class command alongside `web`, preserving `-J/--java-option`, storage path, updates path, and passthrough behavior.
- [x] 1.3 Red: Picocli root tests cover global flags: `--help`, `--version`, `--cwd`, `--project`, `--workspace`, `--config`, `--config-dir`, `--profile`, `--env-file`, `--model`, `--tier`, `--agent`, `--session`, `--continue`, `--fork`, `--format`, `--json`, `--no-color`, `--color`, `--quiet`, `--verbose`, `--log-level`, `--trace`, `--trace-export`, `--no-memory`, `--no-rag`, `--no-mcp`, `--no-skills`, `--permission-mode`, `--yes`, `--no-input`, `--timeout`, `--max-llm-calls`, `--max-tool-executions`, `--attach`, `--port`, `--hostname`, and `-J/--java-option`.
- [x] 1.4 Green: implement `CliRootCommand`, global option binding, validation, and request DTO normalization.
- [ ] 1.5 Red: project discovery tests cover cwd, explicit project, git root, `.golemcore`, rules files, workspace path, and missing/unreadable directories.
- [ ] 1.6 Green: implement `ProjectDiscoveryPort` and local adapter.
- [ ] 1.7 Red: trust registry tests cover first-run restricted state, trusted project lookup, scope persistence, never-trust, and untrusted non-interactive failure.
- [ ] 1.8 Green: implement `ProjectTrust` model, user-level trust registry adapter, and safe defaults.
- [ ] 1.9 Red: project trust flow tests cover the first-run choices `restricted/read-only`, `trust reads only`, `trust reads + edits with diff approval`, `trust full project operations`, and `never trust`, with decisions persisted in the user workspace by default.
- [ ] 1.10 Green: implement trust prompts/policies so file writes, shell, destructive Git, network, and MCP actions remain gated by policy.
- [ ] 1.11 Red: storage layout tests cover existing global workspace directories plus `cli/tui-state.json`, `cli/trusted-projects.json`, `cli/server-registry.json`, `cli/keymaps/`, and project-local `.golemcore/config.json`, `permissions.json`, `mcp.json`, `agents/`, `rules/GOLEM.md`, `sessions/links.json`, `snapshots/`, `cache/`, `server.json`, and `.golemcoreignore`.
- [ ] 1.12 Green: implement storage path resolvers and schema-safe readers/writers without storing raw secrets in project files.
- [ ] 1.13 Red: `project` command tests cover `init`, `status`, `doctor`, `trust`, `untrust`, `rules`, `index`, `ignore`, `env`, and `reset`, including text/JSON output, non-interactive behavior, `.golemcore/rules/GOLEM.md` preference, `AGENTS.md` and `CLAUDE.md` import/support, and optional `.opencode` migration helpers.
- [ ] 1.14 Green: implement project use cases and presenters over project discovery, trust, config, ignore, and RAG indexing ports.
- [ ] 1.15 Red: `config` command tests cover `get`, `set`, `unset`, `list`, `edit`, `validate`, `path`, `import`, `export`, and `reset` across `user`, `project`, `session`, and `profile` scopes.
- [ ] 1.16 Green: implement config use cases and presenters with source attribution per effective value and secret-safe output.
- [~] 1.17 Red: `doctor` presenter tests cover text and JSON output with launcher, Java, workspace, config, providers, models, project, Git, tools, MCP, LSP, memory, RAG, Auto Mode, security, and TUI checks.
- [~] 1.18 Green: implement `DoctorUseCase`, fake check providers, text presenter, and JSON presenter.
- [ ] 1.19 Red: shell completion tests verify bash, zsh, fish, and PowerShell scripts are generated.
- [ ] 1.20 Green: wire Picocli completion generation.
- [ ] 1.21 Red: embedded local mode tests prove `golemcore-bot cli` initializes the runtime without starting the web server, uses current project/workspace storage, lazily starts MCP/LSP, and creates or continues a session according to flags.
- [ ] 1.22 Green: implement embedded runtime bootstrap separate from dashboard server startup.

## Phase 2 — Non-Interactive `run`, Sessions, Agent Profiles, Models, Tier, Auth

- [ ] 2.1 Red: `run` parser tests cover message args, `--stdin`, repeated `--file`, repeated `--context`, `--title`, `--output`, `--save-transcript`, format selection, `--stream`, `--no-stream`, `--plan-only`, `--dry-run`, `--check`, `--require-clean-git`, `--checkpoint`, `--restore-on-failure`, `--apply-patches`, `--no-apply-patches`, `--permission-mode`, hidden `--dangerously-skip-permissions`, budgets, timeout, attach mode, and `--exit-code-from-check`.
- [ ] 2.2 Green: implement `RunCommand` request construction and validation only.
- [ ] 2.3 Red: `RunPromptUseCase` tests prove a run delegates to `AgentRuntimePort`, carries project/session/agent/model/tier/context/permission/budget metadata, and never constructs runtime internals directly.
- [ ] 2.4 Green: implement embedded `AgentRuntimePort` adapter around existing turn/session flow with fake runtime tests first.
- [ ] 2.5 Red: streaming tests define text, JSON, and NDJSON output for `run.started`, `model.selected`, `assistant.delta`, `tool.started`, `tool.completed`, `patch.proposed`, `run.completed`, and `run.failed`.
- [ ] 2.6 Green: implement `CliEvent` stream presenter, final `RunResult` presenter, output file writer, and transcript writer.
- [ ] 2.7 Red: non-interactive permission tests cover `read-only`, `plan`, `ask` without TTY, `edit`, `full`, `--yes`, and `--no-input`.
- [ ] 2.8 Green: implement permission preset resolution and fail-fast behavior.
- [ ] 2.9 Red: hidden permission-bypass tests prove `--dangerously-skip-permissions` is hidden, loudly warned, blocked outside explicitly isolated policy, and trace-visible when used.
- [ ] 2.10 Green: implement guarded dangerous-permission handling without changing safe defaults.
- [ ] 2.11 Red: session command tests cover `list`, `show`, `new`, `continue`, `fork`, `rename`, `delete`, `compact`, `export`, `import`, `share`, `unshare`, `stats`, `trace`, `snapshot`, `restore`, and `prune` request routing.
- [ ] 2.12 Green: implement session use cases on existing session ports with text/JSON presenters.
- [ ] 2.13 Red: session flag/presenter tests cover list sorting/filtering, `--all-projects`, show `--events/--tools/--memory/--trace`, compact strategies, export formats, share redaction, and summary-first sensitive-output behavior.
- [ ] 2.14 Green: implement session presenter rules with stable JSON fields and redaction.
- [ ] 2.15 Red: `agent` command tests cover `list`, `show`, `create`, `edit`, `validate`, `enable`, `disable`, `remove`, `import`, `export`, `run`, `permissions`, `skills`, and `mcp`, including project/global scope and JSON output.
- [ ] 2.16 Green: implement `AgentProfile` use cases, frontmatter parser/serializer, schema validation, and channel-neutral presenters.
- [ ] 2.17 Red: agent profile tests cover `primary`, `subagent`, and `all` modes; `balanced`, `smart`, `coding`, and `deep` tiers; explicit model override; skills; MCP refs; tool policy; permission defaults; memory/RAG toggles; output defaults; and prompt body preservation.
- [ ] 2.18 Green: implement built-in templates `general`, `coding`, `code-reviewer`, `test-writer`, `debugger`, `security-reviewer`, `docs-writer`, and `release-engineer`.
- [ ] 2.19 Red: `agent run` tests prove it is a shortcut for `run --agent` and accepts all `run` flags without duplicating run logic.
- [ ] 2.20 Green: route `agent run` through `RunPromptUseCase`.
- [ ] 2.21 Red: `models`, `tier`, `auth`, and `providers` command tests cover list/show/refresh/set/reset/doctor/route/login/logout/import/export request mapping, provider definitions separately from credentials, effective tier/model source reporting, skill overrides, dynamic upgrades, and fallback explanation.
- [ ] 2.22 Green: bridge commands to existing model routing, config, and credential/provider ports without exposing secrets.
- [ ] 2.23 Red: auth secret storage tests enforce environment variables, OS keychain/credential manager, encrypted user-level store, runtime config references, and no raw project-local secrets in that priority order.
- [ ] 2.24 Green: implement `SecretStorePort` integration or adapter bridge with redacted presenters.
- [ ] 2.25 Red: integration test runs `cli run --format ndjson --permission-mode read-only` against fake runtime and asserts deterministic event order and exit code.
- [ ] 2.26 Green: wire command execution end to end.

## Phase 3 — Default TUI

- [ ] 3.1 Red: root `golemcore-bot cli [project]` behavior tests prove TUI opens when stdin is a TTY, piped stdin converts to run or fails based on `--no-input`, untrusted projects show trust flow before write/shell tools, and root TUI flags `--prompt`, repeated `--file`, repeated `--context`, `--layout`, `--keymap`, `--no-restore-layout`, `--open`, and `--watch` are normalized.
- [ ] 3.2 Green: implement TUI launch use case and terminal capability detection behind `TerminalPort`/`TuiPort`.
- [ ] 3.3 Red: TUI state tests cover status bar model/tier/agent/session/project display, latest-session restore, explicit session open, fork, and layout restore.
- [ ] 3.4 Green: implement minimal JLine/Jansi-backed TUI shell with chat, input, session sidebar, and status bar.
- [ ] 3.5 Red: TUI pane tests cover Chat, Input, Sessions, Files, Diff, Terminal, Tools, Memory, RAG, Trace, Models, Agents, and Todo/Plan panes, including initial `--open` pane selection.
- [ ] 3.6 Green: implement pane routing and placeholder-to-real pane progression behind `TuiPort`.
- [ ] 3.7 Red: TUI keyboard shortcut tests cover `Ctrl+N`, `Ctrl+O`, `Ctrl+P`, `Ctrl+K`, `Ctrl+R`, `Ctrl+C`, `Ctrl+D`, `Ctrl+L`, `Ctrl+T`, `Ctrl+B`, `Ctrl+M`, `Ctrl+A`, `Ctrl+Y`, `Ctrl+E`, `Ctrl+X`, `Tab`, `Shift+Tab`, `Esc`, and `?`.
- [ ] 3.8 Green: implement keymap handling with default/vim/emacs profiles and persisted layout/keymap state.
- [ ] 3.9 Red: streaming UI tests cover token deltas, plan updates, context budget/hygiene events, memory/RAG loads, tool cards, tool output deltas, permission requests, patch events, LSP diagnostics, terminal events, completion/failure, cancellation, and trace id display.
- [ ] 3.10 Green: connect TUI to shared `CliEvent` stream presenters.
- [ ] 3.11 Red: slash-command router tests map `/help`, `/new`, `/sessions`, `/continue`, `/fork`, `/agent`, `/model`, `/tier`, `/tools`, `/permissions`, `/mcp`, `/skill`, `/memory`, `/rag`, `/auto`, `/diff`, `/accept`, `/reject`, `/run`, `/terminal`, `/trace`, `/compact`, `/export`, `/doctor`, and `/quit`.
- [ ] 3.12 Green: implement slash command router with use-case delegation.
- [ ] 3.13 Red: mention parser tests cover `@file`, `@dir`, `@symbol`, `@diagnostics`, `@git:diff`, `@git:status`, `@terminal`, `@session`, `@memory`, `@rag`, `#skill`, and `!command`.
- [ ] 3.14 Green: implement prompt mention resolver contracts and presenter-level autocomplete hooks.
- [ ] 3.15 Red: pseudo-terminal smoke test opens TUI, submits a prompt to fake runtime, receives a final answer, and exits cleanly.
- [ ] 3.16 Green: harden input loop, cancellation, cleanup, and alternate-screen restoration.

## Phase 4 — Coding Context, Native Tools, Git, Patch, LSP

- [ ] 4.1 Red: context layer tests cover project rules, imported `AGENTS.md`/`CLAUDE.md`, Git status, selected Git diff, file mentions, directory mentions, LSP diagnostics, symbols, open files, terminal summaries, patch summaries, current todo/plan, and context hygiene reporting.
- [ ] 4.2 Green: implement channel/project-aware context layers through shared runtime/application abstractions.
- [ ] 4.3 Red: context budget/hygiene tests prove source-level accounting, pinned layer behavior, optional layer dropping, compression/truncation policy, TTL/garbage policy for ephemeral artifacts, structured summaries for tool/terminal/LSP output, and visible `ContextBudgetReport`.
- [ ] 4.4 Green: integrate budget reporting into event stream and trace output.
- [ ] 4.5 Red: filesystem/grep/glob tool tests cover sandbox checks, ignore rules, secret-file denial, large/binary file handling, symlink escape prevention, path traversal denial, and output summarization.
- [ ] 4.6 Green: implement coding filesystem/search adapters or bridge existing tools through permission-aware ports.
- [ ] 4.7 Red: shell risk scoring tests cover low, medium, high, and critical commands; timeouts; output limits; secret redaction; untrusted project denial; and non-interactive fail-fast.
- [ ] 4.8 Green: implement shell policy/risk scoring adapter and presenter summaries.
- [ ] 4.9 Red: patch tests cover unified patch parsing, path traversal denial, binary/large file warnings, hunk selection, accept/reject/apply/revert/export/split, and conflict exit code.
- [ ] 4.10 Green: implement `PatchPort`, patch use cases, text/JSON/TUI presenters, and checkpoint-before-multi-file behavior.
- [ ] 4.11 Red: Git tests use temp repositories for status, diff, checkpoint, restore, commit, branch, worktree list/create/remove, and PR create request delegation.
- [ ] 4.12 Green: implement `GitPort` adapter with safe read defaults and permission-gated writes.
- [ ] 4.13 Red: LSP tests cover list/status/install-consent/start/stop/restart/diagnostics/symbols/references/hover/doctor using fake server protocol fixtures.
- [ ] 4.14 Green: implement `LspPort` abstraction, fake adapter, and first local adapter behind explicit consent.
- [ ] 4.15 Red: prompt composition tests enforce ordering: system/developer instructions, agent profile, project rules, safety/permission summary, current task, explicit mentions, Git status/diff, LSP diagnostics, memory pack, RAG results, recent session summary, tool/terminal summaries, then budget/hygiene metadata.
- [ ] 4.16 Green: implement prompt/context composition ordering through shared runtime abstractions.
- [ ] 4.17 Red: native coding tool registry tests cover `filesystem.read`, `filesystem.write`, `filesystem.edit`, `filesystem.list`, `grep.search`, `glob.search`, `shell.execute`, `patch.apply`, `git.status`, `git.diff`, `git.commit`, `git.worktree`, `lsp.diagnostics`, `lsp.symbols`, `lsp.references`, `todo.update`, `terminal.send`, and `browser.open` with risk/permission defaults.
- [ ] 4.18 Green: wire native coding tools through permission-aware ports and existing tool registry conventions.
- [ ] 4.19 Red: shell-vs-PTY tests prove one-shot shell tools and long-running terminal sessions have separate lifecycle, output capture/streaming, context summaries, and permission paths.
- [ ] 4.20 Green: keep shell and PTY adapters separate while sharing policy evaluation.
- [ ] 4.21 Red: `github` command tests cover `auth`, `doctor`, `install`, `run`, `pr review`, `pr create`, and `issue triage`, with explicit permission checks and no auto-push by default.
- [ ] 4.22 Green: implement GitHub command routing through configured integrations and shared agent/session use cases.

## Phase 5 — MCP, Skills, Plugins, Tools, Permissions

- [ ] 5.1 Red: `mcp` command tests cover list/add/show/remove/enable/disable/auth/logout/debug/test/import/export/logs with project/global/skill scopes.
- [ ] 5.2 Green: bridge CLI MCP commands to existing MCP registry/lifecycle ports and redact env/log output.
- [ ] 5.3 Red: MCP security tests cover explicit env allowlist, idle timeout, skill-scoped lazy startup, permission inheritance, and debug process disclosure.
- [ ] 5.4 Green: implement MCP diagnostics presenters and lifecycle controls.
- [ ] 5.5 Red: `skill` command tests cover list/show/create/edit/install/remove/enable/disable/validate/reload/marketplace search/install/update.
- [ ] 5.6 Green: bridge skill commands to existing skill registry/install ports and add validation presenters.
- [ ] 5.7 Red: `plugin` command tests cover list/show/install/remove/enable/disable/config/doctor/reload/marketplace search/update and current plugin config path conventions.
- [ ] 5.8 Green: bridge plugin commands to existing plugin config/install ports.
- [ ] 5.9 Red: `tool` and `permissions` command tests cover list/show/enable/disable/run/history, policy scopes, presets, allow/deny/reset/explain, approve/reject, and decision durations.
- [ ] 5.10 Green: implement permission policy use cases and channel-neutral decision events.
- [ ] 5.11 Red: skill validation tests cover frontmatter schema, referenced tools, referenced MCP servers, model tier overrides, prompt budget, forbidden permission escalation, raw secret detection, and golden examples when present.
- [ ] 5.12 Green: implement reusable validation reports for CLI, WebUI, and Telegram presenters.

## Phase 6 — `serve`, `attach`, ACP, Terminal

- [ ] 6.1 Red: `serve` command tests cover `--port`, `--hostname`, `--project`, `--workspace`, `--auth none|basic|token`, `--user`, `--password-env`, repeated `--cors`, `--mdns`, `--daemon`, `--pid-file`, `--log-file`, `--idle-timeout`, `--max-clients`, and `--no-open`.
- [ ] 6.2 Green: implement headless runtime server startup path without requiring dashboard web UI.
- [ ] 6.3 Red: API contract tests cover `GET /health`, `GET /v1/projects`, `POST /v1/projects/trust`, `GET/POST /v1/sessions`, `GET /v1/sessions/{id}`, `POST /v1/sessions/{id}/runs`, `GET /v1/runs/{id}/events`, `POST /v1/runs/{id}/cancel`, `POST /v1/permissions/{id}/decision`, `GET /v1/patches`, `POST /v1/patches/{id}/accept`, `POST /v1/patches/{id}/reject`, `GET /v1/models`, `POST /v1/models/refresh`, `GET /v1/mcp`, `POST /v1/mcp/{id}/debug`, `GET /v1/trace/{id}`, `GET /v1/lsp/diagnostics`, `POST /v1/terminal`, `WS /v1/terminal/{id}`, and `WS /v1/events`.
- [ ] 6.4 Green: implement stable API DTOs and controllers over the same use cases used by CLI/TUI.
- [ ] 6.5 Red: `attach` tests cover local discovery, explicit URL, `--token`, `--token-env`, `--basic-user`, `--basic-password-env`, missing server required failure, auto-start, session selection, initial pane selection, remote mode, and default denial of local shell/file tools for remote runtimes.
- [ ] 6.6 Green: implement server registry, runtime client adapter, and attach TUI flow.
- [ ] 6.7 Red: `run --attach` integration test proves HTTP runtime client and embedded runtime use the same `AgentRuntimePort` contract.
- [ ] 6.8 Green: wire attach mode into `run`.
- [ ] 6.9 Red: ACP stdio tests cover newline-delimited JSON handshake, sessions, run, events, permissions, patches, context mentions, cancellation, and invalid message errors.
- [ ] 6.10 Green: implement ACP adapter as another inbound adapter over shared use cases.
- [ ] 6.11 Red: PTY terminal tests cover list/open/attach/send/kill/logs, resize, disconnect cleanup, permission-gated send, and shared server-side lifecycle.
- [ ] 6.12 Green: implement terminal commands and server-backed PTY sharing.

## Phase 7 — Memory, RAG, Auto Mode, Trace, Stats, Backports

- [ ] 7.1 Red: `memory` command tests cover status/search/list/show/pin/unpin/forget/compact/export/import/stats/doctor, summary-first output, `--full`, provenance, redaction, JSON mode, and guarantees that forgotten memory is not reintroduced through RAG indexing.
- [ ] 7.2 Green: bridge memory commands to Memory V2 ports and emit `memory.pack.loaded` events during runs.
- [ ] 7.3 Red: `rag` command tests cover status/query/index/reindex/clear/config/doctor, scored results, degraded availability, fire-and-forget indexing that does not block normal runs, explicit retrieval diagnostics, and trace visibility.
- [ ] 7.4 Green: bridge RAG commands to LightRAG/plugin ports with graceful degradation.
- [ ] 7.5 Red: `auto` command tests cover status, goal lifecycle, task lifecycle, schedule lifecycle, UTC cron behavior, diary, manual run, and stop.
- [ ] 7.6 Green: bridge Auto Mode commands to existing scheduling/goal/task/diary use cases.
- [ ] 7.7 Red: `trace` command tests cover list/show/export/replay/waterfall/prune and redaction-safe exports.
- [ ] 7.8 Green: implement trace presenters and replay over `CliEvent`.
- [ ] 7.9 Red: `stats` tests cover days/project/session filters, model/tool/agent grouping, cost flag, and JSON mode.
- [ ] 7.10 Green: bridge stats to existing usage metrics.
- [ ] 7.11 Red: WebUI/Telegram presenter contract tests prove permission prompts, patch approval, session summaries, model/tier selection, agent profiles, MCP diagnostics, memory/RAG views, Auto Mode actions, and trace links can use shared view models.
- [ ] 7.12 Green: add channel-specific presenters without duplicating use-case logic.
- [ ] 7.13 Red: router contract tests prove CLI command router, TUI navigation router, Web route/controller adapter, and Telegram callback router can invoke the same shared use cases.
- [ ] 7.14 Green: keep channel routing separate from business logic.

## Phase 8 — Export/Import, Upgrade, Uninstall, Packaging, Hardening

- [ ] 8.1 Red: export/import tests cover sessions, config, agents, skills, memory, all-bundle, dry-run, merge, overwrite, secret redaction, and malformed bundle errors.
- [ ] 8.2 Green: implement export/import commands and bundle schema.
- [ ] 8.3 Red: upgrade tests prove integration with staged update mechanism and version target handling.
- [ ] 8.4 Green: implement `upgrade` command delegation.
- [ ] 8.5 Red: uninstall tests cover keep-config, keep-data, remove-cache, dry-run, force, and confirmation behavior.
- [ ] 8.6 Green: implement uninstall planning and guarded execution.
- [ ] 8.7 Red: packaging tests prove native launcher includes CLI help, shell completion, runtime dispatch, and no web-only startup requirement.
- [ ] 8.8 Green: update packaging metadata, README/quickstart references, and deployment docs.
- [ ] 8.9 Red: cross-platform tests cover path handling, shell behavior, terminal capability detection, ANSI color policy, and newline handling.
- [ ] 8.10 Green: harden adapters for macOS/Linux/Windows differences.
- [ ] 8.11 Red: performance tests measure cold embedded run, attach run, model catalog reuse, MCP reuse, LSP reuse, and large NDJSON streams.
- [ ] 8.12 Green: optimize startup, lazy initialization, server reuse, and stream backpressure.

## Open Decisions To Track

- [ ] O.1 Confirm TUI library choice: start with JLine/Jansi behind `TuiPort`, with a documented swap path.
- [ ] O.2 Confirm project rules filename: `.golemcore/rules/GOLEM.md`, with import/support for `AGENTS.md` and `CLAUDE.md`.
- [ ] O.3 Confirm secret store order: OS keychain first, environment fallback, encrypted user-level store fallback, never raw project-local secrets.
- [ ] O.4 Confirm serve API auth default: localhost-only token/basic auth, never open remote by default.
- [ ] O.5 Confirm default permission mode: `ask` in TUI, read-only/fail-fast for non-interactive untrusted projects.
- [ ] O.6 Confirm shell behavior in non-interactive mode: deny unless explicit edit/full policy and allowlist permit it.
- [ ] O.7 Confirm LSP install policy: never auto-download/install without explicit consent.
- [ ] O.8 Confirm MCP config scopes: global/project/skill, with skill-scoped servers started only when active.
- [ ] O.9 Confirm WebUI/Telegram backport timing: begin after shared event/use-case contracts stabilize, before CLI-only hardcoding can accumulate.

---

## Security Regression Matrix

- [ ] S.1 Path traversal cannot read/write outside the trusted project.
- [ ] S.2 Symlink traversal cannot escape the trusted project.
- [ ] S.3 `.env`, private keys, credential stores, and `.git` internals are excluded unless explicitly allowed.
- [ ] S.4 Shell commands with destructive patterns are denied or require explicit high-risk approval.
- [ ] S.5 Non-interactive mode never waits for permission input.
- [ ] S.6 MCP env vars are explicit allowlist only.
- [ ] S.7 Logs, traces, exports, MCP debug output, terminal logs, and tool output redact secrets.
- [ ] S.8 Remote attach cannot accidentally execute local shell/file tools unless explicitly routed and trusted.
- [ ] S.9 Patch apply rejects absolute paths, parent-directory escapes, binary surprises, and conflicting hunks.
- [ ] S.10 Trust decisions persist in user workspace, not project repository, unless a later explicit project policy is added.
- [ ] S.11 Project/config/agent export never includes raw secrets and validates imported files before writing state.
- [ ] S.12 Command injection detection, ignore-file handling, secret-file heuristics, and export-without-secrets behavior have dedicated regression tests.

## Golden End-to-End Scenarios

- [ ] E2E.1 `golemcore-bot cli --help` prints root help and exits `0`.
- [ ] E2E.2 `golemcore-bot cli doctor --json` returns stable diagnostics JSON and exits `0` with fake checks.
- [ ] E2E.3 `cat prompt.md | golemcore-bot cli run --stdin --format ndjson --permission-mode read-only` emits valid event lines and final result.
- [ ] E2E.4 `golemcore-bot cli run --plan-only --no-input` never writes files or runs shell commands.
- [ ] E2E.5 `golemcore-bot cli run --check "./mvnw test" --exit-code-from-check` maps verification failure to exit code `13`.
- [ ] E2E.6 `golemcore-bot cli` opens TUI, streams a fake run, handles permission approval/denial, and exits without terminal corruption.
- [ ] E2E.7 `serve` plus `attach` reuses the same session and event stream.
- [ ] E2E.8 `run --attach required` fails with runtime-unavailable exit code when no server is discoverable.
- [ ] E2E.9 Patch proposal can be accepted by hunk, rejected, applied, reverted, and exported.
- [ ] E2E.10 Memory/RAG unavailable state emits diagnostics but does not fail a normal run.
- [ ] E2E.11 `agent create`, `agent validate`, and `agent run` work against a project-local profile without bypassing `run`.
- [ ] E2E.12 `project init`, `project trust`, `config set`, and `config list --json` create and report the expected `.golemcore/` state.
- [ ] E2E.13 CI review flow runs `cli run --permission-mode read-only --format json --file pr.diff` and returns findings without writes or shell execution.
- [ ] E2E.14 Long-running daemon flow starts `serve --auth token`, attaches a TUI, then runs `run --attach required` against the same warm session/runtime.

## Documentation Deliverables

- [ ] D.1 `docs/CLI.md` user guide.
- [ ] D.2 `docs/CLI_COMMANDS.md` full command reference.
- [ ] D.3 `docs/CLI_TUI.md` TUI panes, shortcuts, slash commands, and mentions.
- [ ] D.4 `docs/CLI_SECURITY.md` trust, permissions, sandbox, ignore rules, and secret redaction.
- [ ] D.5 `docs/CLI_AGENT_PROFILES.md` profile schema, templates, validation, and examples.
- [ ] D.6 `docs/CLI_MCP.md` MCP config, auth, debug, lifecycle, and security.
- [ ] D.7 `docs/CLI_CONTEXT.md` context layers, mentions, memory, RAG, budget reports, and hygiene.
- [ ] D.8 `docs/CLI_BACKPORTS.md` shared use cases and presenters for WebUI/Telegram.
- [ ] D.9 `docs/CLI_API.md` serve/attach/ACP API contracts.
- [ ] D.10 `docs/CLI_TESTING.md` TDD workflow, fake ports, golden fixtures, and test commands.

## Definition of Done

- [ ] DoD.1 All planned commands have deterministic help, argument validation, text output, JSON output where applicable, and documented exit codes.
- [ ] DoD.2 `run`, `serve`, `attach`, and TUI consume the same runtime/use-case contracts.
- [ ] DoD.3 Shared events and DTOs are stable, versionable, and covered by serialization tests.
- [ ] DoD.4 Architecture tests prevent adapter leakage into runtime/domain modules.
- [ ] DoD.5 Security regression matrix is green.
- [ ] DoD.6 Golden end-to-end scenarios are green in CI.
- [ ] DoD.7 Docs match implemented behavior and examples are tested or smoke-verified.
- [ ] DoD.8 Native packaging launches `golemcore-bot cli` without requiring the dashboard server.
- [ ] DoD.9 Agent profiles, project commands, config commands, full event taxonomy, TUI panes/shortcuts, and storage layout are covered by tests before implementation is considered complete.
- [ ] DoD.10 Every global/root/run/serve/attach flag and every command tree from the architecture document has parser tests, presenter tests, and at least one scriptable path.
