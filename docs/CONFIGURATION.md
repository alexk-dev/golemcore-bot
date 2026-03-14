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
- These same provider profiles are used by the dashboard `Model Catalog` for live model discovery via `/api/models/discover/{provider}`.

### Model Configuration

Model selection now has three layers:

1. `llm.providers` defines provider profiles and credentials.
2. `models/models.json` defines model capability metadata.
3. `modelRouter` maps routing/tier slots to concrete models.

Tier routing is configured in runtime config under `modelRouter`:

```json
{
  "modelRouter": {
    "routingModel": "openai/gpt-5.2-codex",
    "routingModelReasoning": "none",
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

- `routingModel` is used for internal routing/classification flows.
- `*Reasoning` values depend on the selected model entry from `models/models.json`.
- In the dashboard, this is split across `LLM Providers`, `Model Catalog`, and `Model Router`.
- `/api/models/available` returns models grouped by provider, filtered to provider profiles that are API-ready.
- When a discovered model id conflicts across providers, the catalog can persist a provider-scoped id such as `provider/model`.

### Tools

Core tool enablement flags are in `runtime-config.json` under `tools`:

```json
{
  "tools": {
    "filesystemEnabled": true,
    "shellEnabled": true,
    "skillManagementEnabled": true,
    "skillTransitionEnabled": true,
    "tierEnabled": true,
    "goalManagementEnabled": true,
    "shellEnvironmentVariables": []
  }
}
```

Official integrations that are loaded through the plugin runtime keep their
own configuration under `preferences/plugins/<owner>/<plugin>.json`. This
includes the first-party browser, Brave Search, Tavily Search, Firecrawl,
Perplexity Sonar, weather, mail, and LightRAG modules.

See: [Tools Guide](TOOLS.md)

### Browser Tool

The `browse` tool is provided by the official `golemcore/browser` plugin and
uses Playwright.

Configuration lives in `preferences/plugins/golemcore/browser.json`:

```json
{
  "enabled": true,
  "headless": true,
  "timeoutMs": 30000,
  "userAgent": "..."
}
```

Docker requirements (Chromium sandbox):

```bash
docker run -d \
  --shm-size=256m \
  --cap-add=SYS_ADMIN \
  ...
