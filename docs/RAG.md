# RAG Integration Guide

How the bot uses LightRAG for long-term semantic memory via knowledge graphs.

> **See also:** [Configuration Guide](CONFIGURATION.md#rag) for runtime config fields, [Memory Guide](MEMORY.md) for short-term structured memory, [Deployment Guide](DEPLOYMENT.md) for production setup.

---

## Overview

RAG (Retrieval-Augmented Generation) gives the bot **long-term semantic memory** that survives conversation resets. Conversations are indexed into a knowledge graph, and relevant context is retrieved before each LLM call.

The bot uses [LightRAG](https://github.com/HKUDS/LightRAG) — a graph-based RAG system that builds a knowledge graph from indexed documents and supports entity-centric, community-summary, and hybrid retrieval modes.

```
                    INDEXING (write path)
                    ─────────────────────
User: "Deploy using Docker Compose with health checks"
Assistant: "Here's a docker-compose.yml with healthcheck..."
        |
        v
[RagIndexingSystem] (order=55, after MemoryPersistSystem)
        |
        +-- Is exchange trivial? (greeting, short) ── Yes ──> skip
        |
        +-- No: format document:
        |     "Date: 2026-02-07 14:30
        |      Skill: devops
        |      User: Deploy using Docker Compose with health checks
        |      Assistant: Here's a docker-compose.yml with healthcheck..."
        |
        +-- Fire-and-forget: POST /documents/text
        |
        v
[LightRAG Server]
        |
        +-- Chunk text → extract entities → build knowledge graph
        +-- Store embeddings for vector similarity search


                    RETRIEVAL (read path)
                    ─────────────────────
User: "How did we configure health checks before?"
        |
        v
[ContextBuildingSystem] (order=20)
        |
        +-- POST /query {"query": "How did we configure health checks", "mode": "hybrid"}
        |
        v
[LightRAG Server]
        |
        +-- Entity search + community summary search
        +-- Returns: "Previously configured Docker health checks with..."
        |
        v
System prompt includes:
        # Relevant Memory
        Previously configured Docker health checks with...
```

---

## Architecture

### Components

| Component | Package | Order | Purpose |
|-----------|---------|-------|---------|
| `RagPort` | `port.outbound` | — | Interface: `query()`, `index()`, `isAvailable()` |
| `LightRagAdapter` | `adapter.outbound.rag` | — | HTTP client to LightRAG REST API via OkHttp |
| `RagIndexingSystem` | `domain.system` | 55 | Indexes conversations after memory persistence |
| `ContextBuildingSystem` | `domain.system` | 20 | Retrieves RAG context before LLM call |

### RagPort Interface

```java
public interface RagPort {
    CompletableFuture<String> query(String query, String mode);
    CompletableFuture<Void> index(String content);
    boolean isAvailable();
}
```

All methods are non-blocking. `query()` returns an empty string if RAG is unavailable. `index()` is fire-and-forget. The interface is designed for graceful degradation — the bot works normally without RAG.

> **Source:** `RagPort.java`

---

## Indexing (Write Path)

`RagIndexingSystem` (order=55) runs **after** `MemoryPersistSystem` (order=50) and **before** `ResponseRoutingSystem` (order=60).

### What Gets Indexed

Each conversation exchange (user message + assistant response) is formatted as a document:

```
Date: 2026-02-07 14:30
Skill: coding-assistant
User: Write a Python function for CSV parsing
Assistant: Here's a function that handles CSV files with proper error handling...
```

The `Skill:` line is included only when an active skill was selected by the routing system.

### What Gets Filtered

Trivial exchanges are **not** indexed to avoid polluting the knowledge graph:

**Greeting filter** — messages matching these patterns (case-insensitive, after stripping trailing punctuation):

```
hi, hello, hey, bye, thanks, thank you, ok, okay, yes, no,
привет, пока, спасибо, да, нет
```

**Length filter** — exchanges where `user.length + assistant.length < indexMinLength` (default 50 chars).

**Empty response filter** — skipped if the LLM response is null or blank.

> **Source:** `RagIndexingSystem.java:69-85`

### Fire-and-Forget

Indexing is asynchronous and non-blocking. The response pipeline is never delayed by RAG indexing:

```java
ragPort.index(document).whenComplete((v, ex) -> {
    if (ex != null) {
        log.warn("[RagIndexing] Failed to index: {}", ex.getMessage());
    }
});
```

If indexing fails, the error is logged but the conversation continues normally.

---

## Retrieval (Read Path)

`ContextBuildingSystem` (order=20) queries RAG before building the system prompt.

### Query Flow

1. Extract the last user message text from context
2. Call `ragPort.query(userText, queryMode)` with the configured mode
3. If the result is non-empty, store it as `context.setAttribute("rag.context", ragContext)`
4. During system prompt construction, inject under `# Relevant Memory` header

```java
// ContextBuildingSystem.java — retrieval
String ragContext = ragPort.query(userQuery, properties.getRag().getQueryMode()).join();

// ContextBuildingSystem.java — injection into system prompt
sb.append("# Relevant Memory\n");
sb.append(ragContext);
```

### System Prompt Placement

RAG context is injected **after** short-term memory and **before** active skill content:

```
[Prompt sections: IDENTITY.md, RULES.md, ...]

# Memory                    ← short-term (Memory V2 pack)
...

# Relevant Memory           ← RAG context (knowledge graph retrieval)
...

# Active Skill: coding      ← skill content
...

# Available Tools            ← tool definitions
...
```

> **Source:** `ContextBuildingSystem.java:137-151, 197-202`

### Error Handling

If the RAG query fails (timeout, connection refused, 5xx), the error is logged and the system prompt is built without RAG context. The bot continues to work normally.

---

## Query Modes

LightRAG supports four query modes, configurable in `preferences/runtime-config.json` via `rag.queryMode`:

| Mode | Description | Best For |
|------|-------------|----------|
| `local` | Entity-centric search. Finds specific entities and their relationships in the knowledge graph. | Factual recall: "What port did we configure for Redis?" |
| `global` | Community-summary search. Uses high-level summaries of entity clusters. | Thematic queries: "What patterns do we use for error handling?" |
| `hybrid` | Combines local + global results. **(Recommended, default)** | General use — balances precision and breadth |
| `naive` | Simple vector similarity without knowledge graph. | Fallback if graph is empty or too small |

> **See:** [LightRAG documentation](https://github.com/HKUDS/LightRAG) for details on how each mode traverses the knowledge graph.

---

## LightRAG Server Setup

### Docker Compose (Recommended)

The project includes a ready-to-use Docker Compose configuration in `lightrag/`:

```yaml
# lightrag/docker-compose.yml
services:
  lightrag:
    container_name: lightrag
    image: ghcr.io/hkuds/lightrag:latest
    ports:
      - "${PORT:-9621}:9621"
    volumes:
      - ./data/rag_storage:/app/data/rag_storage
      - ./data/inputs:/app/data/inputs
    env_file:
      - .env
    restart: unless-stopped
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

Start it:

```bash
cd lightrag
# Create/edit .env (set your LightRAG LLM + embedding provider keys)
docker compose up -d
```

### LightRAG Configuration

Key settings in `lightrag/.env`:

```bash
# Server
HOST=0.0.0.0
PORT=9621

# LLM (for entity extraction and summarization)
LLM_BINDING=openai
LLM_MODEL=gpt-5.1
OPENAI_LLM_REASONING_EFFORT=low
LLM_BINDING_API_KEY=sk-...

# Embedding (for vector similarity)
EMBEDDING_BINDING=openai
EMBEDDING_MODEL=text-embedding-3-large
EMBEDDING_DIM=3072
EMBEDDING_BINDING_API_KEY=sk-...

# Storage (file-based, no external DB needed)
LIGHTRAG_KV_STORAGE=JsonKVStorage
LIGHTRAG_VECTOR_STORAGE=NanoVectorDBStorage
LIGHTRAG_GRAPH_STORAGE=NetworkXStorage

# Query tuning
COSINE_THRESHOLD=0.2
TOP_K=40
CHUNK_TOP_K=20
ENABLE_LLM_CACHE=true

# Document processing
CHUNK_SIZE=1200
CHUNK_OVERLAP_SIZE=100
SUMMARY_LANGUAGE=English

# Concurrency
MAX_ASYNC=4
MAX_PARALLEL_INSERT=2
```

### REST API Endpoints

The bot communicates with LightRAG via three endpoints:

| Endpoint | Method | Request Body | Response | Used By |
|----------|--------|-------------|----------|---------|
| `/query` | POST | `{"query": "...", "mode": "hybrid"}` | `{"response": "..."}` | `ContextBuildingSystem` |
| `/documents/text` | POST | `{"text": "...", "file_source": "conv_20260207_143000.txt"}` | `200 OK` | `RagIndexingSystem` |
| `/health` | GET | — | `200 OK` | Health checks (diagnostics) |

The `file_source` field uses a timestamp-based name (`conv_YYYYMMDD_HHmmss.txt`) to uniquely identify each indexed conversation exchange.

Optional authentication via `Authorization: Bearer <api-key>` header when `RAG_API_KEY` is set.

> **Source:** `LightRagAdapter.java`

---

## Configuration

Edit `preferences/runtime-config.json`:

```json
{
  "rag": {
    "enabled": false,
    "url": "http://localhost:9621",
    "apiKey": "",
    "queryMode": "hybrid",
    "timeoutSeconds": 10,
    "indexMinLength": 50
  }
}
```

The `LightRagAdapter` creates a dedicated `OkHttpClient` with the configured timeout, derived from the shared base client.

> **See:** [Configuration Guide — RAG](CONFIGURATION.md#rag) for a concise reference.

---

## How RAG Complements Short-Term Memory

The bot has two memory layers that work together:

| Layer | Mechanism | Scope | Survives `/new`? |
|-------|-----------|-------|------------------|
| **Short-term** ([Memory](MEMORY.md)) | Structured memory pack (`items/*.jsonl`) | Configurable top-k + budget | Yes (separate from session) |
| **Long-term** (RAG) | Knowledge graph via LightRAG | All indexed conversations | Yes (external storage) |

**Short-term memory** (`# Memory` section in prompt) provides:
- selected episodic events from recent work
- semantic project facts and constraints
- procedural patterns (failures/fixes/commands)

**RAG** (`# Relevant Memory` section in prompt) provides:
- Semantically relevant context from any past conversation
- Entity-relationship knowledge (e.g., "Redis was configured on port 6380")
- High-level patterns and summaries across conversations

Both are injected into the system prompt. Short-term memory appears first (more recent, more relevant), followed by RAG context (deeper, broader).

---

## Pipeline Integration

| Order | System | RAG Behavior |
|-------|--------|-------------|
| 20 | `ContextBuildingSystem` | **Queries RAG** — retrieves relevant context for the user's message |
| 30 | `ToolLoopExecutionSystem` | LLM call + tool execution; system prompt includes `# Relevant Memory` from RAG |
| 50 | `MemoryPersistSystem` | Persists to short-term memory (not RAG) |
| 55 | `RagIndexingSystem` | **Indexes to RAG** — formats and sends exchange to LightRAG |
| 60 | `ResponseRoutingSystem` | Sends response to user |

The read path (retrieval at order=20) runs **before** the write path (indexing at order=55), so the current exchange is not included in its own RAG retrieval — only previous conversations are available.

---

## Debugging

### Log Messages

```
[Context] RAG context: 450 chars
[RagIndexing] Indexed 380 chars
[RagIndexing] Skipping trivial exchange
```

On errors:

```
[RAG] Query failed: HTTP 503
[RAG] Query error: Connection refused
[RAG] Index failed: HTTP 500
[RagIndexing] Failed to index: Connection refused
```

### Health Check

`LightRagAdapter.isHealthy()` calls `GET /health` for diagnostics. Not called on every request — available for monitoring integrations.

### Verifying RAG Works

1. Start LightRAG: `cd lightrag && docker compose up -d`
2. Enable in bot: set `rag.enabled=true` and `rag.url=http://localhost:9621` in `preferences/runtime-config.json`
3. Have a conversation about a specific topic
4. Start a new session (`/new`)
5. Ask about the previous topic — the answer should reference past context under `# Relevant Memory`
