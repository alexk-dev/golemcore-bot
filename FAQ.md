# Frequently Asked Questions (FAQ)

## General

### What is GolemCore Bot?

GolemCore Bot is an enterprise-grade AI assistant framework with intelligent skill routing, multi-LLM support, and autonomous execution capabilities. It's built with Spring Boot and designed for extensibility and production use.

### Who is this for?

- **Developers** building AI-powered applications
- **Teams** needing a customizable AI assistant
- **Enterprises** requiring multi-LLM support with security controls
- **Researchers** experimenting with agentic AI architectures

### What makes it different from other AI frameworks?

1. **Hybrid Skill Routing** — 2-stage semantic + LLM classifier for accurate intent matching
2. **Dynamic Model Tier Selection** — Automatic escalation from GPT-5.1 → GPT-5.2 based on task
3. **MCP Protocol Support** — Connect stdio-based tool servers (GitHub, Slack, etc.)
4. **Auto Mode** — Autonomous goal-driven execution
5. **Context Overflow Protection** — Smart truncation handles 50K+ token conversations
6. **Production-ready** — Security layers, rate limiting, monitoring

---

## Installation & Setup

### Do I need to install Docker?

**Recommended but not required.**

**With Docker (recommended):**
- Consistent environment across platforms
- Easy deployment and scaling
- No Java installation needed
- Isolated from host system

**Without Docker (JAR):**
- Requires Java 17+ installed
- Direct access to host filesystem
- Simpler for development

With Jib, you can build Docker images **without Docker daemon installed**:

```bash
./mvnw compile jib:dockerBuild
```

### Which LLM provider should I use?

**Supported providers:**
- **OpenAI** — GPT-5.1, GPT-5.2, o1, o3, GPT-4
- **Anthropic** — Claude Opus, Claude Sonnet
- **Google** — Gemini models via LangChain4j
- **Custom endpoints** — Any OpenAI-compatible API

All providers have similar capabilities. Choose based on your preferences:
- **Cost** — Compare pricing for your usage patterns
- **Features** — Some models support reasoning effort (o1, o3)
- **Latency** — Test response times for your region
- **Availability** — Check API access in your location

You can mix providers across tiers:
```bash
export OPENAI_API_KEY=sk-...                    # For fast/default tiers
export ANTHROPIC_API_KEY=sk-ant-...             # For smart tier
export BOT_ROUTER_SMART_MODEL=anthropic/claude-opus-4-6
```

### Can I run this without Telegram?

**Yes** — CLI mode is default.

**Docker:**
```bash
docker run -e OPENAI_API_KEY=sk-... golemcore-bot:latest
```

**JAR:**
```bash
java -jar golemcore-bot.jar
```

No Telegram token needed.

### How much does it cost to run?

