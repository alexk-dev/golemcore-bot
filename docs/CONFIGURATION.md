# Configuration Guide

Comprehensive guide to configuring GolemCore Bot.

## Configuration Methods

### 1. Environment Variables (Recommended for Docker)

```bash
# Docker
docker run \
  -e OPENAI_API_KEY=sk-proj-... \
  -e BOT_ROUTER_DEFAULT_MODEL=openai/gpt-5.1 \
  golemcore-bot:latest

# JAR
export OPENAI_API_KEY=sk-proj-...
export BOT_ROUTER_DEFAULT_MODEL=openai/gpt-5.1
java -jar golemcore-bot.jar
```

### 2. Docker Compose (Recommended for Production)

```yaml
version: '3.8'
services:
  golemcore-bot:
    image: golemcore-bot:latest
    environment:
      OPENAI_API_KEY: ${OPENAI_API_KEY}
      BOT_ROUTER_DEFAULT_MODEL: openai/gpt-5.1
```

### 3. Application Properties (Build-Time)

Edit `src/main/resources/application.properties`:

```properties
bot.router.default-model=openai/gpt-5.1
```

Then rebuild image: `./mvnw compile jib:dockerBuild`

**Priority:** Environment variables > application.properties (command line args only for JAR mode)

---

## Model Configuration

> **Deep dive:** See [Model Routing Guide](MODEL_ROUTING.md) for the full end-to-end flow, dynamic tier upgrades, classifier internals, and debugging tips.

### Model Tiers

The bot selects models based on task complexity:

```properties
# Balanced tier (greetings, general questions, summarization — default/fallback)
BOT_ROUTER_DEFAULT_MODEL=openai/gpt-5.1
BOT_ROUTER_DEFAULT_MODEL_REASONING=medium

# Smart tier (complex analysis, architecture decisions)
BOT_ROUTER_SMART_MODEL=openai/gpt-5.1
BOT_ROUTER_SMART_MODEL_REASONING=high

# Coding tier (code generation, debugging, refactoring)
BOT_ROUTER_CODING_MODEL=openai/gpt-5.2
BOT_ROUTER_CODING_MODEL_REASONING=medium

# Deep tier (PhD-level reasoning: proofs, scientific analysis)
BOT_ROUTER_DEEP_MODEL=openai/gpt-5.2
BOT_ROUTER_DEEP_MODEL_REASONING=xhigh
```

### Dynamic Tier Escalation

Automatically upgrade to coding tier when code activity detected:

```properties
BOT_ROUTER_DYNAMIC_TIER_ENABLED=true
```

**Triggers:**
- File operations on `.py`, `.js`, `.java`, `.go` files
- Shell commands: `python`, `node`, `mvn`, `cargo`
- Stack traces in tool results

### Multi-Provider Setup

Mix different LLM providers across tiers for cost optimization or feature access:

**Docker:**
```bash
docker run -d \
  -e OPENAI_API_KEY=sk-proj-... \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  -e BOT_ROUTER_DEFAULT_MODEL=openai/gpt-5.1 \
  -e BOT_ROUTER_SMART_MODEL=anthropic/claude-opus-4-6 \
  -e BOT_ROUTER_CODING_MODEL=openai/gpt-5.2 \
  -e BOT_ROUTER_DEEP_MODEL=openai/gpt-5.2 \
  golemcore-bot:latest
```

**Docker Compose:**
```yaml
services:
  golemcore-bot:
    image: golemcore-bot:latest
    environment:
      OPENAI_API_KEY: ${OPENAI_API_KEY}
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
      BOT_ROUTER_DEFAULT_MODEL: openai/gpt-5.1
      BOT_ROUTER_SMART_MODEL: anthropic/claude-opus-4-6
      BOT_ROUTER_CODING_MODEL: openai/gpt-5.2
      BOT_ROUTER_DEEP_MODEL: openai/gpt-5.2
```

### Custom Endpoints

```bash
export BOT_LLM_PROVIDER=custom
export CUSTOM_LLM_API_URL=https://your-llm.com/v1
export CUSTOM_LLM_API_KEY=your-key
```

Must be OpenAI-compatible API.

---

## Skill Routing

