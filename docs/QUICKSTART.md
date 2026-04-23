# Quick Start Guide

Get GolemCore Bot running quickly (Docker, JAR, or local native bundle) and configure it via the web dashboard.

## Prerequisites

- 🐳 Docker (recommended) OR ☕ Java 25+ with Maven 3.x
- 🧩 Optional for local dashboard frontend development: Node.js 20.19+ and npm 10+
- 🧰 For native app-image packaging: JDK 25 with `jpackage`

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

## Method 2: Executable JAR

```bash
git clone https://github.com/alexk-dev/golemcore-bot.git
cd golemcore-bot
./mvnw clean package -DskipTests
java -jar target/bot-<version>.jar
```

To start on a different port:

```bash
java -jar target/bot-<version>.jar --server.port=9090
# or
java -Dserver.port=9090 -jar target/bot-<version>.jar
```

Then open the dashboard at `http://localhost:9090/dashboard` and configure providers.

## Method 3: Local native app-image bundle

Use this when you want a local launcher bundle instead of running `java -jar` directly.

### 1. Build the bundle

```bash
git clone https://github.com/alexk-dev/golemcore-bot.git
cd golemcore-bot
./mvnw clean package -DskipTests -DskipGitHooks=true
npx golemcore-bot-local-build-native-dist
```

This creates:

```text
target/native-dist/golemcore-bot-<version>-<platform>-<arch>.tar.gz
```

### 2. Extract and run

```bash
mkdir -p /tmp/golemcore-bot-local
tar -xzf target/native-dist/golemcore-bot-<version>-<platform>-<arch>.tar.gz -C /tmp/golemcore-bot-local
/tmp/golemcore-bot-local/golemcore-bot/bin/golemcore-bot
```

### 3. Inspect launcher help

The native launcher is picocli-based, so it has first-class CLI help:

```bash
/tmp/golemcore-bot-local/golemcore-bot/bin/golemcore-bot --help
```

This documents launcher-only flags such as:

- `web`
- `web --port=<port>`
- `web --hostname=<address>`
- `--storage-path=<path>`
- `--updates-path=<path>`
- `--bundled-jar=<path>`
- `web -J=<jvm-option>` / `web --java-option=<jvm-option>`

The native package starts the Spring runtime with the `prod` profile by default.

### 4. Override launcher-managed runtime parameters

The launcher converts its own options into runtime JVM/system properties.

```bash
/tmp/golemcore-bot-local/golemcore-bot/bin/golemcore-bot web --port=8080 --hostname=0.0.0.0
/tmp/golemcore-bot-local/golemcore-bot/bin/golemcore-bot web -J=-Xmx1g --port=9090
/tmp/golemcore-bot-local/golemcore-bot/bin/golemcore-bot --storage-path=/srv/golemcore/workspace web --updates-path=/srv/golemcore/updates
```

### 5. Forward Spring Boot arguments unchanged

Unknown arguments are forwarded to Spring Boot, so standard application arguments still work:

```bash
/tmp/golemcore-bot-local/golemcore-bot/bin/golemcore-bot web --server.port=9090
/tmp/golemcore-bot-local/golemcore-bot/bin/golemcore-bot web --spring.main.banner-mode=off
/tmp/golemcore-bot-local/golemcore-bot/bin/golemcore-bot web -Dlogging.level.root=INFO
```

If you want to make the separation explicit, use `--`:

```bash
/tmp/golemcore-bot-local/golemcore-bot/bin/golemcore-bot web --port=9090 -- --spring.main.banner-mode=off
```

### 6. What the launcher does

The local launcher uses the strict CLI entrypoint and starts runtime in this order:

1. staged jar selected through `updates/current.txt`
2. bundled runtime jar from the app-image
3. legacy Jib/classpath fallback

So the existing self-update flow still works for native local bundles, now with documented launcher parameters.

## Your First Conversation

Use the dashboard chat UI.

Slash commands work in chat (e.g. `/help`, `/skills`, `/status`).

## Telegram (Optional)

Enable Telegram in dashboard Settings (stored in runtime config):

- `telegram.enabled=true`
- `telegram.token=<bot token>`
- Add users to the allowlist (or use invite-only mode)

After saving settings, restart Telegram from the dashboard (or restart the container).

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
