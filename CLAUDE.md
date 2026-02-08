# CLAUDE.md — GolemCore Bot

## What is this project?

Extensible AI assistant built with **Spring Boot 3.4.2** (Java 17). Processes messages through an ordered pipeline of specialized systems. Currently supports Telegram as the primary channel and OpenAI/Anthropic as LLM providers via langchain4j.

## Quick Start

```bash
# Build
./mvnw clean package -DskipTests

# Run tests (255 tests)
./mvnw test

# Run (requires env vars)
OPENAI_API_KEY=sk-... TELEGRAM_BOT_TOKEN=... TELEGRAM_ENABLED=true ./mvnw spring-boot:run
```

**Required env vars:** `OPENAI_API_KEY` (or `ANTHROPIC_API_KEY`). Optional: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_ENABLED`, `TELEGRAM_ALLOWED_USERS`.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                       Input Layer                           │
│  TelegramAdapter │ CommandRouter                              │
├─────────────────────────────────────────────────────────────┤
│                    Message Processing                       │
│  ChannelPort │ CommandPort                                   │
├─────────────────────────────────────────────────────────────┤
│                      Core Logic                             │
│  AgentLoop → Systems Pipeline → Components → Services       │
├─────────────────────────────────────────────────────────────┤
│                    Service Interfaces                       │
│  LlmPort │ StoragePort │ EmbeddingPort │ BrowserPort │ VoicePort │
├─────────────────────────────────────────────────────────────┤
│                   Service Implementations                   │
│  Langchain4jAdapter │ LocalStorageAdapter │ PlaywrightAdapter │
└─────────────────────────────────────────────────────────────┘
```

## Package Structure

```
me.golemcore.bot
├── adapter/
│   ├── inbound/
│   │   ├── command/        CommandRouter (slash commands: /skills, /tools, /status, /compact, /help, /new)
│   │   └── telegram/       TelegramAdapter, TelegramConfig, TelegramHtmlFormatter
│   └── outbound/
│       ├── browser/        PlaywrightAdapter (headless browser)
│       ├── embedding/      Langchain4jEmbeddingAdapter
│       ├── llm/            Langchain4jAdapter, CustomLlmAdapter, NoOpLlmAdapter, LlmAdapterFactory
│       ├── mcp/            McpClient, McpClientManager, McpToolAdapter (MCP over stdio)
│       └── storage/        LocalStorageAdapter (filesystem storage)
├── domain/
│   ├── component/          Components: SkillComponent, ToolComponent, MemoryComponent, LlmComponent, etc.
│   ├── loop/               AgentLoop, AgentLoopConfig
│   ├── model/              AgentContext, AgentSession, Message, Skill, McpConfig, ToolDefinition, LlmRequest/Response, etc.
│   ├── service/            SessionService, SkillService, MemoryService, CompactionService, etc.
│   └── system/             Systems (ordered pipeline) — see "Agent Loop Pipeline" below
├── infrastructure/
│   ├── config/             BotProperties, AutoConfiguration, ModelConfigService
│   ├── event/              SpringEventBus
│   ├── http/               FeignClientFactory, OkHttpConfig
│   └── i18n/               MessageService (en, ru)
├── port/
│   ├── inbound/            ChannelPort, CommandPort
│   └── outbound/           LlmPort, StoragePort, EmbeddingPort, BrowserPort, VoicePort
├── ratelimit/              TokenBucketRateLimiter (per-user, per-channel, per-LLM)
├── routing/                HybridSkillMatcher, LlmSkillClassifier, SkillEmbeddingStore, MessageContextAggregator
├── security/               InjectionGuard, InputSanitizer, AllowlistValidator, ContentPolicy
├── tools/                  FileSystemTool, ShellTool, SkillManagementTool, DateTimeTool, BrowserTool, WeatherTool, EchoTool
├── usage/                  LlmUsageTracker (token/cost tracking, JSONL files)
└── voice/                  WhisperSttAdapter, JaffreeVoiceProcessor, TelegramVoiceHandler
```