> **Deep dive:** See [Model Routing Guide](MODEL_ROUTING.md#how-tier-assignment-works) for the 3-stage routing pipeline, fragmented input detection, and classifier details.

### Basic Routing (Default)

Uses first available skill or general chat.

### Hybrid Routing (Advanced)

2-stage semantic + LLM classifier:

```bash
export SKILL_MATCHER_ENABLED=true

# Semantic search
export BOT_ROUTER_SKILL_MATCHER_EMBEDDING_MODEL=text-embedding-3-small
export BOT_ROUTER_SKILL_MATCHER_SEMANTIC_SEARCH_TOP_K=5
export BOT_ROUTER_SKILL_MATCHER_SEMANTIC_SEARCH_MIN_SCORE=0.6

# LLM classifier
export BOT_ROUTER_SKILL_MATCHER_CLASSIFIER_MODEL=openai/gpt-5-mini
export BOT_ROUTER_SKILL_MATCHER_SKIP_CLASSIFIER_THRESHOLD=0.95

# Cache
export BOT_ROUTER_SKILL_MATCHER_CACHE_TTL_MINUTES=60
```

Cached results are near-instant. The classifier is skipped for high-confidence semantic matches (score > 0.95).

---

## Storage

### Local Workspace

```bash
export STORAGE_PATH=/path/to/workspace  # default: ~/.golemcore/workspace
```

**Structure:**
```
workspace/
├── sessions/     # Conversation history
├── memory/       # Long-term memory
├── skills/       # Custom skills
├── usage/        # LLM usage logs
└── preferences/  # User settings
```

### Sandbox (Tools)

```bash
export TOOLS_WORKSPACE=/path/to/sandbox  # default: ~/.golemcore/sandbox
```

**FileSystem** and **Shell** tools operate only within this directory.

---

## Security

### Input Sanitization

```bash
export BOT_SECURITY_SANITIZE_INPUT=true
export BOT_SECURITY_MAX_INPUT_LENGTH=10000
export BOT_SECURITY_DETECT_PROMPT_INJECTION=true
export BOT_SECURITY_DETECT_COMMAND_INJECTION=true
```

### User Allowlist (Telegram)

```bash
export TELEGRAM_ALLOWED_USERS=123456789,987654321
```

Only these Telegram user IDs can use the bot. The allowlist is enforced automatically when set.

### Tool Confirmation

```bash
export TOOL_CONFIRMATION_ENABLED=true
export TOOL_CONFIRMATION_TIMEOUT=60  # seconds
```

User must approve destructive operations (file delete, risky shell commands).

---

## Rate Limiting

### Request Limits

```bash
export BOT_RATE_LIMIT_USER_REQUESTS_PER_MINUTE=20
export BOT_RATE_LIMIT_USER_REQUESTS_PER_HOUR=100
export BOT_RATE_LIMIT_USER_REQUESTS_PER_DAY=500
```

### Per-Channel Limits

```bash
export BOT_RATE_LIMIT_CHANNEL_MESSAGES_PER_SECOND=30
```

### Per-LLM Limits

```bash
export BOT_RATE_LIMIT_LLM_REQUESTS_PER_MINUTE=60
```

---

## Auto-Compaction

Prevents context overflow:

```bash
export BOT_AUTO_COMPACT_ENABLED=true
export BOT_AUTO_COMPACT_MAX_CONTEXT_TOKENS=50000
export BOT_AUTO_COMPACT_KEEP_LAST_MESSAGES=20
export BOT_AUTO_COMPACT_SYSTEM_PROMPT_OVERHEAD_TOKENS=8000
export BOT_AUTO_COMPACT_CHARS_PER_TOKEN=3.5
export BOT_AUTO_COMPACT_MAX_TOOL_RESULT_CHARS=100000
```

**How it works:**
1. Estimates tokens: `(chars / 3.5) + 8000`
2. If exceeds `MAX_CONTEXT_TOKENS`, summarizes old messages
3. Keeps last N messages intact

---

## MCP (Model Context Protocol)

```bash
export MCP_ENABLED=true
export BOT_MCP_DEFAULT_STARTUP_TIMEOUT=30   # seconds
export BOT_MCP_DEFAULT_IDLE_TIMEOUT=5       # minutes
```

**Per-skill configuration in SKILL.md:**

```yaml
mcp:
  command: npx -y @modelcontextprotocol/server-github
  env:
    GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}
  startup_timeout: 30
  idle_timeout: 10
```

---

## RAG (Long-term Memory)

> **Deep dive:** See [RAG Guide](RAG.md) for the indexing/retrieval pipeline, query modes, LightRAG server configuration, and how RAG complements short-term memory.

Requires LightRAG Docker container:

```bash
docker run -d -p 9621:9621 \
  -v lightrag-data:/app/data \
  lightrag/lightrag:latest
```

Configure bot:

```bash
export RAG_ENABLED=true
export RAG_URL=http://localhost:9621
export RAG_API_KEY=your-api-key  # optional
export BOT_RAG_QUERY_MODE=hybrid  # or local/global
export BOT_RAG_MAX_CONTEXT_TOKENS=2000
export BOT_RAG_TIMEOUT_SECONDS=10
export BOT_RAG_INDEX_MIN_LENGTH=50
```

**Query modes:**
- `local` — Entity-centric search
- `global` — Community-summary search
- `hybrid` — Both combined (recommended)

---

## Auto Mode

> **Deep dive:** See [Auto Mode Guide](AUTO_MODE.md) for the tick cycle, goal/task lifecycle, diary format, system prompt injection, and pipeline integration.

Autonomous goal execution:

```bash
export AUTO_MODE_ENABLED=true
export AUTO_MODE_INTERVAL=15  # minutes
export BOT_AUTO_MAX_GOALS=3
export BOT_AUTO_MODEL_TIER=default
export BOT_AUTO_NOTIFY_MILESTONES=true
```

**Usage:**
```
/auto on
/goal "Deploy bot to production"
```

Bot works autonomously every 15 minutes.

---

## Voice (ElevenLabs STT + TTS)

ElevenLabs provides both speech-to-text and text-to-speech. No FFmpeg or external tools needed.

```bash
export VOICE_ENABLED=true
export ELEVENLABS_API_KEY=your-key-here
export ELEVENLABS_VOICE_ID=21m00Tcm4TlvDq8ikWAM        # Default voice
export ELEVENLABS_TTS_MODEL=eleven_multilingual_v2       # TTS model (29+ languages)
export ELEVENLABS_STT_MODEL=scribe_v1                    # STT model
export ELEVENLABS_SPEED=1.0                              # Speech speed
```

### Telegram Voice

```bash
export BOT_VOICE_TELEGRAM_RESPOND_WITH_VOICE=true   # Auto-reply with voice to voice messages
export BOT_VOICE_TELEGRAM_TRANSCRIBE_INCOMING=true   # Transcribe incoming voice messages
```

### Voice Response Mechanisms

**Primary (voice prefix):** LLM starts response with a special prefix — `ResponseRoutingSystem` detects it, strips the prefix, synthesizes speech via TTS, and sends voice. Falls back to text on TTS failure.

**Secondary (send_voice tool):** LLM calls the `send_voice` tool with custom text to synthesize. Works as a fallback for models with tool calling support.

---

## Email (IMAP + SMTP)

Read and send email. Credentials are hidden from the LLM (single-account design).

### IMAP (Reading)

```bash
export IMAP_TOOL_ENABLED=true
export MAIL_IMAP_HOST=imap.example.com
export MAIL_IMAP_PORT=993
export MAIL_IMAP_USERNAME=user@example.com
export MAIL_IMAP_PASSWORD=app-password
export MAIL_IMAP_SECURITY=ssl              # ssl | starttls | none
export MAIL_IMAP_SSL_TRUST=                # "" = strict, "*" = trust all, "host1 host2" = specific
export MAIL_IMAP_CONNECT_TIMEOUT=10000     # ms
export MAIL_IMAP_READ_TIMEOUT=30000        # ms
export MAIL_IMAP_MAX_BODY_LENGTH=50000     # chars
export MAIL_IMAP_DEFAULT_MESSAGE_LIMIT=20
```

### SMTP (Sending)

```bash
export SMTP_TOOL_ENABLED=true
export MAIL_SMTP_HOST=smtp.example.com
export MAIL_SMTP_PORT=587
export MAIL_SMTP_USERNAME=user@example.com
export MAIL_SMTP_PASSWORD=app-password
export MAIL_SMTP_SECURITY=starttls         # ssl | starttls | none
export MAIL_SMTP_SSL_TRUST=                # "" = strict, "*" = trust all, "host1 host2" = specific
export MAIL_SMTP_CONNECT_TIMEOUT=10000     # ms
export MAIL_SMTP_READ_TIMEOUT=30000        # ms
```

### SSL Trust

The `ssl-trust` property maps directly to Jakarta Mail's `ssl.trust` setting:
- `""` (default) — strict certificate verification
- `"*"` — trust all certificates (useful for mail bridges with self-signed certs)
- `"localhost"` or `"host1 host2"` — trust only specific hosts (space-separated)

---

## Browser Tool

The Browse tool uses Playwright/Chromium for web page rendering, screenshots, and text extraction.

### Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `BOT_TOOL_BROWSE_ENABLED` | `true` | Enable/disable browse tool |
| `BOT_TOOL_BROWSE_TIMEOUT_SECONDS` | `30` | Maximum time for page load/processing |
| `PLAYWRIGHT_BROWSERS_PATH` | `/opt/playwright` | Path to Playwright browsers (pre-installed in image) |
| `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` | `1` | Skip browser download (already bundled) |
| `DEBUG` | — | Set to `pw:api` for Playwright debug logs |
| `DBUS_SESSION_BUS_ADDRESS` | — | Set to `/dev/null` to suppress DBUS warnings in containers |

### Docker Requirements

Chromium inside Docker requires extra settings — without them the browser will crash or time out:

```bash
docker run -d \
  --shm-size=256m \
  --cap-add=SYS_ADMIN \
  ...
```

```yaml
# docker-compose.yml
services:
  golemcore-bot:
    shm_size: '256m'
    cap_add:
      - SYS_ADMIN
```

- **`shm_size: 256m`** — Chromium uses `/dev/shm` for rendering; Docker defaults to 64MB which causes crashes
- **`cap_add: SYS_ADMIN`** — required for Chrome's sandbox (user namespaces) inside containers

---

## Streaming

```bash
export BOT_STREAMING_ENABLED=true
export BOT_STREAMING_CHUNK_DELAY_MS=50
export BOT_STREAMING_TYPING_INDICATOR=true
```

---

## Logging

```bash
export LOGGING_LEVEL_ME_GOLEMCORE_BOT=INFO   # DEBUG, INFO, WARN, ERROR
export LOGGING_LEVEL_DEV_LANGCHAIN4J=DEBUG
```

**Production recommendation:** `INFO` for bot, `WARN` for LangChain4j

---

## Performance Tuning

### HTTP Client

```bash
export BOT_HTTP_CONNECT_TIMEOUT=10000   # ms
export BOT_HTTP_READ_TIMEOUT=60000      # ms
export BOT_HTTP_WRITE_TIMEOUT=60000     # ms
export BOT_HTTP_MAX_IDLE_CONNECTIONS=5
export BOT_HTTP_KEEP_ALIVE_DURATION=300000  # 5 minutes
```

### Agent Loop

```bash
export BOT_AGENT_MAX_ITERATIONS=20  # max tool call loops
```

**Lower** = faster, but may not complete complex tasks
**Higher** = more capable, but slower

---

## Environment-Specific Configs

### Development

```bash
# Lenient settings
export BOT_RATE_LIMIT_ENABLED=false
export LOGGING_LEVEL_ME_GOLEMCORE_BOT=DEBUG
```

### Production

```bash
# Strict settings
export BOT_RATE_LIMIT_ENABLED=true
export TOOL_CONFIRMATION_ENABLED=true
export LOGGING_LEVEL_ME_GOLEMCORE_BOT=INFO
export LOGGING_LEVEL_DEV_LANGCHAIN4J=WARN
```

### Testing

```bash
# Fast, minimal
export SKILL_MATCHER_ENABLED=false
export RAG_ENABLED=false
export AUTO_MODE_ENABLED=false
export MCP_ENABLED=false
```

---

## Configuration Validation

Check active configuration:

```bash
/status
```

Shows:
- Active model tier
- Enabled tools
- Rate limit status
- LLM usage (24h)

---

## Advanced: models.json

Edit `models.json` in working directory to add custom models:

```json
{
  "models": [
    {
      "id": "openai/gpt-5.1",
      "provider": "openai",
      "requiresReasoning": true,
      "supportsTemperature": false,
      "maxInputTokens": 128000
    },
    {
      "id": "custom/my-model",
      "provider": "custom",
      "requiresReasoning": false,
      "supportsTemperature": true,
      "maxInputTokens": 32000
    }
  ]
}
```

Then reference in config:

```bash
export BOT_ROUTER_DEFAULT_MODEL=custom/my-model
```

---

## See Also

- [Quick Start](QUICKSTART.md)
- [Skills Guide](SKILLS.md)
- [Tools Reference](TOOLS.md)
- [MCP Integration](MCP.md)
- [Deployment Guide](DEPLOYMENT.md)
