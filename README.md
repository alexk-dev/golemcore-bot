# GolemCore Bot

> **Autonomous agent + framework** on Java — skill-driven behavior, MCP tool servers, tiered multi-LLM routing, and sandboxed tool execution.

[![CI](https://github.com/alexk-dev/golemcore-bot/actions/workflows/docker-publish.yml/badge.svg)](https://github.com/alexk-dev/golemcore-bot/actions/workflows/docker-publish.yml)
[![Java](https://img.shields.io/badge/Java-25+-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-passing-success.svg)](https://github.com/alexk-dev/golemcore-bot/actions)

---

## What it is (agent *and* framework)

Use it in two ways:

1. **As an autonomous agent** — run it and talk to it (CLI / Telegram). Enable Auto Mode to execute goals/tasks on a schedule.
2. **As a framework** — build your own agents by composing skills + tools + MCP integrations + routing/tier rules.

### What makes it useful

- **Skills as files** — Markdown `SKILL.md` with YAML frontmatter, variables, pipelines, and progressive loading.
- **MCP (Model Context Protocol)** — attach external tool servers via stdio (GitHub, Slack, custom tooling, LSP bridges, etc.).
- **Tiered multi-LLM routing** — configure separate models for different workloads (`balanced/smart/coding/deep`).
- **Tooling + sandbox** — built-in tools like filesystem/shell/browser/search/email with safety rails (confirmation for destructive actions).
- **Autonomy primitives** — Auto Mode (goals/tasks/diary) + memory and optional RAG.

If you want the deep-dive: start with **[Skills](docs/SKILLS.md)** and **[Model Routing](docs/MODEL_ROUTING.md)**.

---

## ⚡ Quick start (Docker, ~5–10 min)

### Prerequisites

- Docker (recommended) **or** Java 25+ / Maven 3.x
- At least one LLM API key (OpenAI or Anthropic)

### Docker (recommended)

```bash
# Pull published image
docker pull ghcr.io/alexk-dev/golemcore-bot:latest
# Optional: pin a specific version tag instead of latest, e.g. :0.10.0

# Run (persist workspace + sandbox)
docker run -d \
  --name golemcore-bot \
  --shm-size=256m \
  --cap-add=SYS_ADMIN \
  -e STORAGE_PATH=/app/workspace \
  -e TOOLS_WORKSPACE=/app/sandbox \
  -v golemcore-bot-data:/app/workspace \
  -v golemcore-bot-sandbox:/app/sandbox \
  -p 8080:8080 \
  --restart unless-stopped \
  ghcr.io/alexk-dev/golemcore-bot:latest

docker logs -f golemcore-bot

# Open dashboard
# http://localhost:8080/dashboard
# On first start, check logs for the temporary admin password.
# Optional: preset dashboard password via BOT_DASHBOARD_ADMIN_PASSWORD.
# Configure LLM provider API keys and API type in Settings (stored in preferences/runtime-config.json).
```

Why the extra Docker flags?
- `--shm-size=256m` and `--cap-add=SYS_ADMIN` are needed for the **Browser tool** (Playwright/Chromium) in containers.
  See **[Configuration → Browser Tool](docs/CONFIGURATION.md#browser-tool)**.

### Telegram (optional)

Enable Telegram from the dashboard (Settings → Telegram). The token and allowlist are stored in `preferences/runtime-config.json`.

More options (Compose, production, systemd): **[Deployment](docs/DEPLOYMENT.md)**.

---

## Local native app-image bundle (experimental)

Besides Docker and the plain executable JAR, the release workflow now also publishes a **local app-image bundle** for the current OS/architecture.

### Build it locally

```bash
./mvnw clean package -DskipTests -DskipGitHooks=true
npx golemcore-bot-local-build-native-dist
```

This produces an archive in:

```text
target/native-dist/golemcore-bot-<version>-<platform>-<arch>.tar.gz
```

### What is inside

The app-image contains:

- a small launcher application produced by `jpackage`
- the regular self-updatable runtime jar under `lib/runtime/`
- launcher wiring that points to that bundled runtime jar first

So the startup order becomes:

1. staged update from `updates/current.txt`
2. bundled runtime jar from the app-image
3. legacy Jib/classpath fallback

### Override startup parameters

The native launcher forwards JVM system properties to the actual runtime process, so you can override the server port and other Spring properties directly at launch time.

Examples:

```bash
./golemcore-bot/bin/golemcore-bot -Dserver.port=9090
./golemcore-bot/bin/golemcore-bot -Dserver.port=9090 --spring.profiles.active=prod
```

You can also still use Spring application arguments:

```bash
./golemcore-bot/bin/golemcore-bot --server.port=9090
```

### Why this matters

This keeps the existing self-update model based on:

- `updates/current.txt`
- `updates/jars/`

while also letting the bot start cleanly from a native local bundle.

---

## Minimal configuration (README keeps only the essentials)

### Required

- Configure at least one LLM provider API key and API type in `preferences/runtime-config.json` (recommended: use the dashboard).
- In Docker, set `STORAGE_PATH` to a mounted volume so configuration and sessions persist.

Full reference (runtime config fields, storage layout, browser/sandbox notes): **[docs/CONFIGURATION.md](docs/CONFIGURATION.md)**.

---

## Documentation (recommended path)

1. **[Quick Start](docs/QUICKSTART.md)**
2. **[Skills](docs/SKILLS.md)** (SKILL.md format, variables, pipelines, MCP)
3. **[Model Routing](docs/MODEL_ROUTING.md)** (tiers)
4. **[Auto Mode](docs/AUTO_MODE.md)**
5. **[Delayed Actions Design](docs/DELAYED_ACTIONS.md)**
6. **[Memory](docs/MEMORY.md)** + **[RAG](docs/RAG.md)**
7. **[Webhooks](docs/WEBHOOKS.md)** (HTTP triggers, custom mappings, callbacks)
8. **[Deployment](docs/DEPLOYMENT.md)**
9. **[Dashboard](docs/DASHBOARD.md)**

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
