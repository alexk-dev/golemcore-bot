# Deployment Guide

Production deployment guide for GolemCore Bot.

## Deployment Options

1. **Docker** — Containerized deployment (recommended)
2. **Docker Compose** — Multi-container orchestration (recommended for production)
3. **JAR** — Standalone Java application (development/testing)
4. **systemd** — Linux service for JAR deployment

---

## Docker Deployment

### Build Image (Jib - No Docker Daemon Required)

```bash
# Build to local Docker daemon
./mvnw compile jib:dockerBuild

# Or push directly to registry (no Docker needed)
./mvnw compile jib:build -Djib.to.image=ghcr.io/alexk-dev/golemcore-bot:latest
```

### Run Container

```bash
docker run -d \
  --name golemcore-bot \
  -e OPENAI_API_KEY=sk-proj-... \
  -e TELEGRAM_ENABLED=true \
  -e TELEGRAM_BOT_TOKEN=... \
  -e TELEGRAM_ALLOWED_USERS=... \
  -v golemcore-bot-data:/app/workspace \
  -p 8080:8080 \
  --restart unless-stopped \
  golemcore-bot:latest

# Or with Anthropic
docker run -d \
  --name golemcore-bot \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  -e TELEGRAM_ENABLED=true \
  -e TELEGRAM_BOT_TOKEN=... \
  -v golemcore-bot-data:/app/workspace \
  -p 8080:8080 \
  --restart unless-stopped \
  golemcore-bot:latest
```

### Docker Compose

`docker-compose.yml`:

```yaml
version: '3.8'

services:
  golemcore-bot:
    image: golemcore-bot:latest
    build:
      context: .
      dockerfile: Dockerfile
    container_name: golemcore-bot
    restart: unless-stopped
    environment:
      # LLM Provider (set at least one)
      OPENAI_API_KEY: ${OPENAI_API_KEY:-}
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY:-}

      # Telegram
      TELEGRAM_ENABLED: ${TELEGRAM_ENABLED:-false}
      TELEGRAM_BOT_TOKEN: ${TELEGRAM_BOT_TOKEN:-}
      TELEGRAM_ALLOWED_USERS: ${TELEGRAM_ALLOWED_USERS:-}

      # Model routing (configure based on your provider)
      BOT_ROUTER_FAST_MODEL: ${BOT_ROUTER_FAST_MODEL:-openai/gpt-5.1}
      BOT_ROUTER_CODING_MODEL: ${BOT_ROUTER_CODING_MODEL:-openai/gpt-5.2}

      # Features
      SKILL_MATCHER_ENABLED: ${SKILL_MATCHER_ENABLED:-false}
      RAG_ENABLED: ${RAG_ENABLED:-false}
      AUTO_MODE_ENABLED: ${AUTO_MODE_ENABLED:-false}

      # Security
      TOOL_CONFIRMATION_ENABLED: ${TOOL_CONFIRMATION_ENABLED:-true}

      # Logging
      LOGGING_LEVEL_ME_GOLEMCORE_BOT: INFO
      LOGGING_LEVEL_DEV_LANGCHAIN4J: WARN

    volumes:
      - ./workspace:/app/workspace
      - ./sandbox:/app/sandbox
    ports:
      - "8080:8080"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 40s

  # Optional: LightRAG for long-term memory
  lightrag:
    image: lightrag/lightrag:latest
    container_name: lightrag
    restart: unless-stopped
    ports:
      - "9621:9621"
    volumes:
      - lightrag-data:/app/data
    environment:
      RAG_API_KEY: ${RAG_API_KEY:-}

volumes:
  golemcore-bot-data:
  lightrag-data:
```

Run:
```bash
docker-compose up -d
```

---

## JAR Deployment

### 1. Build

```bash
./mvnw clean package -DskipTests
```

### 2. Create systemd Service

`/etc/systemd/system/golemcore-bot.service`:

```ini
[Unit]
Description=GolemCore Bot
After=network.target

[Service]
Type=simple
User=golemcore
WorkingDirectory=/opt/golemcore-bot
ExecStart=/usr/bin/java -Xmx2G -jar /opt/golemcore-bot/golemcore-bot.jar
Restart=on-failure
RestartSec=10s
StandardOutput=journal
StandardError=journal
SyslogIdentifier=golemcore-bot

# Environment
Environment="OPENAI_API_KEY=sk-proj-..."
Environment="TELEGRAM_ENABLED=true"
Environment="TELEGRAM_BOT_TOKEN=..."
EnvironmentFile=/opt/golemcore-bot/.env

[Install]
WantedBy=multi-user.target
```

### 3. Install

```bash
# Create user
sudo useradd -r -s /bin/false golemcore

# Create directories
sudo mkdir -p /opt/golemcore-bot
sudo chown golemcore:golemcore /opt/golemcore-bot

# Copy JAR
sudo cp target/golemcore-bot-0.1.0-SNAPSHOT.jar /opt/golemcore-bot/golemcore-bot.jar

# Create .env file
sudo nano /opt/golemcore-bot/.env

# Enable and start
sudo systemctl daemon-reload
sudo systemctl enable golemcore-bot
sudo systemctl start golemcore-bot

# Check status
sudo systemctl status golemcore-bot
sudo journalctl -u golemcore-bot -f
```

---


## Environment Variables (Production)

`.env` file:

