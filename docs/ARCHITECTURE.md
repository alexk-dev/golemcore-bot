# Architecture

GolemCore Bot uses a hexagonal architecture with Maven modules as bounded-context boundaries. `golemcore-bot-app` is the Spring Boot composition root: it owns adapters, web/security configuration, launchers, and packaging. Feature and runtime modules expose ports and services that are wired by the app module.

## Target Dependency Graph

```text
contracts        -> no dependency on app, adapters, infrastructure, Spring Web, Telegram, dashboard
runtime-core     -> contracts + runtime-config + tracing, no inbound/outbound adapters
runtime-config   -> contracts + config persistence ports
sessions         -> contracts + session/storage ports + runtime-config where policy needs it
memory           -> contracts + memory/storage ports + runtime-config
tools            -> contracts + tool ports + runtime-config, no session persistence ownership
tracing          -> contracts + trace persistence/snapshot ports
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
├── golemcore-bot-client
├── golemcore-bot-extensions
├── golemcore-bot-hive
├── golemcore-bot-self-evolving
├── golemcore-bot-app
└── dashboard
```

## Rules

- `golemcore-bot-contracts` contains stable DTOs, value objects, events, ports, lightweight shared views, and shared helpers only.
- `golemcore-bot-app` may depend on all runtime and feature modules.
- Runtime and feature modules must not depend on `golemcore-bot-app`.
- Runtime and feature modules must not depend on app adapters, web controllers, launchers, or security configuration.
- Adapters implement ports; domain/runtime code depends on ports and contracts.
- New module dependencies must follow the graph above and be covered by architecture tests.
- `golemcore-bot-app` domain code is grouped by bounded-context package; new production classes must not be added to `me.golemcore.bot.domain.service`.

## App Domain Contexts

The app module keeps only adapter-facing or composition-root domain services that are not ready for a dedicated Maven module. These services are grouped by ownership:

```text
domain.auto
domain.context.compaction
domain.dashboard
domain.events
domain.model
domain.planning
domain.progress
domain.prompt
domain.resilience
domain.runtime
domain.scheduling
domain.session
domain.skills
domain.tools
domain.tracing
domain.update
domain.voice
domain.workspace
```