## Agent Loop Pipeline

The core message processing is an **ordered pipeline of specialized systems**. Each system implements `AgentSystem` with `process(AgentContext)` and runs in order:

| Order | System                    | Purpose |
|-------|--------------------------|---------|
| 10    | `InputSanitizationSystem` | HTML sanitization, input length check, injection detection |
| 15    | `SkillRoutingSystem`      | Fragmented input aggregation → semantic search → LLM classifier → sets activeSkill & modelTier |
| 20    | `ContextBuildingSystem`   | Assembles system prompt from memory, active skill content (or skills summary), available tools; starts MCP servers and registers MCP tools |
| 30    | `LlmExecutionSystem`     | Selects model by tier (fast/default/smart/coding), calls LLM, tracks usage |
| 40    | `ToolExecutionSystem`     | Executes tool calls from LLM, adds tool results to history, loops back to LLM |
| 50    | `MemoryPersistSystem`     | Persists memory after processing |
| 60    | `ResponseRoutingSystem`   | Sends final response back to the channel |

The loop iterates (max `bot.agent.max-iterations=20`) while the LLM requests tool calls.

## Key Design Patterns

### Component Design

**All beans always exist.** Runtime checks via `isEnabled()` instead of `@ConditionalOnProperty`. This avoids circular dependency issues and simplifies dependency injection.

### LLM Adapter Factory

`LlmAdapterFactory` selects the active LLM adapter based on `bot.llm.provider` config:
- `langchain4j` → `Langchain4jAdapter` (OpenAI, Anthropic)
- `custom` → `CustomLlmAdapter` (OpenAI-compatible endpoints)
- fallback → `NoOpLlmAdapter`

All adapters implement `LlmProviderAdapter` interface.

### Model Routing by Tier

The skill routing system assigns a `modelTier` to each request:
- **fast** — greetings, simple Q&A (gpt-5.1, reasoning=low)
- **default** — general questions (gpt-5.1, reasoning=medium)
- **smart** — complex analysis (gpt-5.1, reasoning=high)
- **coding** — code generation (gpt-5.2, reasoning=medium)

Configured in `application.properties` under `bot.router.*-model`.

### Hybrid Skill Routing (2-stage)

1. **Semantic pre-filter** (~5ms) — embeddings via `EmbeddingPort`, cosine similarity, top-K candidates
2. **LLM classifier** (~200-400ms) — fast model picks the best skill from candidates
3. Skip classifier if semantic score > 0.95

Preceded by **Stage 0: Fragmented Input Detection** (`MessageContextAggregator`) — detects split messages using signals (too_short, back_reference, time_window, etc.). Aggregates fragments for better matching.

### Function Calling

`Langchain4jAdapter` converts `ToolDefinition` → langchain4j `ToolSpecification`. Tool calls from LLM are parsed, executed by `ToolExecutionSystem`, and results added to conversation history as `role=tool` messages with `toolCallId` and `toolName`.

### MCP Client (Model Context Protocol)

Lightweight MCP client over stdio — no external MCP SDK, uses Jackson + ProcessBuilder for JSON-RPC 2.0.

**Components:**
- `McpClient` — manages a single MCP server process: start → initialize → tools/list → tools/call → close. Reader thread matches JSON-RPC responses by id to pending `CompletableFuture`s. Stderr drained to DEBUG log.
- `McpClientManager` (`@Component`) — pool of `McpClient` instances keyed by skill name. Lazy startup via `getOrStartClient(Skill)`, idle timeout cleanup (scheduled every 60s), `@PreDestroy` shutdown.
- `McpToolAdapter` (NOT a Spring bean) — wraps a single MCP tool as `ToolComponent`. Created dynamically when MCP server starts. Delegates `execute()` to `McpClient.callTool()`.

**Integration flow:**
1. `SkillService` parses `mcp:` section from YAML frontmatter into `McpConfig` on skill load
2. `ContextBuildingSystem` checks `activeSkill.hasMcp()` → calls `mcpClientManager.getOrStartClient(skill)`
3. For each MCP tool definition, creates `McpToolAdapter` → registers in `ToolExecutionSystem`
4. LLM sees MCP tools alongside native tools and can call them normally
5. `ToolExecutionSystem.unregisterTools()` cleans up when MCP client stops

