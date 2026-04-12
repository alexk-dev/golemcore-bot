# Hive SDLC Integration Plan

## Goal

Implement built-in SDLC-facing Hive functionality in `golemcore-bot` so card-bound Hive turns can use safe, typed tools for reading SDLC context and reporting work state back to Hive. The integration must remain part of the bot runtime, not an external plugin.

## Scope

### Built-in bot tools

Add Hive SDLC tools that are advertised only for Hive sessions and are individually configurable:

- `hive_get_current_context`
  - Returns current Hive metadata from the active turn: `golemId`, `cardId`, `threadId`, `commandId`, `runId`.
- `hive_get_card`
  - Reads a card from Hive by explicit `card_id` or the active turn card.
- `hive_search_cards`
  - Lists cards from Hive with optional filters such as `board_id`, `service_id`, `kind`, `parent_card_id`, `epic_card_id`, `review_of_card_id`, `objective_id`, `include_archived`.
- `hive_post_thread_message`
  - Posts a structured operator-facing note into the active or explicit Hive thread.
- `hive_request_review`
  - Requests Hive review for the active or explicit card.
- `hive_create_followup_card`
  - Creates a follow-up/subtask/review card in Hive, defaulting relationships from the active card when requested.
- `hive_lifecycle_signal`
  - Existing tool stays built-in and becomes governed by the same SDLC function toggle model.

### Runtime settings

Extend `RuntimeConfig.HiveConfig` with an `sdlc` section. Each tool/function must be optional and enabled by default when Hive integration is active:

```json
{
  "hive": {
    "enabled": true,
    "sdlc": {
      "currentContextEnabled": true,
      "cardReadEnabled": true,
      "cardSearchEnabled": true,
      "threadMessageEnabled": true,
      "reviewRequestEnabled": true,
      "followupCardCreateEnabled": true,
      "lifecycleSignalEnabled": true
    }
  }
}
```

Default behavior:

- If Hive is disabled, all Hive SDLC tools are disabled.
- If Hive is enabled and an SDLC toggle is unset, the function is treated as enabled.
- Dashboard settings show toggles under the Hive tab.

### Hive API use

Use the existing machine Hive session and access token. Add bot-side client methods for machine-scoped SDLC endpoints:

- `GET /api/v1/golems/{golemId}/sdlc/cards/{cardId}`
- `GET /api/v1/golems/{golemId}/sdlc/cards?...`
- `POST /api/v1/golems/{golemId}/sdlc/cards`
- `POST /api/v1/golems/{golemId}/sdlc/threads/{threadId}/messages`
- `POST /api/v1/golems/{golemId}/sdlc/cards/{cardId}:request-review`

Lifecycle signals continue to use `events:batch` through the existing event publisher.

### Hive companion change

Add the machine-scoped Hive endpoints above with `golems:sdlc:read` / `golems:sdlc:write` scopes. Hive remains the authority for card state and filters machine API access to cards assigned/review-assigned to the golem or linked to accessible cards.

## Architecture

- Domain-facing API: extend `HiveGatewayPort` with SDLC operations.
- Adapter: extend `HiveApiClient` and `HiveGatewayAdapter`.
- Session auth: add a small domain service that resolves current Hive session state and performs operations using `serverUrl`, `golemId`, and `accessToken`.
- Tools: implement each new tool as a `ToolComponent`, using `AgentContextHolder` directly and avoiding async thread pools because the context is ThreadLocal.
- Advertisement: update `ToolLayer` to advertise Hive tools only for Hive sessions and only when their feature toggle is enabled.
- UI: update the dashboard Hive tab with per-function toggles.

## Tests

Add/update tests for:

- runtime config defaulting/normalization of Hive SDLC toggles;
- tool enablement and policy denial outside Hive sessions;
- API client request path/body/auth mapping;
- gateway adapter mapping;
- dashboard mapping of `hive.sdlc` settings;
- Hive machine SDLC endpoint access and denial for unrelated golems.

## Verification

Run at minimum:

```bash
./mvnw test
cd dashboard && npm run lint && npm run build
```

After pushing and opening the PR, monitor GitHub checks with `gh pr checks` and fix failures until the PR is green.
