# Quick Start Guide

Get GolemCore Bot running in 5 minutes.

## Prerequisites

- 🐳 Docker (recommended) OR ☕ Java 17+ with Maven 3.x
- 🔑 LLM API key (OpenAI or Anthropic)

## Installation

### Method 1: Docker (Recommended)

#### 1. Clone & Build Image

```bash
git clone https://github.com/alexk-dev/golemcore-bot.git
cd golemcore-bot

# Build Docker image with Jib (no Docker daemon needed)
./mvnw compile jib:dockerBuild
```

#### 2. Configure LLM Provider

Choose any provider:

```bash
# OpenAI
export OPENAI_API_KEY=sk-proj-your-key-here

# OR Anthropic
export ANTHROPIC_API_KEY=sk-ant-your-key-here

```

#### 3. Run Container

```bash
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

You'll see:
```
  ____              _____  ____        _
 |_  /_____   ____ |  _  ||  _ \  ___ | |_
  | |/ _  \ \ / /  / /_\ \ | |_) |/ _ \| __|
 /  | (_| |\ V /   |  _  | |  _ <| (_) | |_
/___|\__,_| \_/    |_| |_| |_| \_\\___/ \__|

:: GolemCore Bot ::        (v0.1.0-SNAPSHOT)
```

### Method 2: JAR (Alternative)

```bash
# Clone & Build
git clone https://github.com/alexk-dev/golemcore-bot.git
cd golemcore-bot
./mvnw clean package -DskipTests

# Configure LLM
export OPENAI_API_KEY=sk-proj-...  # or ANTHROPIC_API_KEY

# Run
java -jar target/golemcore-bot-0.1.0-SNAPSHOT.jar
```

## Your First Conversation

### CLI Mode (Default)

The bot runs in CLI mode by default. Interact via Docker logs:

```bash
# Attach to running container
docker attach golemcore-bot

# Or via logs
docker logs -f golemcore-bot
```

Type in terminal:
```
You: Hello!
Bot: Hello! I'm your AI assistant. How can I help you today?
```

### Telegram Mode

```bash
# Get bot token from @BotFather on Telegram

# Run with Telegram enabled
docker run -d \
  --name golemcore-bot \
  -e OPENAI_API_KEY=sk-... \
  -e TELEGRAM_ENABLED=true \
  -e TELEGRAM_BOT_TOKEN=123456:ABC-DEF... \
  -e TELEGRAM_ALLOWED_USERS=123456789 \
  -v golemcore-bot-data:/app/workspace \
  golemcore-bot:latest
```

Send `/help` to your bot on Telegram to see available commands.

## Essential Commands

### Basic Commands

| Command | Description |
|---------|-------------|
| `/help` | Show all available commands |
| `/skills` | List available skills |
| `/tools` | List enabled tools |
| `/status` | Show session info + usage stats |
| `/new` or `/reset` | Start new conversation |
| `/compact [N]` | Compact conversation history, keep last N messages (default: 10) |
| `/settings` | Change language (Telegram only) |

### Auto Mode Commands

Available when `AUTO_MODE_ENABLED=true`:

| Command | Description |
|---------|-------------|
| `/auto [on\|off]` | Toggle autonomous mode |
| `/goals` | List active goals |
| `/goal <description>` | Create a new goal |
| `/tasks` | List tasks for active goals |
| `/diary [N]` | Show last N diary entries (default: 10) |

## Enable Advanced Features

### Skill Routing (Semantic + LLM)

Enables intelligent model tier selection (fast/default/smart/coding) and skill matching. See [Model Routing Guide](MODEL_ROUTING.md) for how the 3-stage routing pipeline works.

```bash
docker run -d \
  -e OPENAI_API_KEY=sk-... \
  -e SKILL_MATCHER_ENABLED=true \
  -v golemcore-bot-data:/app/workspace \
  golemcore-bot:latest
```

### Web Search (Brave)

```bash
docker run -d \
  -e OPENAI_API_KEY=sk-... \
  -e BRAVE_SEARCH_ENABLED=true \
  -e BRAVE_SEARCH_API_KEY=your-brave-api-key \
  -v golemcore-bot-data:/app/workspace \
  golemcore-bot:latest
```

Get free API key: https://brave.com/search/api/ (2000 queries/month)

### Auto Mode (Autonomous Goals)

Enables the bot to work independently on long-term goals with periodic tick-based execution. See [Auto Mode Guide](AUTO_MODE.md) for the full tick cycle, goal management, and diary system.

```bash
docker run -d \
  -e OPENAI_API_KEY=sk-... \
  -e AUTO_MODE_ENABLED=true \
  -e AUTO_MODE_INTERVAL=15 \
  -v golemcore-bot-data:/app/workspace \
  golemcore-bot:latest
```

Create a goal via Telegram or CLI:
```
You: /goal "Research and summarize latest AI news"
```

Bot will work on it autonomously every 15 minutes.

## Docker Compose (All Features)

Create `docker-compose.yml`:

```yaml
version: '3.8'
services:
  golemcore-bot:
    image: golemcore-bot:latest
    container_name: golemcore-bot
    restart: unless-stopped
    environment:
      # LLM Provider
      OPENAI_API_KEY: ${OPENAI_API_KEY}

      # Telegram
      TELEGRAM_ENABLED: true
      TELEGRAM_BOT_TOKEN: ${TELEGRAM_BOT_TOKEN}
      TELEGRAM_ALLOWED_USERS: ${TELEGRAM_ALLOWED_USERS}

      # Advanced Features
      SKILL_MATCHER_ENABLED: true
      BRAVE_SEARCH_ENABLED: true
      BRAVE_SEARCH_API_KEY: ${BRAVE_SEARCH_API_KEY}
      AUTO_MODE_ENABLED: true
      AUTO_MODE_INTERVAL: 15
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

## Next Steps

- 📖 [Configuration Guide](CONFIGURATION.md) — All settings explained
- 🎯 [Skill System](SKILLS.md) — Create custom skills
- 🛠️ [Tools Guide](TOOLS.md) — Use built-in tools
- 🚀 [Deployment](DEPLOYMENT.md) — Production deployment

## Troubleshooting

### "LLM provider not configured"

✅ **Fix:** Set any LLM provider API key:
```bash
docker run -e OPENAI_API_KEY=sk-... golemcore-bot:latest
# OR
docker run -e ANTHROPIC_API_KEY=sk-ant-... golemcore-bot:latest
```

### "Rate limit exceeded"

✅ **Fix:** Adjust limits via environment variables:
```bash
docker run \
  -e OPENAI_API_KEY=sk-... \
  -e BOT_RATE_LIMIT_USER_REQUESTS_PER_MINUTE=10 \
  golemcore-bot:latest
```

### "Tool confirmation timeout"

✅ **Fix:** Increase timeout or disable confirmations:
```bash
docker run \
  -e OPENAI_API_KEY=sk-... \
  -e TOOL_CONFIRMATION_TIMEOUT=120 \
  golemcore-bot:latest

# OR disable confirmations (not recommended)
docker run \
  -e OPENAI_API_KEY=sk-... \
  -e TOOL_CONFIRMATION_ENABLED=false \
  golemcore-bot:latest
```

### Container won't start

✅ **Fix:** Check logs:
```bash
docker logs golemcore-bot
```

Common issues:
- Missing API key
- Port 8080 already in use (change with `-p 8081:8080`)
- Volume permission issues (use `docker volume` instead of bind mounts)

## Support

- 🐛 Report issues: [GitHub Issues](https://github.com/alexk-dev/golemcore-bot/issues)
- 💬 Ask questions: [GitHub Discussions](https://github.com/alexk-dev/golemcore-bot/discussions)
- 📚 Read docs: [Documentation](../docs/)
