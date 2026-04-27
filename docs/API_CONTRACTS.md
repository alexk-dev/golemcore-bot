# API Contracts

This document defines the cross-boundary contracts that keep runtime modules, plugin/tool execution, observability, and
the dashboard API aligned.

## Runtime Events

Runtime events are first-class typed boundary objects:

- Java contract: `RuntimeEvent`
- Event type enum: `RuntimeEventType`
- Publish boundary: `RuntimeEventPublishPort`
- Dashboard/shared schema: `docs/contracts/dashboard-api/runtime-event.v1.schema.json`

Runtime event payloads must remain structured maps. New event types should be added to `RuntimeEventType`, reflected in
the JSON schema enum, and covered by a contract test or adapter test in the owning module.

## Plugin And Tool Permissions

Tool and plugin capabilities must be modeled as runtime-enforced permissions, not UI-only configuration. The shared
permission schema is `docs/contracts/dashboard-api/tool-permission.v1.schema.json`.

Permission scopes are intentionally explicit:

- filesystem access is path-scoped and mode-scoped;
- network access is domain-scoped;
- shell/process access is command-scoped;
- secrets, memory, session, model/provider, browser, email, calendar, and contact access are separate capabilities.

Tool execution must receive an explicit `ToolExecutionContext`; ThreadLocal access remains compatibility behavior only.

## Observability

Trace exports use a versioned schema: `docs/contracts/dashboard-api/trace-snapshot.v1.schema.json`.

The schema fixes the externally visible shape for traces, spans, events, snapshots, status codes, timing fields, and
redaction metadata. Secrets and PII must be redacted before they cross the trace/dashboard boundary.

## Dashboard Drift Detection

Dashboard API drift is guarded by shared JSON schemas under `docs/contracts/dashboard-api/` and the generated TypeScript
index at `dashboard/src/api/generated/dashboardApiContracts.ts`.

Run `npm run contracts:check` from `dashboard/` to validate the schemas and verify the generated index is current. Run
`npm run contracts:generate` after changing any schema.
