# Runtime Architecture Refactor Plan

This plan tracks the debt burn-down following the runtime module split. It is intentionally checklist-based so reviewers can verify the scope without reading every implementation detail first.

## Milestones

- [x] Replace public mutable runtime context results with immutable turn results.
  - [x] Add `TurnRunResult`, `RunStatus`, `FailureSummary`, and `PersistenceOutcome`.
  - [x] Change `SessionRunDispatchPort` to expose `submitForResult`.
  - [x] Map internal `AgentContext` into the public result model at the app/runtime boundary.
- [x] Split session run coordination responsibilities.
  - [x] Extract pending completion handling.
  - [x] Extract queued-message flush handling.
  - [x] Extract stop request handling.
  - [x] Extract session activity tracking.
  - [x] Keep queue behavior covered by the existing coordinator characterization tests.
- [x] Make agent runtime assembly explicit.
  - [x] Stop constructing runtime collaborators inside `AgentLoop`.
  - [x] Add a runtime factory for collaborator assembly.
  - [x] Keep `AgentLoop` focused on one-turn lifecycle orchestration.
- [x] Introduce immutable pipeline topology.
  - [x] Add `AgentPipelinePlan`.
  - [x] Add `AgentPipelinePlanFactory`.
  - [x] Make `AgentPipelineRunner` execute a prebuilt plan instead of lazy-mutating topology.
- [x] Split runtime config consumers toward typed views.
  - [x] Add narrow config interfaces for model routing, tracing, turns, tools, auto mode, shell, updates, snapshots, and mutation.
  - [x] Move runtime-core and tools consumers to narrow views where possible.
  - [x] Keep `RuntimeConfigService` as the compatibility facade for existing callers.
- [x] Remove `domain.service` as a production package pattern.
  - [x] Move production classes to bounded-context packages.
  - [x] Move corresponding tests with their modules/packages.
  - [x] Add architecture coverage that rejects production `domain.service` packages across all modules.
- [x] Make feedback fallback deterministic and safe by default.
  - [x] Split fallback detection, safe rendering, routing, and optional LLM explanation.
  - [x] Keep the primary fallback independent from LLM availability.
  - [x] Redact error material before optional LLM explanation.
- [x] Make persistence failure observable.
  - [x] Return structured `PersistenceOutcome` from the persistence guard.
  - [x] Attach persistence outcome to the turn context for result mapping.
  - [x] Emit a runtime event for failed session persistence.
- [x] Update architecture documentation and guards.
  - [x] Document runtime ownership, public contracts, package policy, fallback policy, and persistence outcome policy.
  - [x] Burn down stale `domain.service` allowlist entries.
  - [x] Add a guard that contracts ports do not expose `AgentContext`.
- [x] Apply small correctness cleanup.
  - [x] Check nullable inbound/context before auto-run helper evaluation.
  - [x] Replace stale loop log prefixes in extracted pipeline code.

## Verification Checklist

- [x] `mvn -pl golemcore-bot-runtime-core,golemcore-bot-app,golemcore-bot-scheduling -am -DskipTests test-compile`
- [x] Architecture guard tests pass.
- [x] Full local verification passes.
- [ ] GitHub checks are green.
