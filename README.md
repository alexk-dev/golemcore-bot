# GolemCore Bot

> AI assistant framework with intelligent skill routing, multi-LLM support, and autonomous execution capabilities

[![CI](https://github.com/alexk-dev/golemcore-bot/actions/workflows/docker-publish.yml/badge.svg)](https://github.com/alexk-dev/golemcore-bot/actions/workflows/docker-publish.yml)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-1275%20passing-success.svg)](https://github.com/alexk-dev/golemcore-bot/actions)

---

## 🚀 Key Features

### 🧠 Intelligent Processing
- **Hybrid Skill Routing** — 2-stage semantic search + LLM classifier
- **Dynamic Model Tier Selection** — Automatic escalation to coding-tier model when code activity detected
- **Context Overflow Protection** — Smart truncation with emergency recovery, handles 50K+ token conversations
- **Fragmented Input Detection** — Aggregates split messages using temporal and linguistic signals

### 🛠️ Powerful Tools
- **10 Built-in Tools** — Filesystem, Shell, Web Search, Browser, Weather, Skill Management, Goal Management, Transitions, DateTime, Voice
- **MCP Protocol Support** — Model Context Protocol for stdio-based tool servers (GitHub, Slack, etc.)
- **Sandboxed Execution** — Isolated workspace with path traversal protection
- **Tool Confirmation** — User approval workflow for destructive operations

### 🔄 Advanced Capabilities
- **Auto Mode** — Autonomous goal-driven execution with periodic synthetic messages
- **Skill Pipelines** — Sequential skill transitions with conditional routing
- **RAG Integration** — LightRAG for long-term memory via knowledge graphs
- **LLM-Powered Compaction** — Conversation summarization when context limits reached
- **Modular System Prompt** — File-based prompt sections (IDENTITY.md, RULES.md)

### 🌐 Multi-LLM & Channels
- **LLM Providers** — OpenAI, Anthropic (Claude), custom OpenAI-compatible endpoints
- **Channels** — Telegram (long-polling, voice, file uploads), extensible for Discord/Slack

### 🔒 Security
- 5 layers: Unicode normalization, injection detection, allowlists, sandboxing, content policy
- Rate limiting: configurable request limits (20/min, 100/hr, 500/day), per-channel, per-LLM
- Tool confirmation with 60s timeout

---

## 📋 Table of Contents

- [Quick Start](#-quick-start)
- [Core Capabilities](#-core-capabilities)
- [Environment Variables](#-environment-variables)
- [Configuration](#-configuration)
- [Tools & Integrations](#-tools--integrations)
- [Skill System](#-skill-system)
- [Commands](#-commands)
- [Development](#-development)
- [Architecture](#-architecture)
- [Contributing](#-contributing)
- [License](#-license)

## 📚 Documentation

- **[Quick Start Guide](docs/QUICKSTART.md)** — Get running in 5 minutes
- **[Coding Guide](docs/CODING_GUIDE.md)** — Code style, conventions, commit messages
- **[Configuration Guide](docs/CONFIGURATION.md)** — All settings and environment variables
- **[Model Routing Guide](docs/MODEL_ROUTING.md)** — Tier selection, hybrid routing, dynamic upgrades, context overflow
- **[Auto Mode Guide](docs/AUTO_MODE.md)** — Goals, tasks, tick cycle, diary
- **[RAG Guide](docs/RAG.md)** — LightRAG integration, indexing, retrieval
- **[Memory Guide](docs/MEMORY.md)** — Sessions, daily notes, long-term memory, compaction
- **[Deployment Guide](docs/DEPLOYMENT.md)** — Docker, systemd, production setup
- **[FAQ](FAQ.md)** — Common questions & troubleshooting
- **[Contributing](CONTRIBUTING.md)** — Development workflow

---

## ⚡ Quick Start

### Prerequisites

```
Docker (recommended) OR Java 17+ with Maven 3.x
At least one LLM API key (OpenAI or Anthropic)
```

### Docker (Recommended)

```bash
# Clone the repository
git clone https://github.com/alexk-dev/golemcore-bot.git
cd golemcore-bot

# Build Docker image with Jib (no Docker daemon needed)
./mvnw compile jib:dockerBuild

# Configure LLM provider (choose one)
export OPENAI_API_KEY=sk-proj-...          # OpenAI (GPT-5.1, GPT-5.2, o1, o3)
export ANTHROPIC_API_KEY=sk-ant-...        # Anthropic (Claude Opus/Sonnet)

# Run container
docker run -d \
  --name golemcore-bot \
  -e OPENAI_API_KEY \
  -v golemcore-bot-data:/app/workspace \
  -p 8080:8080 \
  --restart unless-stopped \
  golemcore-bot:latest

# Check logs
docker logs -f golemcore-bot
```

### Docker Compose

Create `docker-compose.yml`:

```yaml
version: '3.8'
services:
  golemcore-bot:
    image: golemcore-bot:latest
    container_name: golemcore-bot
    restart: unless-stopped
    environment:
      # LLM (choose one or multiple)
      OPENAI_API_KEY: ${OPENAI_API_KEY}
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY:-}

      # Telegram (optional)
      TELEGRAM_ENABLED: ${TELEGRAM_ENABLED:-false}
      TELEGRAM_BOT_TOKEN: ${TELEGRAM_BOT_TOKEN:-}
      TELEGRAM_ALLOWED_USERS: ${TELEGRAM_ALLOWED_USERS:-}
    volumes:
      - ./workspace:/app/workspace
      - ./sandbox:/app/sandbox
    ports:
      - "8080:8080"
```

Run:
```bash
docker-compose up -d
```

### JAR (Alternative)

```bash
# Build
./mvnw clean package -DskipTests

# Configure LLM provider
export OPENAI_API_KEY=sk-proj-...     # or ANTHROPIC_API_KEY

# Run
java -jar target/golemcore-bot-0.1.0-SNAPSHOT.jar
```

See [Deployment Guide](docs/DEPLOYMENT.md) for production setups (Docker Compose, systemd, etc.)

---

## 🎯 Core Capabilities

### Processing Pipeline (11 Systems)

Messages flow through an ordered pipeline of specialized systems:

```
User Message
    ↓
[10] InputSanitizationSystem     — Unicode normalization, length check
[15] SkillRoutingSystem           — Hybrid skill matching
[18] AutoCompactionSystem         — Context overflow prevention
[20] ContextBuildingSystem        — Prompt assembly, MCP startup
[25] DynamicTierSystem            — Coding activity detection
[30] LlmExecutionSystem           — LLM API call with retry
[40] ToolExecutionSystem          — Tool calls + confirmation
[50] MemoryPersistSystem          — Conversation persistence
[55] RagIndexingSystem            — Long-term memory indexing
[57] SkillPipelineSystem          — Auto-transitions
[60] ResponseRoutingSystem        — Send response to user
```

The loop iterates up to 20 times while the LLM requests tool calls.

### Skill Routing & Model Selection

3-stage hybrid matching: fragmented input detection, semantic search, LLM classifier. 4 model tiers (fast/default/smart/coding) with automatic escalation to coding tier when code activity is detected.

See **[Model Routing Guide](docs/MODEL_ROUTING.md)** for the full end-to-end flow, tier architecture, dynamic upgrades, tool ID remapping, context overflow protection, and debugging tips.

### Auto Mode (Autonomous Execution)

Periodic goal-driven execution: `AutoModeScheduler` sends synthetic messages every N minutes, LLM plans and executes tasks autonomously using `GoalManagementTool`, writes diary entries, sends milestone notifications.

See **[Auto Mode Guide](docs/AUTO_MODE.md)** for the tick cycle, goal/task lifecycle, system prompt injection, and commands.

### Memory & RAG

Multi-layered memory: session messages, daily notes (`YYYY-MM-DD.md`), long-term `MEMORY.md`, and semantic retrieval via LightRAG knowledge graphs. Auto-compaction prevents context overflow.

See **[Memory Guide](docs/MEMORY.md)** and **[RAG Guide](docs/RAG.md)** for details.

### MCP (Model Context Protocol)

Lightweight JSON-RPC 2.0 over stdio — no external SDK. Skills declare MCP servers in YAML frontmatter; tools are discovered and registered automatically. Pool with idle timeout for process lifecycle management.

See the [Tools & Integrations](#-tools--integrations) section below for configuration examples.

---

## 🌍 Environment Variables

### Required: LLM Provider (Choose At Least One)

| Variable | Description | Example |
|----------|-------------|---------|
| `OPENAI_API_KEY` | OpenAI API key (GPT-5.x, o1, o3) | `sk-proj-...` |
| `ANTHROPIC_API_KEY` | Anthropic API key (Claude Opus/Sonnet) | `sk-ant-...` |

### Telegram Channel

| Variable | Description | Default |
|----------|-------------|---------|
| `TELEGRAM_ENABLED` | Enable Telegram integration | `false` |
| `TELEGRAM_BOT_TOKEN` | Bot token from @BotFather | *(empty)* |
| `TELEGRAM_ALLOWED_USERS` | Comma-separated user IDs (allowlist) | *(empty)* |

### Key Feature Toggles

| Variable | Description | Default |
|----------|-------------|---------|
| `SKILL_MATCHER_ENABLED` | Hybrid skill routing (semantic + LLM) | `false` |
| `RAG_ENABLED` | LightRAG long-term memory | `false` |
| `AUTO_MODE_ENABLED` | Autonomous goal execution | `false` |
| `MCP_ENABLED` | Model Context Protocol client | `true` |
| `BRAVE_SEARCH_ENABLED` | Web search via Brave | `false` |
| `VOICE_ENABLED` | Voice processing (ElevenLabs STT/TTS) | `false` |
| `ELEVENLABS_API_KEY` | ElevenLabs API key for voice | — |
| `BOT_ROUTER_DYNAMIC_TIER_ENABLED` | Auto-upgrade to coding tier | `true` |

For the complete list of 80+ environment variables (model routing, security, rate limiting, storage, tools, voice, streaming, HTTP, etc.), see the **[Configuration Guide](docs/CONFIGURATION.md)**.

---

## 🔧 Configuration

### Quick Examples

```bash
# Basic: any LLM provider
docker run -e OPENAI_API_KEY=sk-proj-... golemcore-bot:latest

# Telegram bot
docker run -d \
  -e OPENAI_API_KEY=sk-proj-... \
  -e TELEGRAM_ENABLED=true \
  -e TELEGRAM_BOT_TOKEN=123456:ABC-DEF... \
  -e TELEGRAM_ALLOWED_USERS=123456789 \
  -v golemcore-bot-data:/app/workspace \
  golemcore-bot:latest

# Multi-provider model routing
docker run -d \
  -e OPENAI_API_KEY=sk-proj-... \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  -e BOT_ROUTER_FAST_MODEL=openai/gpt-5.1 \
  -e BOT_ROUTER_SMART_MODEL=anthropic/claude-opus-4-6 \
  -e BOT_ROUTER_CODING_MODEL=openai/gpt-5.2 \
  golemcore-bot:latest
```

**Configuration priority:** Environment variables > `application.properties`

**Model definitions:** `models.json` in working directory — see [Model Routing Guide](docs/MODEL_ROUTING.md#modelsjson-reference).

See **[Configuration Guide](docs/CONFIGURATION.md)** for all settings, **[Quick Start](docs/QUICKSTART.md)** for Docker Compose examples, and **[Deployment Guide](docs/DEPLOYMENT.md)** for production setups.

---

## 🛠️ Tools & Integrations

### Built-in Tools (10)

| Tool | Operations | Requires | Notes |
|------|------------|----------|-------|
| **FileSystem** | read, write, list, mkdir, delete, send_file | — | Sandboxed to `~/.golemcore/sandbox` |
| **Shell** | execute | — | Timeout: 30s-300s, blocklist: `rm -rf /`, `sudo su` |
| **SkillManagement** | create_skill, list_skills, get_skill, delete_skill | — | YAML frontmatter parser |
| **SkillTransition** | transition_to_skill | — | For skill pipelines |
| **GoalManagement** | create_goal, list_goals, plan_tasks, update_task_status, write_diary, complete_goal | — | Auto-mode only |
| **Browser** | browse | Playwright | Modes: text, html, screenshot |
| **BraveSearch** | search | `BRAVE_SEARCH_API_KEY` | 2000 free queries/month |
| **Weather** | get_weather | — | Open-Meteo API (free) |
| **DateTime** | current_time, convert_timezone, date_math | — | — |
| **VoiceResponse** | send_voice | `ELEVENLABS_API_KEY` | LLM-initiated TTS synthesis |

### MCP Integrations

**Install MCP server via skill configuration:**

```yaml
---
name: github-assistant
description: GitHub repository management
mcp:
  command: npx -y @modelcontextprotocol/server-github
  env:
    GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}
---
You are a GitHub assistant. Help manage repos, issues, and PRs.
```

**Popular MCP servers:**
- `@modelcontextprotocol/server-github` — GitHub
- `@modelcontextprotocol/server-slack` — Slack
- `@modelcontextprotocol/server-google-drive` — Google Drive
- `@modelcontextprotocol/server-filesystem` — Filesystem (advanced)

**Tool discovery is automatic** — MCP server tools appear as native tools to the LLM.

---

## 📚 Skill System

### Skill Structure

Skills are Markdown files with YAML frontmatter:

```markdown
---
name: code-reviewer
description: Reviews code for bugs, style, and best practices
tags: [coding, review]
requires:
  env: [OPENAI_API_KEY]
vars:
  max_file_size: { value: "10000", type: "int" }
next_skill: test-runner
conditional_next_skills:
  - condition: "if tests fail"
    skill: debug-assistant
---

You are an expert code reviewer. Analyze code for:
- Bugs and logic errors
- Performance issues
- Security vulnerabilities
- Code style violations

Use the filesystem tool to read files. Be thorough and constructive.
```

### Skill Commands

```bash
/skills                    # List all available skills
```

To create or manage skills, ask the bot directly:
```
You: Create a skill called "code-reviewer" for reviewing Python code
Bot: [Uses SkillManagementTool to create the skill]
```

### Skill Pipelines

**Auto-transitions** when `next_skill` or `conditional_next_skills` defined:

```
code-reviewer → test-runner → (if tests fail) → debug-assistant
```

Max depth: 5 (prevents infinite loops)

### Skill Variables

**Resolution order:**
1. Skill YAML `vars:` section
2. Environment variables
3. User preferences

**Variable types:**
- `env` — Environment variable with optional secret flag
- `value` — Static value with type (int, bool, string)
- `prompt` — User input (collected on skill activation)

---

## 💬 Commands

| Command | Description |
|---------|-------------|
| `/help` | Show all available commands |
| `/skills` | List available skills |
| `/tools` | List enabled tools |
| `/status` | Session info + 24h usage stats |
| `/new`, `/reset` | Start new conversation |
| `/compact [N]` | Summarize old messages, keep last N (default: 10) |
| `/settings` | Language selection (Telegram only) |
| `/auto [on\|off]` | Toggle autonomous mode |
| `/goals` | List active goals |
| `/goal <desc>` | Create a new goal |
| `/tasks` | List tasks for active goals |
| `/diary [N]` | Show last N diary entries |

---

## 💻 Development

### Pre-commit Hooks

This project uses the [pre-commit](https://pre-commit.com) framework (`.pre-commit-config.yaml`):

```bash
pip install pre-commit    # one-time
pre-commit install        # install hooks for this repo
```

**Hooks run on each commit:** trailing whitespace, YAML/JSON validation, merge conflict detection, large file check, private key detection, PMD static analysis.

**Full quality check:**

```bash
mvn clean verify -P strict
```

### Project Structure

```
src/main/java/me/golemcore/bot/
├── adapter/                   # Inbound/outbound adapters
│   ├── inbound/
│   │   ├── command/          # Slash commands
│   │   └── telegram/         # Telegram bot
│   └── outbound/
│       ├── llm/              # LLM providers (Langchain4j, Custom, NoOp)
│       ├── storage/          # Local filesystem
│       ├── mcp/              # MCP client
│       ├── rag/              # RAG integration
│       └── voice/            # ElevenLabs STT + TTS
├── domain/                    # Core business logic
│   ├── loop/                 # Agent loop orchestration
│   ├── system/               # Processing pipeline (11 systems)
│   ├── component/            # Component interfaces
│   ├── model/                # Domain models
│   └── service/              # Business services
├── auto/                      # Auto-mode scheduler
├── routing/                   # Hybrid skill matcher
├── security/                  # Security layers
├── tools/                     # 10 built-in tools
└── usage/                     # Usage tracking
```

### Running Tests

```bash
# All tests
mvn test

# Specific test class
mvn test -Dtest=SessionServiceTest

# With coverage report
mvn test jacoco:report
open target/site/jacoco/index.html
```

### Code Quality Reports

After running checks:
- **PMD:** `target/pmd.xml`
- **SpotBugs:** `target/spotbugsXml.xml`
- **Coverage:** `target/site/jacoco/index.html`

---

## 🏗️ Architecture

### Processing Flow

```
┌───────────────────────────────────────────────────────────┐
│                        Input Layer                        │
│  ┌────────────┐  ┌──────────────┐                         │
│  │  Telegram  │  │ CommandRouter│                         │
│  └─────┬──────┘  └──────┬───────┘                         │
├────────┼────────────────┼─────────────────────────────────┤
│        └────────┬───────┘                                 │
│                 ▼                                         │
│  ┌─────────────────────────────────────────────────────┐  │
│  │              Agent Processing Loop                  │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────┐   │  │
│  │  │  Skill   │  │ Context  │  │ Tool Execution + │   │  │
│  │  │ Routing  │  │ Building │  │   LLM Calls      │   │  │
│  │  └──────────┘  └──────────┘  └──────────────────┘   │  │
│  └─────────────────────────────────────────────────────┘  │
│                                                           │
│        │                │                 │               │
├────────┼────────────────┼─────────────────┼───────────────┤
│        ▼                ▼                 ▼               │
│  ┌───────────────┐ ┌───────────┐ ┌───────────────────┐    │
│  │     LLM       │ │  Storage  │ │     Embedding     │    │
│  │ (Langchain4j) │ │  (Local)  │ │     (OpenAI)      │    │
│  └───────────────┘ └───────────┘ └───────────────────┘    │
│                     Service Layer                         │
└───────────────────────────────────────────────────────────┘
```

The bot processes messages through ordered pipeline stages:
- **Skill Routing** — matches user intent to skills
- **Context Building** — assembles system prompt, memory, skill context
- **Tool Execution** — handles LLM tool calls

### Tech Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| **Language** | Java | 17+ |
| **Framework** | Spring Boot | 3.4.2 |
| **LLM Integration** | LangChain4j | 1.0.0-beta1 |
| **HTTP Client** | Feign + OkHttp | 13.5 + 4.12 |
| **Messaging** | Telegram Bots | 8.2.0 |
| **Browser** | Playwright | 1.49.0 |
| **Security** | Custom (Unicode normalization, injection guard) | — |
| **Voice** | ElevenLabs (STT + TTS) | API v1 |
| **Testing** | JUnit 5 + Mockito | — |
| **Code Quality** | SpotBugs + PMD + JaCoCo | — |

---

## 🤝 Contributing

Contributions welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for:
- Development workflow
- Code quality standards (PMD, SpotBugs, 85%+ coverage)
- Testing requirements
- Pull request process

### Quick Contribute

```bash
# Fork and clone
git clone https://github.com/YOUR_USERNAME/golemcore-bot.git
cd golemcore-bot

# Install hooks
pip install pre-commit && pre-commit install

# Create branch
git checkout -b feature/amazing-feature

# Make changes, add tests
# ...

# Run checks
mvn clean verify -P strict

# Commit and push
git commit -m "Add amazing feature"
git push origin feature/amazing-feature

# Open Pull Request on GitHub
```

### Patent Grant

By contributing, you automatically grant:
- **Copyright license** (Apache 2.0 Section 2)
- **Patent license** (Apache 2.0 Section 3) for your contributions

See [LICENSE](LICENSE) for details.

---

## 📄 License

This project is licensed under the **Apache License 2.0**.

### TL;DR

✅ **Commercial use allowed**
✅ **Modify and distribute freely**
✅ **Patent protection** for contributors
⚠️ **Must include license and notices**
⚠️ **State changes if you modify code**
❌ **No trademark use without permission**

See [LICENSE](LICENSE) for full text and [NOTICE](NOTICE) for attributions.

### Third-Party Licenses

| Library | License |
|---------|---------|
| Spring Boot, LangChain4j, OkHttp, Playwright, Jackson | Apache 2.0 |
| Telegram Bots, Lombok | MIT |

---

## 🙏 Acknowledgments

Built with:
- [Spring Boot](https://spring.io/projects/spring-boot) — Application framework
- [LangChain4j](https://github.com/langchain4j/langchain4j) — LLM integration
- [Telegram Bots](https://github.com/rubenlagus/TelegramBots) — Telegram API
- [Playwright](https://playwright.dev/) — Headless browser
Special thanks to the Model Context Protocol community for MCP tooling.

---

## 📞 Support

- 🐛 **Issues:** [GitHub Issues](https://github.com/alexk-dev/golemcore-bot/issues)
- 💬 **Discussions:** [GitHub Discussions](https://github.com/alexk-dev/golemcore-bot/discussions)
- 📧 **Security:** Report vulnerabilities via email (not public issues)

---

<div align="center">

**⭐ Star this repo if you find it useful!**

Made with ☕ and 🤖

</div>
