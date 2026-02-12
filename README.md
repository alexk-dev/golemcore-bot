# GolemCore Bot

> AI assistant framework for building **autonomous coding agents** on Java — skills, MCP tools, tiered multi-LLM routing, and sandboxed tool execution.

[![CI](https://github.com/alexk-dev/golemcore-bot/actions/workflows/docker-publish.yml/badge.svg)](https://github.com/alexk-dev/golemcore-bot/actions/workflows/docker-publish.yml)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-1451%20passing-success.svg)](https://github.com/alexk-dev/golemcore-bot/actions)

---

## What makes it useful (coding-agent focus)

- **Skills as files** — Markdown `SKILL.md` with YAML frontmatter, variables, and progressive loading.
- **MCP (Model Context Protocol)** — attach external tool servers via stdio (GitHub, Slack, custom tooling, LSP bridges, etc.).
- **Hybrid routing + model tiers** — semantic pre-filter + LLM classifier picks both **skill** and **tier** (`balanced/smart/coding/deep`), with dynamic upgrade to `coding` when code activity is detected.
- **Tooling + sandbox** — built-in tools like filesystem/shell/browser/search/email with safety rails (confirmation for destructive actions).
- **Autonomy primitives** — Auto Mode (goals/tasks/diary) + memory and optional RAG.

If you want the full deep-dive: start with **[Skills](docs/SKILLS.md)** and **[Model Routing](docs/MODEL_ROUTING.md)**.

---

## ⚡ Quick start (Docker, ~5–10 min)

### Prerequisites

- Docker (recommended) **or** Java 17+ / Maven 3.x
- At least one LLM API key (OpenAI or Anthropic)

### Docker (recommended)

```bash
# Clone
git clone https://github.com/alexk-dev/golemcore-bot.git
cd golemcore-bot

# Build image with Jib (no Docker daemon needed)
./mvnw compile jib:dockerBuild

# Configure an LLM provider (choose one)
export OPENAI_API_KEY=sk-proj-...
# or: export ANTHROPIC_API_KEY=sk-ant-...

# Run
docker run -d \
  --name golemcore-bot \
  --shm-size=256m \
  --cap-add=SYS_ADMIN \
  -e OPENAI_API_KEY \
  -v golemcore-bot-data:/app/workspace \
  -p 8080:8080 \
  --restart unless-stopped \
  golemcore-bot:latest

docker logs -f golemcore-bot
```

Why the extra Docker flags?
- `--shm-size=256m` and `--cap-add=SYS_ADMIN` are needed for the **Browser tool** (Playwright/Chromium) in containers.
  See **[Configuration → Browser Tool](docs/CONFIGURATION.md#browser-tool)**.

### Telegram (optional)

```bash
docker run -d \
  --name golemcore-bot \
  --shm-size=256m \
  --cap-add=SYS_ADMIN \
  -e OPENAI_API_KEY=sk-proj-... \
  -e TELEGRAM_ENABLED=true \
  -e TELEGRAM_BOT_TOKEN=123456:ABC-DEF... \
  -e TELEGRAM_ALLOWED_USERS=123456789 \
  -v golemcore-bot-data:/app/workspace \
  -p 8080:8080 \
  --restart unless-stopped \
  golemcore-bot:latest
```

More options (Compose, production, systemd): **[Deployment](docs/DEPLOYMENT.md)**.

---

## Minimal configuration (README keeps only the essentials)

### Required: LLM provider (choose at least one)

| Variable | Purpose |
|---|---|
| `OPENAI_API_KEY` | OpenAI API key |
| `ANTHROPIC_API_KEY` | Anthropic API key |

### Telegram (if enabled)

| Variable | Purpose |
|---|---|
| `TELEGRAM_ENABLED` | Enable Telegram channel (`true/false`) |
| `TELEGRAM_BOT_TOKEN` | Bot token from @BotFather |
| `TELEGRAM_ALLOWED_USERS` | Comma-separated Telegram user IDs allowlist |

### Common feature toggles

| Variable | Purpose |
|---|---|
| `SKILL_MATCHER_ENABLED` | Enable hybrid routing (semantic + LLM classifier) |
| `MCP_ENABLED` | Enable MCP client (per-skill MCP servers) |
| `AUTO_MODE_ENABLED` | Enable autonomous goals/ticks |
| `RAG_ENABLED` | Enable LightRAG integration |

Full reference (90+ variables, examples, mail/voice/rate limits/security): **[docs/CONFIGURATION.md](docs/CONFIGURATION.md)**.

---

## Documentation (recommended path for the “platform” features)

1. **[Quick Start](docs/QUICKSTART.md)**
2. **[Skills](docs/SKILLS.md)** (SKILL.md format, variables, pipelines, MCP)
3. **[Model Routing](docs/MODEL_ROUTING.md)** (tiers, classifier, dynamic upgrade, context overflow handling)
4. **[Auto Mode](docs/AUTO_MODE.md)**
5. **[Memory](docs/MEMORY.md)** + **[RAG](docs/RAG.md)**
6. **[Deployment](docs/DEPLOYMENT.md)**

---

## Contributing / Support / License

- Contributing: see **[CONTRIBUTING.md](CONTRIBUTING.md)** (workflow, quality gates, tests).
- FAQ: **[FAQ.md](FAQ.md)**.
- Issues: https://github.com/alexk-dev/golemcore-bot/issues
- License: **Apache 2.0** — see **[LICENSE](LICENSE)** and **[NOTICE](NOTICE)**.

---

<div align="center">

**⭐ Star this repo if you find it useful!**

Made with ☕ and 🤖

</div>
