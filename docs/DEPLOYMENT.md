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
  --shm-size=256m \
  --cap-add=SYS_ADMIN \
  -e STORAGE_PATH=/app/workspace \
  -e TOOLS_WORKSPACE=/app/sandbox \
  -v golemcore-bot-data:/app/workspace \
  -v golemcore-bot-sandbox:/app/sandbox \
  -p 8080:8080 \
  --restart unless-stopped \
  golemcore-bot:latest

# Configure LLM providers and Telegram in the dashboard:
# http://localhost:8080/dashboard
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
      # Persist workspace + sandbox
      STORAGE_PATH: /app/workspace
      TOOLS_WORKSPACE: /app/sandbox

      # Logging
      LOGGING_LEVEL_ME_GOLEMCORE_BOT: INFO
      LOGGING_LEVEL_DEV_LANGCHAIN4J: WARN

    volumes:
      - ./workspace:/app/workspace
      - ./sandbox:/app/sandbox
    ports:
      - "8080:8080"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/api/system/health"]
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
Environment="STORAGE_PATH=/opt/golemcore-bot/workspace"
Environment="TOOLS_WORKSPACE=/opt/golemcore-bot/sandbox"
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
# === STORAGE ===
STORAGE_PATH=/app/workspace
TOOLS_WORKSPACE=/app/sandbox

# === DASHBOARD ===
# You will configure API keys and most feature flags in:
#   workspace/preferences/runtime-config.json
# (recommended: use the dashboard UI)
DASHBOARD_ENABLED=true

# === LOGGING ===
LOGGING_LEVEL_ME_GOLEMCORE_BOT=INFO
LOGGING_LEVEL_DEV_LANGCHAIN4J=WARN
```

---

## Monitoring

### Health Check Endpoint

```bash
curl http://localhost:8080/api/system/health
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
- Lower `compaction.maxContextTokens` in `preferences/runtime-config.json`
- Lower `turn.maxLlmCalls` / `turn.maxToolExecutions` in `preferences/runtime-config.json`

### Slow Response Times

**Check:**
- LLM API latency
- Database queries
- Tool execution time

**Fix:**
- Use lighter models for balanced tier
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
- [ ] Restrict Telegram access (`telegram.allowedUsers` in runtime config)
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
