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

## Current Scope

This document covers config authority and bootstrap semantics.

Transport-specific flows such as:

- machine registration,
- token rotation,
- heartbeat,
- control channel,
- `events:batch`

are implemented incrementally in the Hive integration epic PR.
