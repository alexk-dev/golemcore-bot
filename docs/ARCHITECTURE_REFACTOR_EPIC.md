# Architecture Refactor Epic

Branch: `refactor/modular-runtime-boundaries`

PR title: `refactor: modular runtime boundaries`

## Goal

Turn the existing physical Maven split into enforceable bounded contexts. The application module must remain the Spring Boot composition root, while runtime execution, runtime config, sessions, memory, tools, and tracing become independently owned modules with explicit dependency rules.

## Target Module Graph

```text
golemcore-bot-contracts       -> no dependency on app, adapters, infrastructure, Spring Web, Telegram, dashboard
golemcore-bot-runtime-core    -> contracts + runtime config + tracing, no inbound/outbound adapters
golemcore-bot-runtime-config  -> contracts + config persistence ports
golemcore-bot-sessions        -> contracts + session/storage ports + runtime config where policy needs it
golemcore-bot-memory          -> contracts + memory/storage ports + runtime config
golemcore-bot-tools           -> contracts + tool ports + runtime config, no session persistence ownership
golemcore-bot-tracing         -> contracts + trace persistence/snapshot ports
golemcore-bot-client          -> contracts
golemcore-bot-extensions      -> contracts + plugin API
golemcore-bot-hive            -> contracts + client
golemcore-bot-self-evolving   -> contracts + client + its own ports
golemcore-bot-app             -> composition root, wires feature/runtime modules and adapters
dashboard                     -> web control plane
```

Key rule: dependencies should not point into `golemcore-bot-app`. The app module wires modules together; it is not a reusable library.

## Working Checklist

### P0: Foundation

- [x] Create one epic branch: `refactor/modular-runtime-boundaries`.
- [x] Define one epic PR title: `refactor: modular runtime boundaries`.
- [x] Add or update architecture documentation with the current and target module dependency graph.
- [x] Update contributor prerequisites so Java/Spring/build baseline matches POM and CI.
- [x] Update stale application comments and startup messages.
- [x] Remove production `@Autowired` constructor annotations.
- [x] Remove test `@Autowired` usage or replace it with explicit test wiring that does not weaken production rules.
- [x] Replace generated injection constructors with explicit constructors and `private final` fields.
- [x] Add the recommended Maven modules to the parent build.
- [x] Wire app dependencies so `golemcore-bot-app` remains the composition root.

### P0: Architecture Guardrails

- [x] Add cross-module architecture tests for dependency direction.
- [x] Ensure feature modules do not depend on `golemcore-bot-app`.
- [x] Ensure `golemcore-bot-contracts` does not depend on implementation packages or Spring stereotypes.
- [x] Add a dependency report target for CI visibility.
- [x] Keep allowlist entries stable or explain removal plans in comments.

### P0: Agent Runtime

- [x] Keep `AgentLoop` externally compatible.
- [x] Move loop-level runtime orchestration into `golemcore-bot-runtime-core`.
- [x] Keep adapter and channel implementations out of `golemcore-bot-runtime-core`.
- [x] Characterize observable `processMessage` behavior before changing the loop internals.
- [x] Move `AgentLoop` unit and BDD tests into `golemcore-bot-runtime-core`.
- [ ] Reduce direct loop knowledge of fallback text, trace naming, typing scheduler, session identity, and persistence semantics.

### P0: Runtime Config

- [x] Move runtime config ownership into `golemcore-bot-runtime-config`.
- [x] Preserve current query/admin/self-evolving/managed-policy ports.
- [ ] Introduce section ownership boundaries for defaults, validation, persistence, and query/admin views.
- [x] Keep self-evolving startup overrides behind a port boundary.
- [x] Add tests for stable defaults, section persistence, secret redaction, and missing sections.
- [x] Move runtime-config service tests into `golemcore-bot-runtime-config`.

### P0: Sessions

- [x] Move session lifecycle ownership into `golemcore-bot-sessions`.
- [x] Preserve `SessionPort` as the module boundary.
- [x] Separate repository/cache/policy/facade responsibilities where it can be done safely.
- [x] Keep session goal cleanup behind a port boundary.
- [x] Add tests for cache/storage consistency, delete rollback, compaction, and model settings inheritance.
- [x] Move session lifecycle tests into `golemcore-bot-sessions`.

### P1: Memory

- [x] Move memory retrieval/write/promotion/prompt-pack logic into `golemcore-bot-memory`.
- [x] Ensure memory does not depend on dashboard or concrete channels.
- [x] Keep memory persistence and retrieval behind ports.
- [x] Add tests for retrieval planning, disclosure rendering, write normalization, and promotion.
- [x] Move memory tests into `golemcore-bot-memory`.

### P1: Tools

- [x] Move tool registry/execution/artifact services into `golemcore-bot-tools`.
- [x] Keep concrete filesystem/shell/dashboard tools in app/adapters unless they are fully port-driven.
- [ ] Introduce explicit tool execution context as the primary contract.
- [x] Keep ThreadLocal context only as compatibility behavior.
- [x] Add tests for registry, confirmation gating, timeout behavior, artifact extraction, and truncation.
- [x] Move tool service tests into `golemcore-bot-tools`.

### P1: Tracing

- [x] Move trace lifecycle, snapshot compression, budgets, MDC helpers, and naming helpers into `golemcore-bot-tracing`.
- [x] Ensure tracing does not depend on a concrete channel.
- [x] Add tests for span lifecycle, snapshot compression, budget pruning, and redaction-friendly views.
- [x] Move tracing tests into `golemcore-bot-tracing`.

### P1: Contracts

- [x] Keep `golemcore-bot-contracts` limited to DTOs, value objects, events, ports, shared views, and stable helpers.
- [x] Move runtime behavior out of contracts when a narrower module owns it.
- [x] Add tests that reject Spring service/component stereotypes in contracts.
- [x] Move contract/model/helper tests into `golemcore-bot-contracts`.

### P2: Follow-Up Boundaries

- [ ] Define typed runtime events as first-class boundaries.
- [ ] Document plugin/tool permission model.
- [ ] Document observability schema versioning and redaction policy.
- [ ] Add OpenAPI or shared JSON schema generation for dashboard API drift detection.

## Definition of Done

- [x] Module dependency graph is documented and enforced.
- [x] Maven build includes the recommended modules.
- [x] `golemcore-bot-app` is the only module that wires all runtime/feature modules together.
- [x] No feature module depends on `golemcore-bot-app`.
- [x] No production source uses `@Autowired`, `@Resource`, or generated injection constructors.
- [x] Runtime config is owned by `golemcore-bot-runtime-config`.
- [x] Session lifecycle is owned by `golemcore-bot-sessions`.
- [x] Memory logic is owned by `golemcore-bot-memory`.
- [x] Tool registry/execution services are owned by `golemcore-bot-tools`.
- [x] Trace lifecycle is owned by `golemcore-bot-tracing`.
- [x] Runtime loop orchestration is owned by `golemcore-bot-runtime-core`.
- [x] App remains responsible for adapters, security, launchers, and packaging.
- [x] Unit tests live in their owning Maven modules.
- [x] Architecture tests cover the new boundaries.
- [x] Local strict or focused verification passes.
- [ ] GitHub checks pass on the epic PR.