**Variable costs:**
- **LLM API calls** — Pay-per-token (varies by provider and model)
  - OpenAI: Check [pricing page](https://openai.com/pricing)
  - Anthropic: Check [pricing page](https://www.anthropic.com/pricing)
  - Google: Check [pricing page](https://ai.google.dev/pricing)
- **Optional: Brave Search** — Free tier: 2000 queries/month, paid plans available
- **Optional: ElevenLabs TTS** — If voice enabled

**No cost for:**
- GolemCore Bot itself (Apache 2.0 license)
- Local storage
- Most tools (filesystem, shell, datetime, weather)
- Docker runtime (uses existing infrastructure)

**Cost control:**
- Set rate limits (requests per minute/hour/day)
- Configure auto-compaction to reduce token usage
- Use faster models for simple tasks (tier-based routing)
- Monitor usage with `/status` command

Track real-time usage:
```bash
/status
```

---

## Features

### What is "skill routing"?

**Skill routing** matches user messages to the best skill (specialized prompt + tools).

**Example:**
```
User: "Review this Python code for bugs"
Bot: [Matches to "code-reviewer" skill]
     [Uses filesystem tool to read code]
     [Analyzes with GPT-5.2 coding model]
```

**3 stages:**
1. **Fragment detection** — Aggregates split messages
2. **Semantic search** — Embedding similarity (~5ms)
3. **LLM classifier** — Accurate selection (~200ms)

Enable:
```bash
export SKILL_MATCHER_ENABLED=true
```

### What is "auto mode"?

**Auto mode** = autonomous goal execution without user input.

**How it works:**
1. User creates goal: `/goal "Deploy bot to production"`
2. Bot plans tasks using `GoalManagementTool`
3. Every 15 minutes, bot works on tasks autonomously
4. User receives milestone notifications

**Enable:**
```bash
export AUTO_MODE_ENABLED=true
export AUTO_MODE_INTERVAL=15  # minutes
```

**Use cases:**
- Long-running research tasks
- Periodic monitoring/reporting
- Multi-step deployment workflows
- Continuous improvement projects

### What is MCP?

**MCP** = Model Context Protocol — connects AI to external tools via stdio.

**Without MCP:**
```
Bot → [Built-in 9 tools only]
```

**With MCP:**
```
Bot → [Built-in tools + GitHub + Slack + Google Drive + ...]
```

**Setup:**
Create skill with `mcp:` section:

```yaml
---
name: github-assistant
mcp:
  command: npx -y @modelcontextprotocol/server-github
  env:
    GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}
---
You are a GitHub assistant...
```

**Popular MCP servers:**
- GitHub — repos, issues, PRs
- Slack — channels, messages
- Google Drive — files, folders
- Filesystem — advanced file operations
- Web Search — Tavily, SearXNG

See: [docs/MCP.md](docs/MCP.md)

### Does it support voice?

**Yes** (experimental):

```bash
export VOICE_ENABLED=true
export BOT_VOICE_STT_PROVIDER=whisper       # Speech-to-text
export BOT_VOICE_TTS_PROVIDER=elevenlabs    # Text-to-speech
export BOT_VOICE_TELEGRAM_RESPOND_WITH_VOICE=true
```

**Requirements:**
- Whisper API (OpenAI) for STT
- ElevenLabs API for TTS
- FFmpeg installed (for audio conversion)

### Can it browse the web?

**Yes** — via `BrowserTool`:

```bash
export BROWSER_ENABLED=true
```

**Features:**
- Headless browsing with Playwright
- Modes: text, html, screenshot
- JavaScript rendering
- 30s timeout (configurable)

**Example:**
```
User: Take a screenshot of https://example.com
Bot: [Uses BrowserTool in screenshot mode]
     [Sends image to user]
```

---

## Usage

### How do I create custom skills?

**Method 1: Via LLM (recommended)**
```
You: Create a skill called "code-reviewer" that reviews Python code for bugs
Bot: [Creates SKILL.md file with YAML frontmatter]
```

**Method 2: Manual**
Create `workspace/skills/code-reviewer/SKILL.md`:

```markdown
---
name: code-reviewer
description: Reviews Python code for bugs
tags: [coding, review]
---
You are an expert Python code reviewer...
```

See: [docs/SKILLS.md](docs/SKILLS.md)

### How do I list available tools?

```bash
/tools
```

Shows:
- Tool name
- Operations
- Enabled status
- Requirements (API keys, etc.)

### Can the bot execute shell commands?

**Yes** — but sandboxed:

```bash
export SHELL_TOOL_ENABLED=true
export TOOLS_WORKSPACE=~/.golemcore/sandbox  # isolated directory
```

**Security:**
- ✅ Sandboxed to `TOOLS_WORKSPACE` (cannot access parent dirs)
- ✅ Dangerous commands blocked (`rm -rf /`, `sudo su`, etc.)
- ✅ Timeout protection (30s default, 300s max)
- ✅ User confirmation for risky operations

**Blocked commands:**
- `rm -rf /`
- `sudo su`, `su -`
- `mkfs`, `dd if=/dev/zero`
- `chmod -R 777 /`

### How do I reset the conversation?

```bash
/new
# or
/reset
```

Starts fresh conversation, clears history.

### How do I check LLM usage/costs?

```bash
/status
```

Shows:
- Messages in session
- Last 24h usage by model
- Token counts
- Estimated cost

Usage logs stored in `workspace/usage/` (JSONL format).

---

## Troubleshooting

### "Rate limit exceeded" error

**Causes:**
1. Too many requests per minute/hour/day
2. LLM provider rate limit

**Solutions:**

**Adjust bot limits:**
```bash
export BOT_RATE_LIMIT_USER_REQUESTS_PER_MINUTE=10  # lower
export BOT_RATE_LIMIT_USER_REQUESTS_PER_HOUR=50
```

**Increase LLM provider limits:**
- OpenAI: Upgrade tier (https://platform.openai.com/settings/organization/billing/overview)
- Anthropic: Contact support

**Check current limits:**
```bash
/status
```

### "Context too long" error

**Cause:** Conversation history exceeds model's context window (128K tokens for GPT-5.1)

**Auto-fix:** Enable auto-compaction (enabled by default):
```bash
export BOT_AUTO_COMPACT_ENABLED=true
export BOT_AUTO_COMPACT_MAX_CONTEXT_TOKENS=50000
```

**Manual fix:**
```bash
/compact 10  # Keep last 10 messages, summarize rest
```

### Tool confirmation times out

**Cause:** User didn't approve within 60s

**Solutions:**

**Increase timeout:**
```bash
export TOOL_CONFIRMATION_TIMEOUT=120  # 2 minutes
```

**Disable confirmations (not recommended for production):**
```bash
export TOOL_CONFIRMATION_ENABLED=false
```

**Auto-approve for trusted users:**
Edit `ToolConfirmationPolicy` in code.

### Skills not loading

**Check:**

1. **Skill directory exists:**
   ```bash
   ls ~/.golemcore/workspace/skills/
   ```

2. **SKILL.md format:**
   ```markdown
   ---
   name: my-skill
   description: My skill
   ---
   Prompt text here...
   ```

3. **Enable skills:**
   ```bash
   export BOT_SKILLS_ENABLED=true
   ```

4. **View logs:**
   ```bash
   export LOGGING_LEVEL_ME_GOLEMCORE_BOT=DEBUG
   java -jar golemcore-bot.jar
   ```

### MCP server won't start

**Debug:**

1. **Check command:**
   ```bash
   npx -y @modelcontextprotocol/server-github --version
   ```

2. **Check environment variables:**
   ```yaml
   mcp:
     command: npx -y @modelcontextprotocol/server-github
     env:
       GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}  # Must be set
   ```

3. **View MCP logs:**
   ```bash
   export LOGGING_LEVEL_ME_GOLEMCORE_BOT=DEBUG
   ```

4. **Increase startup timeout:**
   ```bash
   export BOT_MCP_DEFAULT_STARTUP_TIMEOUT=60  # 60 seconds
   ```

### Docker: Environment variables not working

**Fix:** Pass via `-e` flag:

```bash
docker run -e OPENAI_API_KEY=sk-... \
           -e TELEGRAM_ENABLED=true \
           -e TELEGRAM_BOT_TOKEN=... \
           golemcore-bot:latest
```

Or use `.env` file:

```bash
# .env
OPENAI_API_KEY=sk-...
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=...
```

```bash
docker run --env-file .env golemcore-bot:latest
```

---

## Security

### Is my data stored locally?

**Yes** — by default, everything is local:

```
~/.golemcore/
├── workspace/
│   ├── sessions/      # Conversation history (local)
│   ├── memory/        # Long-term memory (local)
│   └── skills/        # Custom skills (local)
└── sandbox/           # Tool workspace (isolated)
```

**Sent to LLM providers:**
- Message content
- Tool results
- System prompts

**Not sent:**
- File paths
- Environment variables (except in tool results)
- Session metadata

### Can I use this in production?

**Yes** — designed for production:

✅ **Security:**
- OWASP HTML sanitizer
- Prompt injection detection
- Command injection detection
- Path traversal protection
- User allowlists

✅ **Reliability:**
- Exponential backoff retry (LLM calls)
- Context overflow recovery
- Rate limiting
- Tool timeout protection

✅ **Monitoring:**
- Usage tracking (tokens, costs)
- Structured logging
- Health checks (coming soon)

**Recommendations:**
- Use allowlists in production
- Enable tool confirmations
- Set rate limits conservatively
- Monitor usage logs
- Run behind reverse proxy (nginx)

### How do I restrict access?

**Telegram:**
```bash
export BOT_SECURITY_ALLOWLIST_ENABLED=true
export TELEGRAM_ALLOWED_USERS=123456789,987654321
```

**CLI/HTTP:**
Implement custom `ChannelPort` with authentication.

### Are API keys secure?

**Yes:**
- ✅ Never logged
- ✅ Never sent to LLM (except as tool results if explicitly requested)
- ✅ Stored as environment variables (not in files)

**Best practices:**
- Use `.env` file with `chmod 600`
- Rotate keys regularly
- Use separate keys per environment (dev/prod)
- Set spending limits in LLM provider dashboard

---

## Performance

### How fast is it?

**Typical response times:**
- **Fast tier (greetings):** 500ms - 1s
- **Default tier (Q&A):** 1s - 3s
- **Coding tier (code gen):** 3s - 10s
- **With tools:** +1s - 5s per tool call

**Skill routing:**
- Semantic search: ~5ms
- LLM classifier: ~200ms
- Cache hit: <1ms

### How many conversations can it handle?

**Single instance:**
- CLI: 1 user
- Telegram: 100s of users (limited by rate limits)

**Scaling:**
- Run multiple instances behind load balancer
- Each instance stateless (sessions in storage)
- Shared storage (Redis, S3) for session state

### Can I disable features to improve speed?

**Yes:**

```bash
# Disable skill routing (faster)
export SKILL_MATCHER_ENABLED=false

# Disable RAG (faster)
export RAG_ENABLED=false

# Disable MCP (faster startup)
export MCP_ENABLED=false

# Lower max iterations (faster, less capable)
export BOT_AGENT_MAX_ITERATIONS=10
```

---

## Development

### How do I contribute?

See [CONTRIBUTING.md](CONTRIBUTING.md):

1. Fork repo
2. Install hooks: `pip install pre-commit && pre-commit install`
3. Create branch: `git checkout -b feature/amazing-feature`
4. Make changes, add tests
5. Run checks: `mvn clean verify -P strict`
6. Open PR

### How do I add a new tool?

1. Create class in `tools/`:

```java
@Component
public class MyTool implements ToolComponent {
    @Override
    public ToolDefinition getDefinition() {
        // JSON Schema for input
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        // Implementation
    }

    @Override
    public boolean isEnabled() {
        return properties.isMyToolEnabled();
    }
}
```

2. Add config:
```properties
bot.tools.my-tool.enabled=${MY_TOOL_ENABLED:true}
```

3. Add tests

See: `FileSystemTool`, `ShellTool` for examples

### How do I add a new LLM provider?

1. Implement `LlmProviderAdapter`:

```java
@Component
public class MyLlmAdapter implements LlmProviderAdapter {
    @Override
    public LlmResponse chat(LlmRequest request) {
        // Call your LLM API
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null;
    }
}
```

2. Register in `LlmAdapterFactory`

3. Add config:
```bash
export BOT_LLM_PROVIDER=my-provider
```

---

## Licensing

### Can I use this commercially?

**Yes** — Apache License 2.0 allows commercial use.

### Do I need to open-source my modifications?

**No** — Apache 2.0 does not require sharing modifications.

**However:**
- ✅ Must include LICENSE file
- ✅ Must include NOTICE file
- ✅ Must state changes if you modify code

### Can I sell this?

**Yes** — you can:
- Use in commercial products
- Sell as SaaS
- Integrate into proprietary software

**Must:**
- Include LICENSE and NOTICE
- Not use "GolemCore Bot" trademark without permission

### What's the patent grant?

By contributing, you grant:
- **Copyright license** (Section 2) — others can use your code
- **Patent license** (Section 3) — others can use your patented ideas in the code

**Defensive termination:** If you sue the project for patent infringement, your patent license terminates.

See [LICENSE](LICENSE) Section 3.

---

## Still have questions?

- 📚 **Docs:** [docs/](docs/)
- 💬 **Discussions:** [GitHub Discussions](https://github.com/your-org/golemcore-bot/discussions)
- 🐛 **Issues:** [GitHub Issues](https://github.com/your-org/golemcore-bot/issues)
- 📧 **Security:** Email maintainers directly
