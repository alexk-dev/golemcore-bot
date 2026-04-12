# QA Integration Test Plan — golemcore-bot

## Goal

Add a senior-QA oriented integration test suite that exercises real Spring wiring and persisted runtime state without reaching external services. The suite is intentionally designed to expose production defects rather than mock them away.

## Boundaries

- No real external network calls, LLM calls, Telegram calls, or Hive calls.
- Use Spring Boot/WebFlux integration tests, temporary filesystem storage, and test doubles for telemetry or remote ports when needed.
- Keep tests deterministic and focused on externally observable contracts: HTTP status, JSON response shape, persistence on disk, security behaviour, and cross-section runtime settings semantics.

## Test matrix

| Area | Risk | Integration coverage |
| --- | --- | --- |
| Application boot | Broken bean graph, missing properties, lifecycle startup failures | Full `@SpringBootTest` smoke already exists and remains the baseline |
| Dashboard auth + security | JWT filter or security matchers accidentally expose/lock API endpoints | Real random-port login flow, protected `/api/settings/runtime`, unauthorized access, refresh-cookie flow |
| Runtime settings persistence | UI saves appear successful but are not persisted or returned correctly | Real HTTP `GET/PUT /api/settings/runtime/*` against temp storage, then verify follow-up `GET` |
| Runtime settings validation | Invalid config accepted or wrong status mapping | Invalid shell env, invalid Hive URL, invalid turn deadline/ranges via HTTP endpoints |
| Secret retention | Updating settings with masked/empty secrets drops existing secrets | Add focused integration contract via runtime settings service/API path where practical |
| Hive SDLC toggles | Hive enabled but dependent SDLC feature toggles not defaulted/independently controllable | Verify runtime Hive config and adjacent plan/self-evolving Hive defaults remain visible and mutable through settings API |
| Webhooks | Auth bypass, payload validation, wrong mapping action | Existing Webhook HTTP integration remains; extend later if failures point there |
| Tool/filesystem safety | Traversal/sandbox regressions | Existing adapter/tool tests remain; extend later if shell/filesystem defects appear |

## First implementation slice

1. Add a reusable Spring random-port integration base with temp storage and disabled plugin/update side effects.
2. Add Dashboard auth/security integration tests.
3. Add Runtime settings HTTP integration tests for persistence, validation, and Hive-related feature toggles.
4. Run the new tests and record failures as production defects; do not weaken assertions to hide bugs.
