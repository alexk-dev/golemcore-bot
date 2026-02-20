# Configuration Guide

How to configure GolemCore Bot.

## Where Configuration Lives

There are three main configuration surfaces:

1. **Storage workspace** (filesystem) controlled by Spring properties.
2. **Runtime config** (editable at runtime, persisted to workspace).
3. **User preferences** (editable at runtime, persisted to workspace).

### Workspace Base Path

The bot stores all state under a base path:

- Spring property: `bot.storage.local.base-path`
- Docker/JAR env var (via Spring): `STORAGE_PATH`

In Docker, you almost always want this mounted:

```bash
docker run -d \
  -e STORAGE_PATH=/app/workspace \
  -v golemcore-bot-data:/app/workspace \
  -p 8080:8080 \
  golemcore-bot:latest
```

## Dashboard (Recommended)

The easiest way to configure the bot is via the dashboard:

- `http://localhost:8080/dashboard`

See: [Dashboard Guide](DASHBOARD.md)

## Runtime Config (preferences/runtime-config.json)

Runtime config is persisted to the workspace:

- File: `preferences/runtime-config.json`
- Dashboard API: `GET /api/settings/runtime`, `PUT /api/settings/runtime`

Secrets (API keys) can be provided as plain strings in JSON; they are wrapped as `Secret` internally.

### LLM Providers

Configure provider credentials under `llm.providers`.

- Provider key is the model prefix in `provider/model` (and should match `provider` in `models/models.json`).
- `apiType` selects the wire protocol used by the adapter.
- Supported `apiType` values: `openai` (default), `anthropic`, `gemini`.

```json
{
  "llm": {
    "providers": {
      "openai": {
        "apiKey": "sk-proj-...",
        "apiType": "openai",
        "baseUrl": null,
        "requestTimeoutSeconds": 300
      },
      "anthropic": {
        "apiKey": "sk-ant-...",
        "apiType": "anthropic"
      },
      "google": {
        "apiKey": "AIza...",
        "apiType": "gemini"
      }
    }
  }
}
```

Notes:

- Use lowercase `apiType` values.
- `baseUrl` is optional; for `gemini` it is typically left empty.

### Model Configuration

Tier routing is configured in runtime config under `modelRouter`:

```json
{
  "modelRouter": {
    "balancedModel": "openai/gpt-5.1",
    "balancedModelReasoning": "none",
    "smartModel": "openai/gpt-5.1",
    "smartModelReasoning": "none",
    "codingModel": "openai/gpt-5.2",
    "codingModelReasoning": "none",
    "deepModel": "openai/gpt-5.2",
    "deepModelReasoning": "none",
    "dynamicTierEnabled": true,
    "temperature": 0.7
  }
}
```

Notes:

- `*Reasoning` values depend on the selected model (see `models/models.json`).
- The bot also uses a dedicated `routingModel` internally for some routing/classification flows.

### Tools

Most tool enablement flags are in `runtime-config.json` under `tools`:

```json
{
  "tools": {
    "filesystemEnabled": true,
    "shellEnabled": true,
    "browserEnabled": true,
    "browserType": "playwright",
    "browserHeadless": true,
    "browserTimeout": 30000,
    "browserUserAgent": "...",
    "browserApiProvider": "brave",

    "braveSearchEnabled": false,
    "braveSearchApiKey": "...",

    "skillManagementEnabled": true,
    "skillTransitionEnabled": true,
    "tierEnabled": true,
    "goalManagementEnabled": true,

    "imap": {
      "enabled": false,
      "host": "imap.example.com",
      "port": 993,
      "username": "user@example.com",
      "password": "...",
      "security": "ssl",
      "sslTrust": "",
      "connectTimeout": 10000,
      "readTimeout": 30000,
      "maxBodyLength": 50000,
      "defaultMessageLimit": 20
    },
    "smtp": {
      "enabled": false,
      "host": "smtp.example.com",
      "port": 587,
      "username": "user@example.com",
      "password": "...",
      "security": "starttls",
      "sslTrust": "",
      "connectTimeout": 10000,
      "readTimeout": 30000
    }
  }
}
```

See: [Tools Guide](TOOLS.md)

### Browser Tool

The `browse` tool uses Playwright.

Docker requirements (Chromium sandbox):

```bash
docker run -d \
  --shm-size=256m \
  --cap-add=SYS_ADMIN \
  ...
```

### Security

Input and tool safety settings live in runtime config under `security`:

```json
{
  "security": {
    "sanitizeInput": true,
    "detectPromptInjection": true,
    "detectCommandInjection": true,
    "maxInputLength": 10000,
    "allowlistEnabled": true,

    "toolConfirmationEnabled": false,
    "toolConfirmationTimeoutSeconds": 60
  }
}
```

### Rate Limiting

