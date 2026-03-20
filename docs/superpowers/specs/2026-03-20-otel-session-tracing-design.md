# OTel-Compatible Internal Session Tracing Design

Date: 2026-03-20
Status: Approved design, pending implementation plan
Branch: `feat/request-tracing-design`

## Summary

GolemCore Bot should gain an OTel-compatible internal tracing system that captures end-to-end product flows, stores trace data directly in `session.pb`, and exposes built-in analysis and visualization tools in the dashboard.

Tracing is enabled by default.
Payload snapshots are disabled by default.
When enabled, payload snapshots are stored in compressed form inside the session protobuf and are governed by configurable per-session size limits.

## Goals

- Provide a single trace model for product flows from inbound request to outbound response.
- Persist traces with the session so forensic debugging remains possible without external infrastructure.
- Keep the trace model OTel-compatible so future OTLP export remains straightforward.
- Support full request/response payload snapshots, compressed and budget-limited.
- Add first-party dashboard tools for trace analysis and visualization.
- Make tracing behavior configurable through runtime config and the dashboard UI.

## Non-Goals

- Replacing existing usage tracking or metrics aggregation.
- Requiring Jaeger, Tempo, Zipkin, or an OTLP collector for normal operation.
- Capturing every Spring internal framework span by default.
- Shipping redaction policies in the first iteration beyond existing sanitization behavior.

## Product Decisions

- Architecture choice: OTel-compatible internal tracing.
- Storage choice: trace data is stored in `session.pb`, not in a sidecar trace store.
- Tracing default: enabled.
- Payload snapshots default: disabled.
- Default per-session trace budget: `128 MB`.
- Payload snapshots, budgets, and capture scopes must be configurable through the UI.

## Current State

The codebase already has partial observability primitives, but not a true tracing system:

- `RuntimeEventService` records per-turn events, but these are not trace/span records.
- `MdcSupport` and `AutoRunContextSupport` propagate MDC only for selected auto-run paths.
- `AgentLoop` and `DefaultToolLoopSystem` are strong instrumentation points, but they do not carry a canonical trace context.
- `LlmRequest` carries `sessionId` but not `traceId`, `spanId`, or snapshot policy.
- Session persistence is centralized through `SessionService`, `session.proto`, and `SessionProtoMapper`.

The result is partial observability but no consistent request traceability across web, webhook, websocket, Telegram, LLM, tool, and outbound delivery boundaries.

## Selected Approach

### Chosen approach

Implement a canonical internal trace model with OTel-compatible fields:

- `traceId`
- `spanId`
- `parentSpanId`
- `kind`
- `status`
- `attributes`
- `events`
- optional compressed payload snapshots

This internal model becomes the source of truth.

### Rejected alternatives

1. External collector as primary storage
   - rejected because the product requirement is session-centric local traceability
2. Extending `RuntimeEvent` into an ad hoc trace format
   - rejected because it would drift into a proprietary schema without clean export semantics
3. Metrics-first instrumentation via Micrometer Observation only
   - rejected because metrics and observations alone do not satisfy payload-centric forensic analysis

## High-Level Architecture

Each inbound product request creates a root trace.
Each meaningful processing stage becomes a span under that trace.
Trace data is accumulated during the turn and persisted into the owning session record.

### Root trace creation

Root traces are created for:

- dashboard HTTP API requests that start product work
- webhook requests
- websocket inbound messages
- Telegram inbound messages
- auto-scheduler and delayed internal wakeups

### Core span tree

Typical span hierarchy:

1. inbound request
2. auth and request parsing
3. session load
4. agent loop
5. per-system execution
6. LLM call
7. tool execution
8. session save
9. outbound response or callback delivery

### Correlation model

The following identifiers must be propagated consistently:

- `traceId`
- `spanId`
- `parentSpanId`
- `sessionId`
- `conversationKey`
- `runId` where applicable

These identifiers must exist in:

- in-memory trace context
- MDC for logs
- `AgentContext` and `ContextAttributes`
- `LlmRequest`
- tool execution context
- outbound delivery metadata where useful

## Protobuf Storage Design

Tracing must be stored as first-class protobuf structures, not hidden in the generic session metadata map.

### `session.proto` additions

Add to `AgentSessionRecord`:

- `repeated TraceRecord traces`
- `TraceStorageStats trace_stats`

Add new messages:

- `TraceRecord`
- `SpanRecord`
- `SpanEventRecord`
- `SnapshotRecord`
- `TraceStorageStats`

### Trace record shape

`TraceRecord` stores:

- `trace_id`
- `root_span_id`
- `trace_name`
- `started_at`
- `ended_at`
- `repeated SpanRecord spans`
- `bool truncated`
- `uint64 compressed_snapshot_bytes`
- `uint64 uncompressed_snapshot_bytes`

`SpanRecord` stores:

- `span_id`
- `parent_span_id`
- `name`
- `kind`
- `status_code`
- `status_message`
- `started_at`
- `ended_at`
- `map<string, JsonValue> attributes`
- `repeated SpanEventRecord events`
- `repeated SnapshotRecord snapshots`

`SnapshotRecord` stores:

- `snapshot_id`
- `role`
- `content_type`
- `encoding`
- `bytes compressed_payload`
- `uint64 original_size`
- `uint64 compressed_size`
- `bool truncated`