```

### Tavily Search Tool

The `tavily_search` tool is provided by the official
`golemcore/tavily-search` plugin.

Configuration lives in `preferences/plugins/golemcore/tavily-search.json`:

```json
{
  "enabled": true,
  "apiKey": "tvly-...",
  "defaultMaxResults": 5,
  "defaultTopic": "general",
  "defaultSearchDepth": "basic",
  "includeAnswer": true,
  "includeRawContent": false
}
```

### Firecrawl Tool

The `firecrawl_scrape` tool is provided by the official `golemcore/firecrawl`
plugin.

Configuration lives in `preferences/plugins/golemcore/firecrawl.json`:

```json
{
  "enabled": true,
  "apiKey": "fc-...",
  "defaultFormat": "markdown",
  "onlyMainContent": true,
  "maxAgeMs": 172800000,
  "timeoutMs": 30000
}
```

### Perplexity Sonar Tool

The `perplexity_ask` tool is provided by the official
`golemcore/perplexity-sonar` plugin and currently uses synchronous completions.

Configuration lives in `preferences/plugins/golemcore/perplexity-sonar.json`:

```json
{
  "enabled": true,
  "apiKey": "pplx-...",
  "defaultModel": "sonar",
  "defaultSearchMode": "web",
  "returnRelatedQuestions": false,
  "returnImages": false
}
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
    "keepLastMessages": 20,
    "preserveTurnBoundaries": true,
    "detailsEnabled": true,
    "detailsMaxItemsPerCategory": 50,
    "summaryTimeoutMs": 15000
  }
}
```

Field notes:

1. `preserveTurnBoundaries`: keeps compaction split-safe so turns are not truncated mid exchange.
2. `detailsEnabled`: persists structured compaction diagnostics in session metadata and summary metadata.
3. `detailsMaxItemsPerCategory`: caps stored file/tool detail lists.
4. `summaryTimeoutMs`: hard timeout for the LLM summarization phase.

### Turn Budget

Limits for a single user request (one internal tool loop run):

```json
{
  "turn": {
    "maxLlmCalls": 200,
    "maxToolExecutions": 500,
    "deadline": "PT1H",
    "autoRetryEnabled": true,
    "autoRetryMaxAttempts": 2,
    "autoRetryBaseDelayMs": 600,
    "queueSteeringEnabled": true,
    "queueSteeringMode": "one-at-a-time",
    "queueFollowUpMode": "one-at-a-time"
  }
}
```

Field notes:

1. `autoRetry*`: resilient retries for transient LLM/tool-loop failures.
2. `queueSteeringEnabled`: allows steering messages to bypass normal follow-up handling.
3. `queueSteeringMode`: `one-at-a-time` or `all`.
4. `queueFollowUpMode`: `one-at-a-time` or `all`.

### Memory (V2)

Memory behavior is configured under `memory`:

```json
{
  "memory": {
    "enabled": true,
    "softPromptBudgetTokens": 1800,
    "maxPromptBudgetTokens": 3500,
    "workingTopK": 6,
    "episodicTopK": 8,
    "semanticTopK": 6,
    "proceduralTopK": 4,
    "promotionEnabled": true,
    "promotionMinConfidence": 0.75,
    "decayEnabled": true,
    "decayDays": 30,
    "retrievalLookbackDays": 21,
    "codeAwareExtractionEnabled": true
  }
}
```

Field notes:

1. `softPromptBudgetTokens` / `maxPromptBudgetTokens`: memory injection budget for prompt packing.
2. `*TopK`: per-layer candidate limits for retrieval.
3. `promotion*`: controls promotion from episodic records into semantic/procedural stores.
4. `decay*`: stale item pruning window for stored memory.
5. `retrievalLookbackDays`: episodic retrieval window (how many recent day-files are read per request).

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
    "telegramRespondWithVoice": false,
    "telegramTranscribeIncoming": false,
    "sttProvider": "golemcore/elevenlabs",
    "ttsProvider": "golemcore/elevenlabs"
  }
}
```

Notes:

- The `voice` section now primarily routes STT/TTS providers and Telegram voice behavior.
- Provider-specific secrets and endpoints live in plugin settings, for example:
  - `preferences/plugins/golemcore/elevenlabs.json`
  - `preferences/plugins/golemcore/whisper.json`
- The dashboard resolves available providers from `/api/plugins/voice/providers`.

### Auto Mode

```json
{
  "autoMode": {
    "enabled": false,
    "tickIntervalSeconds": 1,
    "taskTimeLimitMinutes": 10,
    "autoStart": true,
    "maxGoals": 3,
    "modelTier": "default",
    "notifyMilestones": true
  }
}
```

Notes:

- Auto mode is schedule-driven; goals/tasks are only executed automatically when a cron schedule exists.
- `tickIntervalSeconds` remains in runtime config, but the backend currently polls due schedules every second.
- Schedules themselves are stored separately in `auto/schedules.json`.

### RAG

RAG is configured through the official `golemcore/lightrag` plugin rather than
the core runtime schema.

```json
{
  "enabled": false,
  "url": "http://localhost:9621",
  "apiKey": "",
  "queryMode": "hybrid",
  "timeoutSeconds": 10,
  "indexMinLength": 50
}
```

Store this in `preferences/plugins/golemcore/lightrag.json` or configure it
from the dashboard plugin settings UI.

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

On first run, the bot copies a bundled `models.json` into the workspace. The dashboard `Model Catalog` can edit, reload, and enrich this file with live provider discovery.

Important behavior:

- model ids may be plain (`gpt-5.1`) or provider-scoped (`openai/gpt-5.1`)
- provider-scoped ids are useful when the same raw id exists under multiple provider profiles
- `/api/models/discover/{provider}` reads live models from the configured provider API and helps seed catalog entries

See: [Model Routing Guide](MODEL_ROUTING.md)

## Spring Properties (bot.*)

Some settings are still controlled via Spring properties (application config), typically using env vars in Docker:

