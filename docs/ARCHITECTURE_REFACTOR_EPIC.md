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
- [x] Reduce direct loop knowledge of fallback text, trace naming, typing scheduler, session identity, and persistence semantics.

### P0: Runtime Config

- [x] Move runtime config ownership into `golemcore-bot-runtime-config`.
- [x] Preserve current query/admin/self-evolving/managed-policy ports.
- [x] Introduce section ownership boundaries for defaults, validation, persistence, and query/admin views.
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
- [x] Introduce explicit tool execution context as the primary contract.
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

- [x] Define typed runtime events as first-class boundaries.
- [x] Document plugin/tool permission model.
- [x] Document observability schema versioning and redaction policy.
- [x] Add OpenAPI or shared JSON schema generation for dashboard API drift detection.

## Detailed Recommendation Coverage

### Runtime Loop Extraction

- [x] Keep rate-limit admission behavior for user messages.
- [x] Keep internal and auto messages outside normal rate-limit consumption.
- [x] Move typing indicator scheduling and rate-limit feedback into `TurnFeedbackCoordinator`.
- [x] Move final session save, traced save span, and error-safe persistence into `TurnPersistenceGuard`.
- [x] Keep synthetic fallback feedback routed through the routing system, not raw history mutation.
- [x] Keep session identity metadata binding covered by runtime-core tests.
- [x] Keep inbound trace root, child system spans, and trace state events covered by runtime-core tests.
- [x] Keep loop iteration limit behavior covered by runtime-core BDD tests.

### Runtime Config Split

- [x] Own runtime config from `golemcore-bot-runtime-config`.
- [x] Keep query/admin ports as the public boundary.
- [x] Keep self-evolving runtime config access behind its own port.
- [x] Define section owners for defaults, validation, persistence, and query/admin views.
- [x] Cover defaults, missing sections, persistence, bootstrap overrides, and redaction-sensitive views in module tests.

### Session Split

- [x] Own session lifecycle from `golemcore-bot-sessions`.
- [x] Keep `SessionPort` as the runtime boundary.
- [x] Split active session pointer, selection, inspection, retention cleanup, presentation, and model settings helpers.
- [x] Cover cache/storage consistency, delete rollback, compaction, retention cleanup, and model settings behavior in module tests.

### Memory Split

- [x] Own memory retrieval, write, promotion, diagnostics, and prompt-pack logic from `golemcore-bot-memory`.
- [x] Keep memory persistence and retrieval behind ports.
- [x] Split retrieval planning, candidate collection, scoring, reranking, selection, disclosure, normalization, and promotion helpers.
- [x] Cover retrieval planning, disclosure rendering, write normalization, promotion, and diagnostics in module tests.

### Tool Split

- [x] Own tool execution, registry, artifacts, confirmation, and workspace path logic from `golemcore-bot-tools`.
- [x] Split runtime registry ownership into `ToolRegistryService`.
- [x] Keep tool execution in `ToolCallExecutionService`.
- [x] Keep artifact persistence in `ToolArtifactService`.
- [x] Introduce `ToolExecutionContext` as the primary execution boundary.
- [x] Keep `AgentContextHolder` as compatibility behavior only.
- [x] Cover registry, confirmation gating, timeout behavior, artifact extraction, truncation, and context cleanup in module tests.

### Tracing Split

- [x] Own trace lifecycle from `golemcore-bot-tracing`.
- [x] Split trace service, budget pruning, snapshot compression, context helpers, MDC helpers, naming, and runtime-config support.
- [x] Keep tracing channel-independent.
- [x] Cover span lifecycle, snapshot compression, budget pruning, MDC, naming, and runtime config support in module tests.

### Contracts Cleanup

- [x] Keep contracts limited to DTOs, value objects, events, ports, shared views, and stable helpers.
- [x] Keep runtime implementation services out of contracts.
- [x] Reject Spring service/component stereotypes and injection annotations in contracts through architecture tests.
- [x] Add runtime event schema versioning coverage in contracts tests.

### Dashboard API Drift

- [x] Add shared dashboard API contract schemas under `docs/contracts/dashboard-api`.
- [x] Generate TypeScript dashboard contracts from schemas.
- [x] Fail dashboard build when generated contracts drift.
- [x] Document schema versioning and redaction-sensitive fields in `docs/API_CONTRACTS.md`.

### Module-Local Architecture Tests

- [x] Add module-local architecture tests for `golemcore-bot-runtime-core`.
- [x] Add module-local architecture tests for `golemcore-bot-runtime-config`.
- [x] Add module-local architecture tests for `golemcore-bot-sessions`.
- [x] Add module-local architecture tests for `golemcore-bot-memory`.
- [x] Add module-local architecture tests for `golemcore-bot-tools`.
- [x] Add module-local architecture tests for `golemcore-bot-tracing`.
- [x] Enforce that the new runtime-owned modules do not import app, adapter, infrastructure, launcher, security, plugin, proto, usage, or app-owned tool packages.
- [x] Enforce explicit constructor injection in new runtime-owned module production sources.

### Code-Level Quick Wins

- [x] Remove direct production usage of `@Autowired` and `@Resource`.
- [x] Replace generated injection constructors with explicit constructors and final fields.
- [x] Keep `Service` classes scoped by module ownership and split broad responsibilities where this refactor touched them.
- [x] Keep architecture allowlist additions documented and stable.
- [x] Publish Maven dependency tree as a CI artifact for reviewer visibility.
- [x] Add explicit dashboard contract checks to the dashboard build.
- [x] Make runtime config section ownership explicit with tests.
- [x] Make `ToolExecutionContext` the primary tool execution contract while keeping compatibility behavior.

### Review And Verification

- [x] Repeat code review after the modular split.
- [x] Fix review findings with focused tests before implementation changes.
- [x] Confirm tests live in their owning Maven modules.
- [x] Run module-focused tests for changed feature modules after the latest checklist update.
- [x] Run full local `verify` after the latest checklist update.
- [x] Run injection annotation and generated-constructor scans after the latest checklist update.
- [x] Run whitespace and changed-file language hygiene checks after the latest checklist update.

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
- [x] Local strict or focused verification passes after the latest checklist update.
- [x] GitHub checks pass on the epic PR after the latest checklist update.