### Compression

Payload snapshots are stored compressed.

Recommended default:

- encoding: `zstd`

If implementation constraints make `zstd` too expensive for phase 1, the fallback may be a simpler compression strategy, but the schema should still store the encoding explicitly so `zstd` remains the target format.

## Trace Retention and Budget Policy

Tracing data must obey a configurable per-session budget.

### Default policy

- tracing enabled: `true`
- payload snapshots enabled: `false`
- session trace budget: `128 MB`

### Budget semantics

- budget is counted against compressed payload bytes stored in `session.pb`
- span and event headers remain even when snapshots are dropped
- overflow policy is oldest-snapshot-first eviction
- evictions must set clear truncation markers at both trace and session level

### Required configurable limits

- `sessionTraceBudgetMb`
- `maxSnapshotSizeKb`
- `maxSnapshotsPerSpan`
- `maxTracesPerSession`

## Runtime Config and UI

Add a new `tracing` section to runtime config.

### Required fields

- `enabled`
- `payloadSnapshotsEnabled`
- `sessionTraceBudgetMb`
- `maxSnapshotSizeKb`
- `maxSnapshotsPerSpan`
- `maxTracesPerSession`
- `captureInboundPayloads`
- `captureOutboundPayloads`
- `captureToolPayloads`
- `captureLlmPayloads`

### Defaults

- `enabled=true`
- `payloadSnapshotsEnabled=false`
- `sessionTraceBudgetMb=128`

### Dashboard requirements

Expose tracing configuration in the UI:

- tracing enabled toggle
- payload snapshots toggle
- session trace budget input
- granular capture toggles for inbound, outbound, tools, and LLM

The UI must explicitly warn that payload snapshots:

- increase session size
- can preserve sensitive request or response content

## Instrumentation Points

### Inbound

Add root trace creation to:

- dashboard HTTP handlers
- webhook endpoints
- websocket inbound message processing
- Telegram adapter inbound processing
- auto-mode and delayed-action triggers

### Core runtime

Add spans for:

- session load and session save
- agent loop execution
- each pipeline system
- compaction
- retries

### LLM boundary

Every LLM call must produce:

- one span for request preparation
- one span for provider call
- one span or event for response parsing if separated

Optional snapshots:

- normalized LLM request
- normalized LLM response

### Tool boundary

Every tool execution must produce:

- one tool span
- attributes for tool name, status, duration, failure kind

Optional snapshots:

- tool input
- tool output

### Outbound boundary

Outbound delivery must produce spans for:

- dashboard/websocket response push
- Telegram send
- webhook callback delivery

Optional snapshots:

- outbound payload
- callback request and callback response

## Logging and Correlation

Standardize MDC keys across all product flows:

- `trace`
- `span`
- `session`
- `conv`
- `run`

Logging patterns should include these keys so logs can be correlated with session traces.

Current auto-run-only MDC support should be generalized to all traceable request types.

## Analysis and Visualization

### Session trace explorer

The dashboard should provide a trace viewer for a session with:

- trace list for the session
- span tree
- timeline view
- duration and status indicators
- retry and failure highlighting
- filters by span kind

### Snapshot inspection

Snapshot viewer should support:

- request and response body viewing
- compressed size vs original size display
- truncation markers
- copy and download actions

### Diagnostics

Add derived analysis:

- critical path
- slowest spans
- failed spans
- trace truncation summary
- snapshot eviction summary

### Export

Support exporting a trace in OTel-compatible JSON form.

This export is for debugging and future integration, not the primary storage format.

## Compatibility and Migration

Existing sessions without tracing data must continue to load normally.

Session protobuf parsing must remain backward-compatible:

- missing trace sections are treated as empty
- new trace sections do not change existing message semantics

No migration rewrite is required for already persisted sessions.

## Performance Expectations

Phase 1 should prioritize low overhead when payload snapshots are disabled.

Required expectations:

- minimal overhead for span/event creation
- explicit fast path when tracing is disabled
- snapshot compression only when snapshot capture is enabled
- no session-size explosion beyond configured budget

## Risks

### Session growth

Payload snapshots can grow `session.pb` aggressively.
Mitigation: compressed storage, explicit budgets, snapshot eviction, truncation markers.

### Sensitive data retention

Full payload snapshots can retain secrets and private content.
Mitigation: default snapshots off, explicit UI warning, later redaction controls.

### Partial propagation

A tracing system is only useful if correlation survives across boundaries.
Mitigation: define canonical propagation rules early and enforce them across ingress, agent loop, LLM, tools, and outbound.

### Instrumentation drift

Ad hoc per-class span naming or attributes will degrade quickly.
Mitigation: define one canonical span taxonomy and shared helpers.

## Rollout Plan

1. Add tracing runtime config and dashboard settings
2. Extend protobuf schema and session mapper
3. Introduce canonical trace model and propagation helpers
4. Add ingress root traces and MDC standardization
5. Instrument agent loop, systems, LLM, tools, and outbound delivery
6. Add optional compressed payload snapshots with budget enforcement
7. Add dashboard trace explorer and export tools

## Recommendation

Proceed with the OTel-compatible internal tracing model as the product-default path.

This gives GolemCore Bot:

- session-native forensic traceability
- future export compatibility
- configurable payload persistence
- first-party UX for debugging and analysis

