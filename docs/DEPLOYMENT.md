# Deployment Guide

Production deployment guide for GolemCore Bot.

## Deployment Options

1. **Docker** — Containerized deployment (recommended)
2. **Docker Compose** — Multi-container orchestration (recommended for production)
3. **JAR** — Standalone Java application (development/testing)
4. **Native app-image** — Local desktop/server bundle built with `jpackage`
5. **systemd** — Linux service for JAR deployment

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

### 2. Run directly

```bash
java -jar target/bot-<version>.jar
```

This uses the standard Spring Boot executable jar.

To override the HTTP port:

```bash
java -jar target/bot-<version>.jar --server.port=9090
# or
java -Dserver.port=9090 -jar target/bot-<version>.jar
```

### 3. Create systemd Service

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

### 4. Install

```bash
# Create user
sudo useradd -r -s /bin/false golemcore

# Create directories
sudo mkdir -p /opt/golemcore-bot
sudo chown golemcore:golemcore /opt/golemcore-bot

# Copy JAR
sudo cp target/bot-<version>.jar /opt/golemcore-bot/golemcore-bot.jar

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

## Native app-image Deployment

This mode is intended for **local machine installs** or lightweight server deployments where you want a bundled launcher instead of invoking `java -jar` manually.

### Build the archive

```bash
./mvnw clean package -DskipTests -DskipGitHooks=true
npx golemcore-bot-local-build-native-dist
```

Output:

```text
target/native-dist/golemcore-bot-<version>-<platform>-<arch>.tar.gz
```

### Extract and run

```bash
mkdir -p /opt/golemcore-bot-native
tar -xzf target/native-dist/golemcore-bot-<version>-<platform>-<arch>.tar.gz -C /opt/golemcore-bot-native
/opt/golemcore-bot-native/golemcore-bot/bin/golemcore-bot
```

### Inspect launcher help

The native launcher uses picocli, so operators can inspect its own documented parameters directly:

```bash
/opt/golemcore-bot-native/golemcore-bot/bin/golemcore-bot --help
```

Launcher-specific options include:

- `web`
- `web --port=<port>`
- `web --hostname=<address>`
- `--storage-path=<path>`
- `--updates-path=<path>`
- `--bundled-jar=<path>`
- `web -J=<jvm-option>` / `web --java-option=<jvm-option>`

The native package starts the Spring runtime with the `prod` profile by default.

### Override launcher-managed runtime parameters

The native launcher converts its own options into runtime JVM/system properties.

Examples:

```bash
/opt/golemcore-bot-native/golemcore-bot/bin/golemcore-bot web --port=8080 --hostname=0.0.0.0
/opt/golemcore-bot-native/golemcore-bot/bin/golemcore-bot web -J=-Xmx1g --port=9090
/opt/golemcore-bot-native/golemcore-bot/bin/golemcore-bot --storage-path=/srv/golemcore/workspace web --updates-path=/srv/golemcore/updates
```

### Forward Spring Boot arguments unchanged

Unknown arguments still flow to Spring Boot as application arguments, so existing runtime flags continue to work:

```bash
/opt/golemcore-bot-native/golemcore-bot/bin/golemcore-bot web --server.port=9090
/opt/golemcore-bot-native/golemcore-bot/bin/golemcore-bot web --spring.main.banner-mode=off
/opt/golemcore-bot-native/golemcore-bot/bin/golemcore-bot web -Dlogging.level.root=INFO
```

If you want to make the handoff explicit, use `--`:

```bash
/opt/golemcore-bot-native/golemcore-bot/bin/golemcore-bot web --port=9090 -- --spring.main.banner-mode=off
```

### How the launcher behaves

The native bundle wraps the strict CLI launcher entrypoint, which resolves runtime in this order:

1. staged update selected by `updates/current.txt`
2. bundled runtime jar from the app-image under `lib/runtime/`
3. legacy Jib/classpath fallback

That means the existing self-update flow still works for local bundles, now with a documented launcher CLI.

### Notes

- `jpackage` from JDK 25 is required to build the app-image.
- The generated archive is platform-specific.
- The release workflow attaches these native archives to GitHub Releases together with the executable JAR.
- The native launcher CLI is implemented with `picocli`.

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