**Skill frontmatter:**
```yaml
mcp:
  command: npx -y @modelcontextprotocol/server-github
  env:
    GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}
  startup_timeout: 30   # seconds (default: 30)
  idle_timeout: 10       # minutes (default: 5)
```

`${VAR}` placeholders in `env` are resolved from skill's `resolvedVariables` first, then `System.getenv()`.

**Config:** `bot.mcp.enabled`, `bot.mcp.default-startup-timeout`, `bot.mcp.default-idle-timeout`

## Storage

**Local filesystem only** (S3 was removed). `StoragePort` interface uses "directory" and "path" terminology.

- Adapter: `LocalStorageAdapter` (`@Primary`)
- Base path: `${user.home}/.golemcore/workspace` (configurable via `bot.storage.local.base-path`)
- Directories: `sessions/`, `memory/`, `skills/`, `usage/`, `preferences/`

## Tools

All tools implement `ToolComponent` with `getDefinition()` (JSON Schema), `execute(params)`, `isEnabled()`.

| Tool | Description | Sandbox |
|------|-------------|---------|
| `FileSystemTool` | read/write/list/mkdir/delete files | `~/.golemcore/sandbox` |
| `ShellTool` | execute shell commands (timeout 30s, max 300s) | `~/.golemcore/sandbox` |
| `SkillManagementTool` | create/list/get/delete skills via LLM | skills workspace |
| `DateTimeTool` | current time, timezone conversion, date math | — |
| `BrowserTool` | headless browser via Playwright | — |
| `BraveSearchTool` | web search via Brave Search API | — |
| `GoalManagementTool` | create/list goals, plan tasks, write diary | — |
| `SkillTransitionTool` | explicit skill-to-skill transitions | — |
| `WeatherTool` | weather data via Open-Meteo (free, no API key) | — |

**Security:** Path traversal blocked by `InjectionGuard`. Shell commands filtered against blocklist (rm -rf /, sudo su, etc.).

## Slash Commands

Handled by `CommandRouter` (`CommandPort`). Telegram routes commands before the AgentLoop.

| Command | Description |
|---------|-------------|
| `/skills` | List available skills |
| `/tools` | List enabled tools |
| `/status` | Session info + 24h usage stats |
| `/new`, `/reset` | Start new conversation |
| `/compact [N]` | Summarize old messages via LLM, keep last N (default 10) |
| `/help` | Show available commands |
| `/settings` | Language selection (special-cased in TelegramAdapter with inline keyboards) |

## Skill System

Skills are stored as `SKILL.md` files with YAML frontmatter:

```markdown
---
name: greeting
description: Handle greetings
requires:
  env: [OPENAI_API_KEY]
vars:
  api_key: { source: env, env_var: OPENAI_API_KEY, secret: true }
---
You are a friendly greeter...
```

- Loaded from `${workspace}/skills/*/SKILL.md`
- Progressive loading: only summary shown unless selected by routing
- Variables resolved via `SkillVariableResolver`
- Requirements checked (env vars, binaries)
- Dynamic creation via `SkillManagementTool`
- Optional `mcp:` section to declare an MCP server (see "MCP Client" above)

## Conversation Compaction

`CompactionService` uses a fast LLM to summarize old messages. `SessionService.compactWithSummary()` replaces old messages with a `[Conversation summary]` system message + last N messages. Falls back to simple truncation if LLM is unavailable.

## Security Layers

1. **AllowlistValidator** — user allow/blocklist for Telegram
2. **InputSanitizer** — OWASP HTML sanitizer, max input length
3. **InjectionGuard** — path traversal, command injection, prompt injection detection
4. **ContentPolicy** — content filtering rules
5. **Tool sandboxing** — FileSystem and Shell restricted to `~/.golemcore/sandbox`

## Rate Limiting