```bash
# === LLM PROVIDER (Required: Set At Least One) ===
OPENAI_API_KEY=sk-proj-...          # OpenAI (GPT-5.x, o1, o3)
ANTHROPIC_API_KEY=sk-ant-...        # Anthropic (Claude)

# === TELEGRAM ===
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=123456:ABC-DEF...
TELEGRAM_ALLOWED_USERS=123456789,987654321

# === SECURITY (STRICT) ===
BOT_SECURITY_SANITIZE_INPUT=true
BOT_SECURITY_DETECT_PROMPT_INJECTION=true
BOT_SECURITY_DETECT_COMMAND_INJECTION=true
TOOL_CONFIRMATION_ENABLED=true
TOOL_CONFIRMATION_TIMEOUT=60

# === RATE LIMITING ===
BOT_RATE_LIMIT_ENABLED=true
BOT_RATE_LIMIT_USER_REQUESTS_PER_MINUTE=10
BOT_RATE_LIMIT_USER_REQUESTS_PER_HOUR=50
BOT_RATE_LIMIT_USER_REQUESTS_PER_DAY=200

# === MODEL ROUTING (see docs/MODEL_ROUTING.md for details) ===
BOT_ROUTER_FAST_MODEL=openai/gpt-5.1
BOT_ROUTER_FAST_MODEL_REASONING=low
BOT_ROUTER_DEFAULT_MODEL=openai/gpt-5.1
BOT_ROUTER_DEFAULT_MODEL_REASONING=medium
BOT_ROUTER_CODING_MODEL=openai/gpt-5.2
BOT_ROUTER_CODING_MODEL_REASONING=medium
BOT_ROUTER_DYNAMIC_TIER_ENABLED=true

# === FEATURES ===
SKILL_MATCHER_ENABLED=false  # Enable if you use custom skills
RAG_ENABLED=false            # Enable if LightRAG running
AUTO_MODE_ENABLED=false      # Enable for autonomous mode
MCP_ENABLED=true             # Enable for MCP integrations

# === STORAGE ===
STORAGE_PATH=/app/workspace
TOOLS_WORKSPACE=/app/sandbox

# === LOGGING ===
LOGGING_LEVEL_ME_GOLEMCORE_BOT=INFO
LOGGING_LEVEL_DEV_LANGCHAIN4J=WARN

# === PERFORMANCE ===
BOT_AGENT_MAX_ITERATIONS=20
BOT_AUTO_COMPACT_ENABLED=true
BOT_AUTO_COMPACT_MAX_CONTEXT_TOKENS=50000
```

---

## Monitoring

### Health Check Endpoint

```bash
curl http://localhost:8080/actuator/health
```

Response:
```json
{
  "status": "UP"
}
```

### Metrics (Prometheus)

Add to `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

Enable:
```properties
management.endpoints.web.exposure.include=health,metrics,prometheus
management.metrics.export.prometheus.enabled=true
```

Scrape:
```bash
curl http://localhost:8080/actuator/prometheus
```

### Logging

**Structured JSON logging** (add to dependencies):

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

`logback-spring.xml`:

```xml
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>
    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```

**Log aggregation:**
- Elasticsearch + Kibana
- Grafana Loki
- CloudWatch (AWS)
- Stackdriver (GCP)

---

## Backup & Recovery

### Backup Workspace

```bash
# Docker volume
docker run --rm -v golemcore-bot-data:/data -v $(pwd):/backup \
  busybox tar czf /backup/backup-$(date +%Y%m%d).tar.gz /data

# Local
tar czf backup-$(date +%Y%m%d).tar.gz ~/.golemcore/workspace
```

### Restore

```bash
# Docker volume
docker run --rm -v golemcore-bot-data:/data -v $(pwd):/backup \
  busybox tar xzf /backup/backup-20260207.tar.gz -C /

# Local
tar xzf backup-20260207.tar.gz -C ~/
```

---

## Security Hardening

### 1. Use Non-Root User

```dockerfile
FROM eclipse-temurin:17-jre-jammy
RUN useradd -r -s /bin/false golemcore
USER golemcore
```

### 2. Read-Only Filesystem

```bash
docker run --read-only \
  --tmpfs /tmp \
  -v workspace:/app/workspace \
  golemcore-bot:latest
```

### 3. Secrets Management

Use secrets manager:
- AWS Secrets Manager
- HashiCorp Vault
- Docker Secrets

---

## Troubleshooting

### High Memory Usage

**Check:**
```bash
docker stats golemcore-bot
```

**Fix:**
- Lower `BOT_AUTO_COMPACT_MAX_CONTEXT_TOKENS`
- Increase compaction frequency
- Lower `BOT_AGENT_MAX_ITERATIONS`

### Slow Response Times

**Check:**
- LLM API latency
- Database queries
- Tool execution time

**Fix:**
- Use faster models for fast tier
- Enable caching (`SKILL_MATCHER_CACHE_ENABLED=true`)
- Disable RAG if not needed

### OOM Errors

**Fix:**
```bash
# Increase heap size
java -Xmx4G -jar golemcore-bot.jar

# Or in Docker
docker run --memory=4g golemcore-bot:latest
```

---

## Production Checklist

- [ ] Set `LOGGING_LEVEL_ME_GOLEMCORE_BOT=INFO` (not DEBUG)
- [ ] Set `TELEGRAM_ALLOWED_USERS` to restrict access
- [ ] Set conservative rate limits
- [ ] Enable tool confirmations
- [ ] Rotate API keys regularly
- [ ] Set up monitoring (Prometheus + Grafana)
- [ ] Configure log aggregation
- [ ] Set up automated backups
- [ ] Test disaster recovery
- [ ] Document runbooks
- [ ] Set up alerting (PagerDuty, OpsGenie)

---

## See Also

- [Quick Start](QUICKSTART.md)
- [Configuration](CONFIGURATION.md)
- [FAQ](../FAQ.md)