- Workspace paths: `STORAGE_PATH`, `TOOLS_WORKSPACE`
- Dashboard toggle: `DASHBOARD_ENABLED`
- Plugin runtime: `BOT_PLUGINS_ENABLED`, `BOT_PLUGINS_DIRECTORY`, `BOT_PLUGINS_AUTO_START`, `BOT_PLUGINS_AUTO_RELOAD`, `BOT_PLUGINS_POLL_INTERVAL`
- Skills marketplace source defaults: `BOT_SKILLS_MARKETPLACE_REPOSITORY_DIRECTORY`, `BOT_SKILLS_MARKETPLACE_REPOSITORY_URL`, `BOT_SKILLS_MARKETPLACE_BRANCH`
- Skills marketplace HTTP fallback: `BOT_SKILLS_MARKETPLACE_API_BASE_URL`, `BOT_SKILLS_MARKETPLACE_RAW_BASE_URL`, `BOT_SKILLS_MARKETPLACE_REMOTE_CACHE_TTL`
- ClawHub integration: `BOT_SKILLS_CLAWHUB_ENABLED`, `BOT_SKILLS_CLAWHUB_BASE_URL`
- Plugin marketplace source: `BOT_PLUGINS_MARKETPLACE_REPOSITORY_DIRECTORY`, `BOT_PLUGINS_MARKETPLACE_REPOSITORY_URL`, `BOT_PLUGINS_MARKETPLACE_BRANCH`
- Plugin marketplace HTTP fallback: `BOT_PLUGINS_MARKETPLACE_API_BASE_URL`, `BOT_PLUGINS_MARKETPLACE_RAW_BASE_URL`, `BOT_PLUGINS_MARKETPLACE_REMOTE_CACHE_TTL`
- Self-update controls: `BOT_UPDATE_ENABLED`, `UPDATE_PATH`, `BOT_UPDATE_MAX_KEPT_VERSIONS`, `BOT_UPDATE_CHECK_INTERVAL`
- Allowed providers in model picker: `BOT_MODEL_SELECTION_ALLOWED_PROVIDERS`
- Tool result truncation: `bot.auto-compact.max-tool-result-chars`
- Plan mode feature flag: `bot.plan.enabled`

### Plugin Runtime and Marketplace

The plugin runtime and marketplace are controlled by `bot.plugins.*`.

Important properties:

- `bot.plugins.enabled` / `BOT_PLUGINS_ENABLED`
- `bot.plugins.directory` / `BOT_PLUGINS_DIRECTORY`
- `bot.plugins.auto-start` / `BOT_PLUGINS_AUTO_START`
- `bot.plugins.auto-reload` / `BOT_PLUGINS_AUTO_RELOAD`
- `bot.plugins.poll-interval` / `BOT_PLUGINS_POLL_INTERVAL`
- `bot.plugins.marketplace.repository-directory` / `BOT_PLUGINS_MARKETPLACE_REPOSITORY_DIRECTORY`
- `bot.plugins.marketplace.repository-url` / `BOT_PLUGINS_MARKETPLACE_REPOSITORY_URL`
- `bot.plugins.marketplace.branch` / `BOT_PLUGINS_MARKETPLACE_BRANCH`
- `bot.plugins.marketplace.api-base-url` / `BOT_PLUGINS_MARKETPLACE_API_BASE_URL`
- `bot.plugins.marketplace.raw-base-url` / `BOT_PLUGINS_MARKETPLACE_RAW_BASE_URL`
- `bot.plugins.marketplace.remote-cache-ttl` / `BOT_PLUGINS_MARKETPLACE_REMOTE_CACHE_TTL`

Behavior:

- If `repository-directory` is configured and present, marketplace metadata/artifacts are read from that repository checkout.
- Otherwise the backend falls back to the configured remote repository and GitHub HTTP sources.
- Installed marketplace artifacts are written into the plugin runtime directory and then reloaded into the running bot.

### Skills Runtime and Marketplace

Core skill loading is split between:

- workspace storage under `skills/`
- runtime config under `skills`
- marketplace source defaults under `bot.skills.marketplace.*`

Important properties:

