# Architecture

GolemCore Bot uses a hexagonal architecture with Maven modules as bounded-context boundaries. `golemcore-bot-app` is the Spring Boot composition root: it owns adapters, web/security configuration, launchers, and packaging. Feature and runtime modules expose ports and services that are wired by the app module.

## Target Dependency Graph

```text
contracts        -> no dependency on app, adapters, infrastructure, Spring Web, Telegram, dashboard
runtime-core     -> contracts + runtime-config + tracing, turn lifecycle and context assembly, no inbound/outbound adapters
runtime-config   -> contracts + config persistence ports
sessions         -> contracts + session/storage ports + runtime-config where policy needs it
memory           -> contracts + memory/storage ports + runtime-config
tools            -> contracts + tool ports + runtime-config, no session persistence ownership
tracing          -> contracts + trace persistence/snapshot ports
scheduling       -> contracts + runtime-config + tracing, no app or adapter ownership
client           -> contracts
extensions       -> contracts + plugin API
hive             -> contracts + client
self-evolving    -> contracts + client + its own ports
app              -> composition root, wires runtime/feature modules and adapters
dashboard        -> web control plane
```

## Module Layout

```text
golemcore-bot-parent
├── golemcore-bot-contracts
├── golemcore-bot-runtime-core
├── golemcore-bot-runtime-config
├── golemcore-bot-sessions
├── golemcore-bot-memory
├── golemcore-bot-tools
├── golemcore-bot-tracing
├── golemcore-bot-scheduling
├── golemcore-bot-client
├── golemcore-bot-extensions
├── golemcore-bot-hive
├── golemcore-bot-self-evolving
├── golemcore-bot-app
└── dashboard
```

## Rules

- `golemcore-bot-contracts` contains stable DTOs, value objects, events, ports, lightweight shared views, and shared helpers only.
- Public ports must not expose mutable runtime internals such as `AgentContext`; async turn execution returns immutable `TurnRunResult`.
- `golemcore-bot-app` may depend on all runtime and feature modules.
- Runtime and feature modules must not depend on `golemcore-bot-app`.
- Runtime and feature modules must not depend on app adapters, web controllers, launchers, or security configuration.
- Adapters implement ports; domain/runtime code depends on ports and contracts.
- New module dependencies must follow the graph above and be covered by architecture tests.
- Production code is grouped by bounded-context package; new production classes must not be added to any `me.golemcore.bot.domain.service` package.
- Reusable context assembly (`ContextAssembler`, `PromptComposer`, and the `ContextBuildingSystem` pipeline step) lives in `golemcore-bot-runtime-core`; app owns only concrete context layers and resolver wiring that still depend on app services.

## Runtime Module Ownership

`golemcore-bot-runtime-core` owns one-turn execution: `AgentLoop`, context creation, pipeline planning, feedback guarantee, persistence guard, and auto-run outcome recording. `AgentLoop` is a lifecycle facade; collaborator construction belongs to the runtime factory/configuration layer.

`golemcore-bot-sessions` owns session storage/cache/lifecycle services. The app module still owns app-facing session run queue coordination because it depends on app executor wiring, delayed action integration, and channel-facing stop behavior.

`golemcore-bot-runtime-config` owns runtime configuration state and exposes narrow views such as model routing, tracing, turn, tools, auto mode, shell, update, snapshot, and mutation views. The broad service remains a compatibility facade while consumers migrate to the narrow views.

`golemcore-bot-tools`, `golemcore-bot-memory`, `golemcore-bot-tracing`, and `golemcore-bot-scheduling` own their bounded runtime capabilities and must not depend on app adapters or app-specific controllers.

## Public Contracts

Public ports expose stable commands, DTOs, events, read models, and immutable results. `SessionRunDispatchPort` returns `TurnRunResult` for awaitable turn execution. The mutable `AgentContext` remains an internal pipeline object and must not be used as a public port return type.

`TurnRunResult` includes session/run/trace identifiers, status, the final response projection, failure summaries, stop/queue flags, and `PersistenceOutcome`. This makes a processed-but-unsaved turn distinguishable from a fully persisted turn without throwing persistence failures from the turn-finalization path.

## Feedback And Persistence Policy

Feedback fallback is deterministic-first. The primary failure response is rendered by a safe local renderer and routed without relying on LLM availability. Optional LLM-based error explanation is feature-flagged, redacted, and bounded; it is not required for user-visible fallback delivery.

Final session persistence reports structured success or failure through `PersistenceOutcome`. Failures are attached to the runtime context for result mapping and emitted as runtime events so diagnostics, metrics, and callers can observe degraded turns.

## App Domain Contexts

The app module keeps only adapter-facing or composition-root domain services that are not ready for a dedicated Maven module. These services are grouped by ownership:

```text
domain.auto
domain.context.compaction
domain.context.layer
domain.context.resolution
domain.dashboard
domain.model
domain.planning
domain.progress
domain.prompt
domain.resilience
domain.runtime
domain.session
domain.skills
domain.system
domain.tools
domain.tracing
domain.update
domain.voice
domain.workspace
```
