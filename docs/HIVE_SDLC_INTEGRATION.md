# Hive SDLC Integration

`golemcore-bot` includes built-in Hive SDLC tools for Hive card-bound turns. These tools are runtime functionality, not external plugins.

## Availability

Hive SDLC tools are advertised only when both conditions are true:

1. The current session channel type is `hive`.
2. The specific SDLC function is enabled in Hive settings.

All SDLC function toggles default to enabled when Hive integration is active. Disabling Hive disables the effective availability of every Hive SDLC function.

## Runtime settings

The toggles are stored under `preferences/hive.json`:

```json
{
  "enabled": true,
  "serverUrl": "https://hive.example.com",
  "displayName": "Builder",
  "hostLabel": "builder-lab-a",
  "autoConnect": true,
  "managedByProperties": false,
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
```

The dashboard exposes these toggles on **Settings → Hive → SDLC agent functions**.

## Tools

| Tool | Purpose |
| --- | --- |
| `hive_get_current_context` | Returns current `threadId`, `cardId`, `commandId`, `runId`, `golemId`, channel, and chat id. |
| `hive_get_card` | Reads the active Hive card or an explicit `card_id`. |
| `hive_search_cards` | Searches cards by service, board, kind, parent/epic/review/objective filters, and archive flag. |
| `hive_post_thread_message` | Posts an operator-facing SDLC note into the active or explicit Hive thread. |
| `hive_request_review` | Requests Hive review for the active or explicit card. |
| `hive_create_followup_card` | Creates a follow-up/subtask/review card. Can inherit the active card as parent. |
| `hive_lifecycle_signal` | Emits structured board lifecycle signals through Hive event ingestion. |

## Hive API usage

The bot uses its existing Hive machine session and bearer access token. The SDLC tools call the machine-scoped Hive endpoints added for this integration:

- `GET /api/v1/golems/{golemId}/sdlc/cards/{cardId}`
- `GET /api/v1/golems/{golemId}/sdlc/cards?...`
- `POST /api/v1/golems/{golemId}/sdlc/cards`
- `POST /api/v1/golems/{golemId}/sdlc/threads/{threadId}/messages`
- `POST /api/v1/golems/{golemId}/sdlc/cards/{cardId}:request-review`
- `POST /api/v1/golems/{golemId}/events:batch` for lifecycle signals

These endpoints require Hive machine scopes:

- `golems:sdlc:read`
- `golems:sdlc:write`

Existing machine sessions get these scopes during token rotation; new sessions get them during enrollment.

## Authority model

Hive remains the owner of board state. The bot reads Hive SDLC state, creates approved SDLC records through Hive APIs, and emits structured lifecycle signals. It does not mutate card columns directly.