`TokenBucketRateLimiter` with three tiers:
- Per user: 20/min, 100/hr, 500/day
- Per channel: 30 msg/sec
- Per LLM provider: 60 req/min

## i18n

`MessageService` wraps Spring's `MessageSource`. Two locale files:
- `src/main/resources/messages_en.properties`
- `src/main/resources/messages_ru.properties`

User language stored in `UserPreferences` (persisted to storage).

## Configuration

All config in `src/main/resources/application.properties` under `bot.*` prefix. Key sections:

- `bot.agent.*` — loop settings (max iterations)
- `bot.llm.*` — LLM provider config (langchain4j/custom, API keys)
- `bot.channels.telegram.*` — Telegram token, allowed users
- `bot.storage.*` — local workspace path, directory names
- `bot.router.*` — model tiers, skill matcher settings
- `bot.tools.*` — enable/disable tools, sandbox paths
- `bot.mcp.*` — MCP client settings (enabled, startup/idle timeouts)
- `bot.security.*` — sanitization, injection detection, allowlist
- `bot.rate-limit.*` — per-user/channel/LLM limits
- `bot.voice.*` — STT/TTS settings

Model definitions live in `models.json` in working directory.

## Testing

**255 tests**, all unit tests, run with `./mvnw test`.

- Tests in `src/test/java/` mirror the main source structure
- `LlmPort` mocked in classifier tests (use custom `Answer` on mock creation for varargs)
- Tool tests verify sandbox restrictions, injection blocking
- Security tests cover injection patterns, sanitization
- MCP tests cover JSON-RPC serialization, tool parsing, client lifecycle, config parsing from YAML

## Key Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Spring Boot | 3.4.2 | Framework |
| LangChain4j | 1.0.0-beta1 | LLM/embedding integration (OpenAI, Anthropic) |
| Telegram Bots | 8.2.0 | Long-polling Telegram API |
| Playwright | 1.49.0 | Headless browser |
| Jackson YAML | — | Skill frontmatter parsing |
| OWASP Sanitizer | 20240325.1 | HTML sanitization |
| Feign + OkHttp | 13.5 / 4.12.0 | HTTP client |
| Jaffree | 2023.09.10 | FFmpeg wrapper for voice |

## Common Tasks

### Adding a new tool

1. Create class in `tools/` implementing `ToolComponent`
2. Implement `getDefinition()` with JSON Schema, `execute()`, `isEnabled()`
3. Add config flag `bot.tools.<name>.enabled` in `application.properties`
4. The tool auto-registers via Spring component scanning

### Adding a new LLM provider

1. Implement `LlmProviderAdapter` in `adapter/outbound/llm/`
2. Register in `LlmAdapterFactory`
3. Add config under `bot.llm.<provider>.*`

### Adding a new channel

1. Implement `ChannelPort` in `adapter/inbound/`
2. Wire to `AgentLoop.processMessage()` for message handling
3. Add config under `bot.channels.<name>.*`

### Adding a new processing system

1. Implement `AgentSystem` in `domain/system/`
2. Set `@Order(N)` annotation to control pipeline position
3. Implement `process(AgentContext)`, `shouldProcess()`, `isEnabled()`

## License & Contributing

This project is licensed under **Apache License 2.0**. See `LICENSE` and `NOTICE` files.

### Why Apache 2.0?

- **Patent protection** — Section 3 grants explicit patent license from contributors
- **Enterprise-friendly** — widely accepted by corporate legal teams
- **Trademark protection** — Section 6 protects project name from misuse
- **Contributor protection** — Section 8 shields all contributors from liability
- **Compatible with dependencies** — 80% of project dependencies use Apache 2.0

### Contributing

By submitting a contribution, you:
- Grant a copyright license (Section 2) for your code
- Grant a patent license (Section 3) for any patents in your contribution
- Agree that your contribution is under Apache 2.0 terms
- Waive additional terms or conditions

If you submit code containing patented technology and later sue the project for patent infringement, your patent license automatically terminates (defensive termination clause).
