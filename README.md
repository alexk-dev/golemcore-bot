# GolemCore Bot

> Agent Platform for AI-Native Companies — build, run, extend, and inspect channel-connected agents with skills, plugins, MCP, memory, and Hive.

[![CI](https://github.com/alexk-dev/golemcore-bot/actions/workflows/docker-publish.yml/badge.svg)](https://github.com/alexk-dev/golemcore-bot/actions/workflows/docker-publish.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-passing-success.svg)](https://github.com/alexk-dev/golemcore-bot/actions)

---

## What it is

GolemCore Bot is the agent platform for AI-native companies building on the GolemCore ecosystem.

Use it to run channel-connected agents, extend them with skills, plugins, and MCP servers, and operate them with memory, delayed follow-ups, trace inspection, and Hive-connected control flows.

Use it in two ways:

1. **Run agents** — chat in the dashboard, Telegram, or webhook-driven flows, then inspect execution, memory, and trace output.
2. **Extend the runtime** — add skills, install plugins, connect MCP servers, configure routing tiers, and attach Hive-based control and inspection.

## Why it is different

- **Plugin-native runtime** — plugins can contribute tools, channels, voice providers, and RAG ingestion instead of living outside the agent runtime.
- **MCP-ready by design** — skills can launch MCP servers, and the runtime now includes MCP catalog and discovery flows.
- **Built for long-running work** — delayed follow-ups, progressive memory orchestration, and recovery paths help agents continue past a single turn.
- **Hive-connected control surface** — connect agents to Hive for control commands, lifecycle signals, inspection, and host-level coordination.
- **Inspectable execution** — traces, waterfall views, and exported snapshots make agent behavior easier to debug and operate.

---

## Quick start

### What you need

- Docker
- At least one LLM provider API key

### Run the published image

```bash
docker pull ghcr.io/alexk-dev/golemcore-bot:latest

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

# Health probe:
# curl http://localhost:8080/api/system/health
#
# Compatibility: Docker CMD overrides may still pass Spring Boot args directly:
# docker run ... ghcr.io/alexk-dev/golemcore-bot:latest --server.port=9090

# Open http://localhost:8080/dashboard
# On first start, check logs for the temporary admin password.
```

Open `http://localhost:8080/dashboard`, sign in with the temporary admin password from the logs, then configure your LLM providers in Settings.

Why the extra Docker flags?
- `--shm-size=256m` and `--cap-add=SYS_ADMIN` are needed for the **Browser tool** (Playwright/Chromium) in containers.
  See **[Configuration → Browser Tool](docs/CONFIGURATION.md#browser-tool)**.

### Optional next steps

- Enable Telegram in Settings for channel-based chat.
- Install plugins for browser, search, mail, weather, voice, or RAG-backed workflows.
- Connect MCP-backed skills and tool servers.
- Join Hive for control-plane coordination and inspection.

Need a local build, Compose setup, or production deployment path? See **[Quick Start](docs/QUICKSTART.md)** and **[Deployment](docs/DEPLOYMENT.md)**.

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
- a bundled Java runtime, so running the extracted app-image does not require a separately installed Java
- the regular self-updatable runtime jar under `lib/runtime/`
- a picocli-powered native launcher entrypoint with built-in help and launcher-only options
- launcher wiring that points to that bundled runtime jar first

So the startup order becomes:

1. staged update from `updates/current.txt`, unless the bundled runtime jar is newer
2. bundled runtime jar from the app-image
3. legacy Jib/classpath fallback

### Native launcher options

The native launcher uses picocli, so it has first-class help and a small set of launcher-specific flags.

Show help:

```bash
./golemcore-bot/bin/golemcore-bot --help
```

Common options:

- `web` — required command that starts the bundled Spring Boot runtime
- `web --port=<port>` — forwards `-Dserver.port=<port>` to the spawned runtime
- `web --hostname=<address>` — forwards `-Dserver.address=<address>` to the spawned runtime
- `--storage-path=<path>` / `web --storage-path=<path>` — forwards `-Dbot.storage.local.base-path=<path>`
- `--updates-path=<path>` / `web --updates-path=<path>` — forwards `-Dbot.update.updates-path=<path>`
- `--bundled-jar=<path>` — overrides the bundled runtime jar path
- `web -J=<jvm-option>` / `web --java-option=<jvm-option>` — forwards extra JVM options to the spawned runtime

The native package starts the Spring runtime with the `prod` profile by default.

Examples:

```bash
./golemcore-bot/bin/golemcore-bot web --port=8080 --hostname=0.0.0.0
./golemcore-bot/bin/golemcore-bot web -J=-Xmx1g --port=9090
./golemcore-bot/bin/golemcore-bot --storage-path=/srv/golemcore/workspace web --updates-path=/srv/golemcore/updates
```

### Spring runtime arguments still work

Unknown arguments are forwarded to Spring Boot unchanged, so existing application arguments continue to work:

```bash
./golemcore-bot/bin/golemcore-bot web --server.port=9090
./golemcore-bot/bin/golemcore-bot web --spring.main.banner-mode=off
./golemcore-bot/bin/golemcore-bot web -Dlogging.level.root=INFO
```

If you want to make the split explicit, you can also use `--` before Spring arguments:

```bash
./golemcore-bot/bin/golemcore-bot web --port=9090 -- --spring.main.banner-mode=off
```

### Why this matters

This keeps the existing self-update model based on:

- `updates/current.txt`
- `updates/jars/`

while also letting the bot start cleanly from a native local bundle with documented launcher parameters.

---

## First-run setup

1. Open the dashboard.
2. Add at least one LLM provider key in Settings.
3. Verify the storage and sandbox volumes are mounted so sessions, skills, and runtime config persist.
4. Optionally enable Telegram, install plugins, connect MCP-backed skills, or join Hive.

For runtime config details, storage layout, and browser or sandbox notes, see **[Configuration](docs/CONFIGURATION.md)**.

---

## Documentation

Documentation site: https://docs.golemcore.me/

The source docs below are kept in this repo for local and offline reference:

1. **[Quick Start](docs/QUICKSTART.md)**
2. **[Skills](docs/SKILLS.md)**
3. **[Tools](docs/TOOLS.md)**
4. **[MCP Integration](docs/MCP.md)**
5. **[Model Routing](docs/MODEL_ROUTING.md)**
6. **[Memory](docs/MEMORY.md)** + **[RAG](docs/RAG.md)**
7. **[Auto Mode](docs/AUTO_MODE.md)** + **[Delayed Actions Design](docs/DELAYED_ACTIONS.md)**
8. **[Hive Integration](docs/HIVE_INTEGRATION.md)**
9. **[Webhooks](docs/WEBHOOKS.md)**
10. **[Deployment](docs/DEPLOYMENT.md)**
11. **[Dashboard](docs/DASHBOARD.md)**

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