```json
{
  "rateLimit": {
    "enabled": true,
    "userRequestsPerMinute": 20,
    "userRequestsPerHour": 100,
    "userRequestsPerDay": 500,
    "channelMessagesPerSecond": 30,
    "llmRequestsPerMinute": 60
  }
}
```

### Compaction

Automatic history compaction (to prevent context overflow):

```json
{
  "compaction": {
    "enabled": true,
    "maxContextTokens": 50000,
    "keepLastMessages": 20
  }
}
```

### Turn Budget

Limits for a single user request (one internal tool loop run):

```json
{
  "turn": {
    "maxLlmCalls": 200,
    "maxToolExecutions": 500,
    "deadline": "PT1H"
  }
}
```

### Telegram

Telegram channel settings are stored in runtime config under `telegram`:

```json
{
  "telegram": {
    "enabled": false,
    "token": "123456:ABC-DEF...",
    "authMode": "invite_only",
    "allowedUsers": []
  }
}
```

### Voice

```json
{
  "voice": {
    "enabled": false,
    "apiKey": "...",
    "voiceId": "21m00Tcm4TlvDq8ikWAM",
    "ttsModelId": "eleven_multilingual_v2",
    "sttModelId": "scribe_v1",
    "speed": 1.0,
    "telegramRespondWithVoice": false,
    "telegramTranscribeIncoming": false
  }
}
```

### Auto Mode

```json
{
  "autoMode": {
    "enabled": false,
    "tickIntervalSeconds": 30,
    "taskTimeLimitMinutes": 10,
    "autoStart": true,
    "maxGoals": 3,
    "modelTier": "default",
    "notifyMilestones": true
  }
}
```

### RAG

```json
{
  "rag": {
    "enabled": false,
    "url": "http://localhost:9621",
    "apiKey": "",
    "queryMode": "hybrid",
    "timeoutSeconds": 10,
    "indexMinLength": 50
  }
}
```

### MCP

```json
{
  "mcp": {
    "enabled": true,
    "defaultStartupTimeout": 30,
    "defaultIdleTimeout": 5
  }
}
```

See: [MCP Integration](MCP.md)

## User Preferences (preferences/settings.json)

User preferences are stored separately:

- File: `preferences/settings.json`

This includes language/timezone/notifications and per-user tier/model overrides.

Webhooks configuration also lives here. See: [Webhooks Guide](WEBHOOKS.md)

## Models (models/models.json)

Model capabilities are stored in the workspace at:

- `models/models.json`

On first run, the bot copies a bundled `models.json` into the workspace. The dashboard can edit and reload models.

See: [Model Routing Guide](MODEL_ROUTING.md)

## Spring Properties (bot.*)

Some settings are still controlled via Spring properties (application config), typically using env vars in Docker:

- Workspace paths: `STORAGE_PATH`, `TOOLS_WORKSPACE`
- Dashboard toggle: `DASHBOARD_ENABLED`
- Self-update controls: `BOT_UPDATE_ENABLED`, `UPDATE_PATH`, `BOT_UPDATE_MAX_KEPT_VERSIONS`, `BOT_UPDATE_CHECK_INTERVAL`, `BOT_UPDATE_CONFIRM_TTL`
- Allowed providers in model picker: `BOT_MODEL_SELECTION_ALLOWED_PROVIDERS`
- Tool result truncation: `bot.auto-compact.max-tool-result-chars`
- Plan mode feature flag: `bot.plan.enabled`

### Self-Update (Core)

Core self-update is controlled by Spring properties under `bot.update.*`.

- `bot.update.enabled` defaults to `true` (disable with `BOT_UPDATE_ENABLED=false`).
- Release repository is fixed in code: `alexk-dev/golemcore-bot`.
- GitHub token is not required (public repository).
- Release asset glob is fixed in code: `bot-*.jar`.

Configurable properties:

- `bot.update.updates-path` (`UPDATE_PATH`, default `/data/updates`)
- `bot.update.max-kept-versions` (`BOT_UPDATE_MAX_KEPT_VERSIONS`, default `3`)
- `bot.update.check-interval` (`BOT_UPDATE_CHECK_INTERVAL`, default `PT1H`)
- `bot.update.confirm-ttl` (`BOT_UPDATE_CONFIRM_TTL`, default `PT2M`)

## Storage Layout

Default (macOS/Linux): `~/.golemcore/workspace`

```
workspace/
├── auto/                    # auto mode + plan mode state
├── memory/                  # MEMORY.md + daily notes
├── models/                  # models.json (capabilities)
├── preferences/             # settings.json, runtime-config.json, admin.json
├── sessions/                # conversation sessions
├── skills/                  # skills (SKILL.md)
└── usage/                   # usage logs
```

## Diagnostics

- Health: `GET /api/system/health`
- Config flags: `GET /api/system/config`
- Diagnostics: `GET /api/system/diagnostics`
