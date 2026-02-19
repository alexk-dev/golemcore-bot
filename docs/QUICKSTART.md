# Quick Start Guide

Get GolemCore Bot running quickly (Docker or JAR) and configure it via the web dashboard.

## Prerequisites

- 🐳 Docker (recommended) OR ☕ Java 17+ with Maven 3.x

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

### 4. Configure LLM Providers

In the dashboard Settings, set at least one provider API key and API type (stored in `preferences/runtime-config.json`).

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

## Next Steps

- 📖 [Configuration Guide](CONFIGURATION.md)
- 🛠️ [Tools Guide](TOOLS.md)
- 🎯 [Skills Guide](SKILLS.md)
- 🧠 [Model Routing Guide](MODEL_ROUTING.md)
- 🚀 [Deployment Guide](DEPLOYMENT.md)
