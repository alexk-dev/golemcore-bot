# Quick Start Guide

Get GolemCore Bot running quickly (Docker or JAR) and configure it via the web dashboard.

## Prerequisites

- 🐳 Docker (recommended) OR ☕ Java 25+ with Maven 3.x
- 🧩 Optional for local dashboard frontend development: Node.js 20.19+ and npm 10+

## Method 1: Docker (Recommended)

### 1. Clone & Build

```bash
git clone https://github.com/alexk-dev/golemcore-bot.git
cd golemcore-bot
./mvnw compile jib:dockerBuild
```

### 2. Run (Persist Workspace + Sandbox)

```bash
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
  golemcore-bot:latest

docker logs -f golemcore-bot
```

Why the extra Docker flags?

- `--shm-size=256m` and `--cap-add=SYS_ADMIN` are needed for the browser tool (Playwright/Chromium) in containers.

### 3. Open Dashboard + Login

- Open `http://localhost:8080/dashboard`
- Username: `admin`
- On first start, the bot prints a temporary admin password in logs.

See: [Dashboard Guide](DASHBOARD.md)

Useful routes after login:

- Chat: `http://localhost:8080/dashboard/chat`
- Setup: `http://localhost:8080/dashboard/setup`
- Settings: `http://localhost:8080/dashboard/settings`
- Scheduler: `http://localhost:8080/dashboard/scheduler`
- Embedded IDE: `http://localhost:8080/dashboard/ide`
- Sessions: `http://localhost:8080/dashboard/sessions`
- Logs: `http://localhost:8080/dashboard/logs`

### 4. Configure LLM Providers

Open `http://localhost:8080/dashboard/setup` or go directly to `Settings -> LLM Providers`.

Set at least one provider API key and API type (stored in `preferences/runtime-config.json`).

Example runtime config snippet:

```json
{
  "llm": {
    "providers": {
      "openai": { "apiKey": "sk-proj-...", "apiType": "openai" },
      "anthropic": { "apiKey": "sk-ant-...", "apiType": "anthropic" },
      "google": { "apiKey": "AIza...", "apiType": "gemini" }
    }
  }
}
```

Then finish the routing flow:

1. `Settings -> Model Catalog` if you want to edit or discover model definitions from a provider API
2. `Settings -> Model Router` to assign the routing/tier models the bot will actually use

Optional next steps in Settings:

- `Plugin Marketplace` to install official integrations such as browser, search, mail, weather, LightRAG, and voice providers
- `Auto Mode` plus `/dashboard/scheduler` to create cron-based autonomous runs for goals/tasks

Optional next steps outside Settings:

- `Skills -> Marketplace` to install standalone skills or packs from a local `golemcore-skills` checkout, a direct `registry/` path, or the default remote repository

## Your First Conversation

Use the dashboard chat UI.

Slash commands work in chat (e.g. `/help`, `/skills`, `/status`).

## Telegram (Optional)

Enable Telegram in dashboard Settings (stored in runtime config):

- `telegram.enabled=true`
- `telegram.token=<bot token>`
- Add users to the allowlist (or use invite-only mode)

After saving settings, restart Telegram from the dashboard (or restart the container).

## Method 2: JAR (Alternative)

```bash
git clone https://github.com/alexk-dev/golemcore-bot.git
cd golemcore-bot
./mvnw clean package -DskipTests
java -jar target/golemcore-bot-0.1.0-SNAPSHOT.jar
```

Then open the dashboard at `http://localhost:8080/dashboard` and configure providers.

## Optional: Run Dashboard Frontend Locally

Use this mode when you work on dashboard UI code.

```bash
cd dashboard
npm install
npm run dev
```

Vite runs with API proxying to `http://localhost:8080` (backend should be running).

## Next Steps

- 📖 [Configuration Guide](CONFIGURATION.md)
- 🛠️ [Tools Guide](TOOLS.md)
- 🎯 [Skills Guide](SKILLS.md)
- 🧠 [Model Routing Guide](MODEL_ROUTING.md)
- 🚀 [Deployment Guide](DEPLOYMENT.md)
