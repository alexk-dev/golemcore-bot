# 🤖 GolemCore Bot

> **Autonomous AI Agent Framework** with intelligent skill routing, multi-LLM support, and Model Context Protocol (MCP) integration.

[![CI](https://github.com/alexk-dev/golemcore-bot/actions/workflows/docker-publish.yml/badge.svg)](https://github.com/alexk-dev/golemcore-bot/actions/workflows/docker-publish.yml)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

GolemCore Bot is a production-ready framework for building and deploying autonomous AI agents. It features a sophisticated processing pipeline, dynamic model selection, and a modular skill system.

---

## ✨ Key Highlights

- **🧠 Smart Orchestration** — Hybrid skill routing (Semantic + LLM) and automatic model escalation for coding tasks.
- **⚙️ Tool-Rich** — 12+ built-in tools (Shell, Browser, Filesystem, Email) + native **MCP support**.
- **🔄 Autonomous Mode** — Goal-driven execution with task planning and self-reflection (diary).
- **📚 Long-term Memory** — Knowledge Graph-based RAG (LightRAG) and smart context compaction.
- **🔒 Secure by Design** — Multi-layer sanitization, sandboxed execution, and manual tool confirmation.

---

## ⚡ Quick Start

### 1. Run with Docker (Recommended)
```bash
docker run -d \
  --name golemcore-bot \
  --shm-size=256m --cap-add=SYS_ADMIN \
  -e OPENAI_API_KEY=sk-proj-... \
  -v golemcore-bot-data:/app/workspace \
  -p 8080:8080 \
  golemcore-bot:latest
```

### 2. Basic Configuration
| Variable | Description |
|----------|-------------|
| `OPENAI_API_KEY` | OpenAI API key (GPT-4o, o1, o3) |
| `ANTHROPIC_API_KEY` | Anthropic API key (Claude 3.5/3.7) |
| `TELEGRAM_BOT_TOKEN` | (Optional) Token for Telegram interface |

---

## 🛠️ Tools & Skills

The bot comes with a powerful set of capabilities out of the box:
- **Web & Search:** Integrated Playwright browser and Brave Search.
- **Communication:** Full IMAP/SMTP email support.
- **Development:** Sandboxed Shell and Filesystem access.
- **Extensibility:** Add new tools via **MCP Servers** (GitHub, Slack, Google Drive) or custom Markdown skills.

---

## 📖 Deep Dive Documentation

For detailed guides, please refer to:
- 🚀 **[Quick Start Guide](docs/QUICKSTART.md)** — Detailed setup instructions.
- ⚙️ **[Configuration Reference](docs/CONFIGURATION.md)** — Full list of 90+ environment variables.
- 🧠 **[Model & Skill Routing](docs/MODEL_ROUTING.md)** — How the brain works.
- 🤖 **[Autonomous Mode](docs/AUTO_MODE.md)** — Goals and task management.
- 🏗️ **[Architecture Overview](docs/ARCHITECTURE.md)** — Internal systems and tech stack.

---

## 🤝 Contributing

We love contributions! Please see our **[Contributing Guide](CONTRIBUTING.md)** for development standards and PR process.

**⭐ Star this repo if you find it useful!**

---
[Apache License 2.0](LICENSE) | [alexk-dev](https://github.com/alexk-dev)
