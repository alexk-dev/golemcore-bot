# Architecture Refactor Plan

Review date: 2026-04-27

This checklist tracks the next architecture-hardening pass after the modular runtime split. The goal is not to add modules mechanically, but to make ownership visible in package/module boundaries and keep `golemcore-bot-app` as a Spring Boot composition root.

## P0 - No Behavior Change

- [x] Document the current module graph in `docs/ARCHITECTURE.md`.
- [x] Document the target module graph and forbidden dependencies.
- [x] Enforce Maven module dependency rules with architecture tests.
- [x] Enforce explicit constructor injection for production beans.
- [x] Split remaining `golemcore-bot-app` `domain/service` classes into bounded-context packages.
- [x] Add an architecture test that prevents new production classes from being added to `golemcore-bot-app` `domain/service`.
- [x] Keep tests in the module that owns the production code they validate.
- [x] Run the affected tests and fix failures before widening the gate.
- [x] Run the full Maven reactor verification gate after package ownership changes.

## P1 - Runtime Context Ownership

- [x] Create `golemcore-bot-runtime-core`.
- [x] Create `golemcore-bot-runtime-config`.
- [x] Create `golemcore-bot-sessions`.
- [x] Create `golemcore-bot-memory`.
- [x] Create `golemcore-bot-tools`.
- [x] Create `golemcore-bot-tracing`.
- [x] Move runtime-core architecture tests into `golemcore-bot-runtime-core`.
- [x] Move runtime-config architecture tests into `golemcore-bot-runtime-config`.
- [x] Move sessions architecture tests into `golemcore-bot-sessions`.
- [x] Move memory architecture tests into `golemcore-bot-memory`.
- [x] Move tools architecture tests into `golemcore-bot-tools`.
- [x] Move tracing architecture tests into `golemcore-bot-tracing`.
- [x] Evaluate whether scheduling is stable enough for a dedicated Maven module.
- [x] Move reusable context assembly (`ContextAssembler`, `PromptComposer`, `ContextBuildingSystem`) and their tests into `golemcore-bot-runtime-core`.
- [x] Keep `golemcore-bot-app` focused on bootstrapping, adapters, security, launchers, and wiring.
- [x] Keep concrete context layers and app-dependent resolvers in `golemcore-bot-app` until their dependencies are port-only.

## P2 - Platform Contracts

- [x] Add dashboard API contract checks.
- [x] Decide whether dashboard should use generated OpenAPI or generated JSON-schema TypeScript contracts.
- [x] Define runtime events as a first-class internal API.
- [x] Define plugin permissions for filesystem, network, browser, mail, calendar, memory, trace, and config mutation.
- [x] Add trace snapshot schema versioning.
- [x] Add runtime config section schema versioning and migration rules.
- [x] Split `RuntimeConfigService` cache, API redaction, and mutation rollback into dedicated collaborators.
- [x] Split runtime config defaults and section normalization into concrete section owner services.
- [x] Split `SessionService` lifecycle internals into repository, cache, id factory, compaction, inheritance, and deletion collaborators.
- [x] Split tool execution from registry, result post-processing, attachment extraction, and artifact persistence.

## Definition Of Done

- [x] Maven module names map to clear reasons for change.
- [x] `golemcore-bot-app` contains composition-root code, not core runtime implementation.
- [x] `domain/service` is no longer a bucket package in `golemcore-bot-app`.
- [x] `AgentLoop` remains a thin turn-lifecycle orchestrator.
- [x] Context assembly and its pipeline system are owned by `golemcore-bot-runtime-core`, with app retaining only concrete layer wiring.
- [x] Runtime config ownership is split by section behind a compatible facade.
- [x] Session lifecycle can be tested separately from storage adapters.
- [x] Tool execution can be tested separately from registry, confirmation, and artifact persistence.
- [x] `golemcore-bot-contracts` has no Spring/runtime implementation details.
- [x] Dashboard API is generated from, or validated against, a contract.
- [x] Architecture tests describe the real module and package boundaries.