- `bot.skills.marketplace-enabled` / `BOT_SKILLS_MARKETPLACE_ENABLED`
- `bot.skills.marketplace-repository-directory` / `BOT_SKILLS_MARKETPLACE_REPOSITORY_DIRECTORY`
- `bot.skills.marketplace-repository-url` / `BOT_SKILLS_MARKETPLACE_REPOSITORY_URL`
- `bot.skills.marketplace-branch` / `BOT_SKILLS_MARKETPLACE_BRANCH`
- `bot.skills.marketplace-api-base-url` / `BOT_SKILLS_MARKETPLACE_API_BASE_URL`
- `bot.skills.marketplace-raw-base-url` / `BOT_SKILLS_MARKETPLACE_RAW_BASE_URL`
- `bot.skills.marketplace-remote-cache-ttl` / `BOT_SKILLS_MARKETPLACE_REMOTE_CACHE_TTL`
- `bot.skills.claw-hub-enabled` / `BOT_SKILLS_CLAWHUB_ENABLED`
- `bot.skills.claw-hub-base-url` / `BOT_SKILLS_CLAWHUB_BASE_URL`

Runtime config fields exposed in the dashboard:

```json
{
  "skills": {
    "enabled": true,
    "progressiveLoading": true,
    "marketplaceSourceType": "repository",
    "marketplaceRepositoryDirectory": null,
    "marketplaceRepositoryUrl": "https://github.com/alexk-dev/golemcore-skills",
    "marketplaceBranch": "main"
  }
}
```

Behavior:

- The dashboard `Skills -> Marketplace` page can switch the source between `repository` and `directory`.
- Runtime config values take precedence over Spring defaults when present.
- If source type is `directory`, the configured path may point either to the repository root or directly to its `registry/` directory.
- If source type is `directory` and the configured path points at a valid `golemcore-skills` checkout, metadata is loaded from disk.
- Otherwise the backend reads the configured remote repository through the GitHub tree/raw endpoints.
- Remote repository mode currently supports GitHub-style repository URLs such as `https://github.com/alexk-dev/golemcore-skills`.
- Installed skill artifacts are written into `skills/marketplace/<maintainer>/<artifact>/...` and then the skill registry is reloaded.
- Standalone marketplace artifacts install one runtime skill; pack artifacts install multiple runtime skills with namespaced runtime ids.
- The registry format is manifest-driven: `registry/<maintainer>/maintainer.yaml` plus `registry/<maintainer>/<artifact>/artifact.yaml`.
- Public ClawHub installs use a separate distribution path under `skills/clawhub/<slug>/...` and rewrite runtime ids to `clawhub/<slug>`.

### Self-Update (Core)

Core self-update is controlled by Spring properties under `bot.update.*`.

- `bot.update.enabled` defaults to `true` (disable with `BOT_UPDATE_ENABLED=false`).
- Release repository is fixed in code: `alexk-dev/golemcore-bot`.
- GitHub token is not required (public repository).
- Release asset glob is fixed in code: `bot-*.jar`.

Configurable properties:

- `bot.update.updates-path` (`UPDATE_PATH`, default `${STORAGE_PATH}/updates`; if `STORAGE_PATH` is unset, it follows `bot.storage.local.base-path`)
- `bot.update.max-kept-versions` (`BOT_UPDATE_MAX_KEPT_VERSIONS`, default `3`)
- `bot.update.check-interval` (`BOT_UPDATE_CHECK_INTERVAL`, default `PT1H`)

## Storage Layout

Default (macOS/Linux): `~/.golemcore/workspace`

```
workspace/
├── auto/                    # auto mode + plan mode state
├── memory/                  # structured memory items (JSONL)
├── models/                  # models.json (capabilities)
├── preferences/             # settings.json, runtime-config.json, admin.json
├── sessions/                # conversation sessions
├── skills/                  # manual skills + marketplace-installed artifacts
└── usage/                   # usage logs
```

Example skills layout:

```text
workspace/skills/
├── greeting/
│   └── SKILL.md
└── marketplace/
    └── golemcore/
        └── devops-pack/
            ├── .marketplace-install.json
            └── skills/
                ├── deploy-review/SKILL.md
                └── incident-triage/SKILL.md
```

## Diagnostics

- Health: `GET /api/system/health`
- Config flags: `GET /api/system/config`
- Diagnostics: `GET /api/system/diagnostics`
