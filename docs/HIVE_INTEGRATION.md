# Hive Integration

How `golemcore-bot` joins and operates under `golemcore-hive`.

## Configuration Layers

The Hive integration has three layers:

1. `RuntimeConfig.HiveConfig`
   - effective runtime and dashboard-visible settings
2. `bot.hive.*`
   - optional managed bootstrap override from Spring properties
3. dedicated Hive session state
   - machine auth/session storage for `golemId`, JWTs, and reconnect state

Rules:

- `RuntimeConfig.HiveConfig` is the source of truth for editable runtime behavior.
- If `bot.hive.*` is present, the bot materializes those values into the effective Hive config and exposes that section as read-only in the dashboard.
- Secrets and rotating machine tokens must not be stored in runtime config.

## Managed Bootstrap Properties

The bot supports these managed bootstrap properties:

- `bot.hive.enabled`
- `bot.hive.join-code`
- `bot.hive.display-name`
- `bot.hive.host-label`
- `bot.hive.auto-connect-on-startup`

If any of these are set, Hive settings are treated as property-managed.

## Join Code Format

Hive onboarding uses a single join code string:

- format: `<TOKEN>:<URL>`

Example:

- `et_abc123.secretvalue:https://hive.example.com`

The bot parses the join code by splitting on the first `:`:

- left side: reusable enrollment token
- right side: Hive server URL

## Enrollment Token Semantics

- Enrollment tokens are reusable until revoked or expired.
- Revoke blocks new joins only.
- Persistent machine authentication after registration is JWT-based.

## Runtime Fields

`hive.json` stores only non-secret runtime fields:

- `enabled`
- `serverUrl`
- `displayName`
- `hostLabel`
- `autoConnect`
- `managedByProperties`

## Session State

Persistent machine auth is stored separately in `preferences/hive-session.json`.

That file contains bot-owned machine state such as:

- `golemId`
- `serverUrl`
- `controlChannelUrl`
- access and refresh JWTs
- token expiration timestamps
- `heartbeatIntervalSeconds`
- reconnect metadata such as `lastConnectedAt`, `lastHeartbeatAt`, and `lastError`

The dashboard never receives raw join tokens from `RuntimeConfig.HiveConfig`.

Buffered control commands are persisted separately in `preferences/hive-control-inbox.json`.

## Dashboard Actions

The dashboard Hive tab now uses dedicated action endpoints:

- `GET /api/hive/status`
- `POST /api/hive/join`
- `POST /api/hive/reconnect`
- `POST /api/hive/leave`

`POST /api/hive/join` accepts a transient `joinCode` field only for the action itself. The value is not persisted into `hive.json`.

## Current Scope

This document covers config authority and bootstrap semantics.

The current bot-side slice includes:

- manual or managed join code parsing
- machine registration against Hive
- persisted `HiveSessionState`
- reconnect via refresh-token rotation
- periodic heartbeat maintenance
- control channel connect and reconnect loop
- buffered control command inbox for transport diagnostics
- control command dispatch into the regular `hive` channel execution queue
- `events:batch` publishing for command acknowledgements, runtime events, progress updates, thread messages, and usage snapshots
- explicit `hive_lifecycle_signal` tool for Hive card-bound turns
- automatic lifecycle signals for `WORK_STARTED`, `WORK_FAILED`, and interruption-driven `WORK_CANCELLED`
- stop/cancel control command handling via `command.stop` and `command.cancel`
- dashboard status and join/reconnect/leave controls

## Control Command Event Types

Hive may send these control command envelope event types over the control channel:

- `command`
  - regular card-bound prompt execution; requires `body`
- `command.stop`
  - request stop for the active Hive thread run
- `command.cancel`
  - alias of `command.stop` for control-plane initiated cancellation

`command.stop` and `command.cancel` do not enqueue a new inbound user message. They route directly to the existing session stop path for the `hive` channel.

## Lifecycle Signal Emission

The bot now emits card lifecycle signals in two ways:

1. automatically from deterministic runtime outcomes
   - `TURN_STARTED -> WORK_STARTED`
   - `TURN_FAILED -> WORK_FAILED`
   - `TURN_FINISHED(reason=user_interrupt) -> WORK_CANCELLED`
2. explicitly from the Hive-only `hive_lifecycle_signal` tool
   - `PROGRESS_REPORTED`
   - `BLOCKER_RAISED`
   - `BLOCKER_CLEARED`
   - `REVIEW_REQUESTED`
   - `WORK_COMPLETED`
   - `WORK_FAILED`

This keeps Hive board state driven by structured events instead of parsing assistant prose.
