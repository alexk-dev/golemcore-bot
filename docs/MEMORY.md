# Memory Guide

How the bot persists and recalls conversation context across sessions.

> **See also:** [RAG Guide](RAG.md) for long-term semantic memory via knowledge graphs, [Configuration Guide](CONFIGURATION.md) for environment variables, [Model Routing](MODEL_ROUTING.md#context-overflow-protection) for context overflow and compaction.

---

## Overview

The bot has a multi-layered memory system that ensures continuity across conversations:

```
┌──────────────────────────────────────────────────────────────┐
│                      System Prompt                           │
│                                                              │
│  # Memory                    ← MemoryService                │
│  ## Long-term Memory         ← MEMORY.md (persistent)       │
│  ## Today's Notes            ← 2026-02-07.md (daily log)    │
│  ## Recent Context           ← last 7 days of notes         │
│                                                              │
│  # Relevant Memory           ← RAG (see RAG.md)             │
│                                                              │
│  [conversation messages]     ← SessionService (in-memory)   │
└──────────────────────────────────────────────────────────────┘
```

| Layer | Storage | Scope | Survives `/new`? | Written By |
|-------|---------|-------|------------------|------------|
| **Session messages** | `sessions/{id}.json` | Current conversation | No (cleared) | `SessionService` |
| **Daily notes** | `memory/YYYY-MM-DD.md` | Today + recent N days | Yes | `MemoryPersistSystem` |
| **Long-term memory** | `memory/MEMORY.md` | Permanent | Yes | LLM (via tools) or user |
| **RAG** | LightRAG knowledge graph | All past conversations | Yes | `RagIndexingSystem` |

This guide covers the first three layers. For RAG, see the [RAG Guide](RAG.md).

---

## Memory V2 (Structured + Legacy)

Current implementation runs in hybrid mode:

1. `MemoryPersistSystem` still appends legacy daily notes (`memory/YYYY-MM-DD.md`).
2. The same finalized turn is also persisted as structured `MemoryItem` records (`JSONL`) via `MemoryWriteService`.
3. `ContextBuildingSystem` asks memory for a scored `MemoryPack` first, and falls back to legacy text context only when needed.

Structured stores:

```text
memory/
├── MEMORY.md
├── YYYY-MM-DD.md
└── items/
    ├── episodic/YYYY-MM-DD.jsonl
    ├── semantic.jsonl
    └── procedural.jsonl
```

Key model objects:

1. `MemoryItem` — typed memory record with `layer`, `type`, `confidence`, `salience`, `tags`, `references`, `fingerprint`.
2. `MemoryQuery` — retrieval constraints (`topK`, prompt budgets, query text, active skill).
3. `MemoryPack` — selected items + diagnostics + rendered context.
4. `TurnMemoryEvent` — structured turn payload from the pipeline write stage.

Memory layer intent:

1. `EPISODIC` — recent turn events and short-horizon traces.
2. `SEMANTIC` — durable facts/preferences/constraints.
3. `PROCEDURAL` — stable execution knowledge (failures/fixes/commands).

---

## Session Messages

The most immediate form of memory — the full conversation history within the current session.

### AgentSession Model

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Format: `{channelType}:{chatId}` (e.g., `telegram:12345`) |
| `channelType` | string | Channel identifier (e.g., `telegram`) |
| `chatId` | string | Chat identifier within channel |
| `messages` | List\<Message\> | Full conversation history |
| `metadata` | Map | Session metadata |
| `state` | enum | `ACTIVE`, `PAUSED`, `TERMINATED` |
| `createdAt` | Instant | Session creation time |
| `updatedAt` | Instant | Last message time |

> **Source:** `AgentSession.java`

### SessionService

Manages session lifecycle with in-memory caching and filesystem persistence.

**Storage:** `sessions/{channelType}:{chatId}.json`

**Key operations:**

| Method | Description |
|--------|-------------|
| `getOrCreate(channelType, chatId)` | Lazy load from disk or create new session |
| `save(session)` | Persist to disk + update cache |
| `delete(sessionId)` | Remove from cache and disk |
| `clearMessages(sessionId)` | Wipe message history (used by `/new` command) |
| `getMessageCount(sessionId)` | Count messages in session |

Sessions are cached in a `ConcurrentHashMap` for fast access. Disk persistence happens on every `save()` call.

> **Source:** `SessionService.java`

### Compaction

When conversation history grows too large for the model's context window, it can be compacted:

**Manual compaction** — `/compact [N]` command:
1. `CompactionService.summarize()` sends old messages to the default (balanced) LLM model
2. LLM produces a concise summary
3. `SessionService.compactWithSummary()` replaces old messages with a summary message + last N messages

**Automatic compaction** — `AutoCompactionSystem` (order=18):
1. Estimates token count: `sum(message.length) / 3.5 + 8000` (system prompt overhead)
2. Compares against model's `maxInputTokens * 0.8` from `models.json`
3. If exceeded, triggers the same compaction flow

**Summary message format:**
```
[Conversation summary]
User discussed deploying a Spring Boot app with Docker. Key decisions:
- Using Jib for image builds (multi-stage, ~180MB)
- Docker Compose for orchestration with health checks
- LightRAG container alongside the main bot
```

The summary is stored as a `system` role message at the beginning of the conversation history.

**CompactionService details:**
- Model: balanced tier (low reasoning, temperature 0.3)
- Max output: 500 tokens
- Timeout: 15 seconds
- Filters out tool result messages for cleaner summaries
- Truncates individual messages to 300 chars before summarization
- Falls back to simple truncation (drop oldest, keep last N) if LLM unavailable

> **Source:** `CompactionService.java`, `SessionService.java`, `AutoCompactionSystem.java`
>
> **See:** [Model Routing — Large Input Truncation](MODEL_ROUTING.md#large-input-truncation) for the full 3-layer context overflow protection.

---

## Daily Notes

`MemoryPersistSystem` (order=50) automatically logs each conversation exchange as a timestamped note in a daily file.

### How It Works

After every LLM response, the system:

1. Extracts the last user message and the LLM response
2. Truncates: user to 200 chars, assistant to 300 chars
3. Replaces newlines with spaces
4. Formats as: `[HH:mm] User: {text} | Assistant: {text}`
5. Appends to today's file: `memory/YYYY-MM-DD.md`

**Example daily file** (`memory/2026-02-07.md`):

```
[14:15] User: How do I configure Docker health checks? | Assistant: Add a healthcheck section to your docker-compose.yml with test, interval, and timeout...
[14:30] User: What about restart policies? | Assistant: Use restart: unless-stopped for production. This restarts the container unless explicitly stopped...
[14:45] User: Show me the full compose file | Assistant: Here's a complete docker-compose.yml with health checks, restart policies, and volume mounts...
```

> **Source:** `MemoryPersistSystem.java`

### Error Handling

If persistence fails (e.g., disk full, permission denied), the error is logged as a warning and the response pipeline continues. Memory persistence is fail-safe — it never blocks the user from getting a response.

---

## Long-Term Memory

`MEMORY.md` is a persistent file that survives conversation resets and contains curated knowledge the LLM should always remember.

### Storage

Path: `memory/MEMORY.md` (within workspace: `~/.golemcore/workspace/memory/MEMORY.md`)

### How It's Used

On every request, `ContextBuildingSystem` loads `MEMORY.md` content and includes it under `## Long-term Memory` in the system prompt. This gives the LLM persistent knowledge across all sessions.

### How It's Written

`MEMORY.md` can be updated in two ways:

1. **By the LLM** — using the `filesystem` tool to write to the memory directory (if the skill or prompt instructs it to maintain persistent notes)
2. **By the user** — manually editing the file at `~/.golemcore/workspace/memory/MEMORY.md`

The `MemoryComponent` interface provides `writeLongTerm(content)` and `readLongTerm()` methods.

---

## Memory Context Format

`MemoryService.getMemoryContext()` calls `Memory.toContext()` which formats all memory layers into markdown for the system prompt:

```markdown
## Long-term Memory
User prefers concise responses.
Project uses Spring Boot 4.0.2 with Java 25.
Database is PostgreSQL on port 5432.

## Today's Notes
[14:15] User: How do I configure health checks? | Assistant: Add healthcheck section...
[14:30] User: What about restart? | Assistant: Use restart: unless-stopped...

## Recent Context
### 2026-02-06
[10:00] User: Set up CI/CD pipeline | Assistant: Created GitHub Actions workflow...
[10:30] User: Add test stage | Assistant: Added test job with mvn test...

### 2026-02-05
[16:00] User: Initialize project | Assistant: Created Spring Boot project with...
```

**Sections included:**
- `## Long-term Memory` — only if `MEMORY.md` has content
- `## Today's Notes` — only if today's file exists and has content
- `## Recent Context` — includes the last N days (default 7), with each day as a `###` subsection. Only days with content are shown.

If all sections are empty, `toContext()` returns an empty string and the `# Memory` header is not included in the system prompt.

> **Source:** `Memory.java`, `MemoryService.java`

In Memory V2 mode, prompt assembly prefers `memoryComponent.buildMemoryPack(MemoryQuery)`:

1. retrieval ranks structured items across `EPISODIC` / `SEMANTIC` / `PROCEDURAL`;
2. `MemoryPromptPackService` applies soft/max token budgets;
3. rendered sections are injected under `# Memory`;
4. legacy markdown context is used as fallback (and can be disabled via config).

---

## Storage Layout

```
~/.golemcore/workspace/
├── memory/
│   ├── MEMORY.md              # Long-term persistent memory
│   ├── 2026-02-07.md          # Today's notes
│   ├── 2026-02-06.md          # Yesterday's notes
│   ├── 2026-02-05.md          # ...
│   └── ...                    # Up to recent-days files loaded
│
└── sessions/
    ├── telegram:12345.json    # Session with messages, metadata
    └── ...
```

---

## Configuration

Runtime config (stored in `preferences/runtime-config.json`):

```json
{
  "memory": {
    "enabled": true,
    "recentDays": 7,
    "softPromptBudgetTokens": 1800,
    "maxPromptBudgetTokens": 3500,
    "workingTopK": 6,
    "episodicTopK": 8,
    "semanticTopK": 6,
    "proceduralTopK": 4,
    "promotionEnabled": true,
    "promotionMinConfidence": 0.75,
    "decayEnabled": true,
    "decayDays": 30,
    "codeAwareExtractionEnabled": true,
    "legacyDailyNotesEnabled": true
  },
  "compaction": {
    "enabled": true,
    "maxContextTokens": 50000,
    "keepLastMessages": 20
  }
}
```

Storage paths/directories are Spring properties (see [Configuration Guide](CONFIGURATION.md)).

---

## Pipeline Integration

| Order | System | Memory Behavior |
|-------|--------|----------------|
| 18 | `AutoCompactionSystem` | Compacts session messages if context too large |
| 20 | `ContextBuildingSystem` | **Reads** scored `MemoryPack` (structured) and injects into system prompt, with legacy fallback |
| 30 | `ToolLoopExecutionSystem` | LLM call + tool execution; system prompt includes `# Memory` section; LLM can write to `MEMORY.md` via filesystem tool |
| 50 | `MemoryPersistSystem` | **Writes** both legacy daily note and structured turn event |
| 55 | `RagIndexingSystem` | Indexes exchange to LightRAG (separate from this memory system) |

The read path (order=20) runs **before** the write path (order=50), so the LLM sees the memory state **before** the current exchange is recorded.

---

## Architecture: Key Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `Memory` | `domain.model` | Data model: longTermContent, todayNotes, recentDays; `toContext()` formatting |
| `MemoryComponent` | `domain.component` | Interface: legacy text memory + Memory V2 APIs (`buildMemoryPack`, structured writes/queries) |
| `MemoryService` | `domain.service` | Facade implementation: legacy compatibility + structured retrieval/pack assembly |
| `MemoryWriteService` | `domain.service` | Structured JSONL persistence, extraction, dedup, promotion/decay |
| `MemoryRetrievalService` | `domain.service` | Hybrid scoring + layer top-k selection |
| `MemoryPromptPackService` | `domain.service` | Token-budgeted rendering of memory packs |
| `MemoryPersistSystem` | `domain.system` | Order=50: dual-write (legacy daily note + structured turn event) |
| `MemoryTool` | `tools` | Explicit memory ops for autonomous flows: add/search/update/promote/forget |
| `SessionService` | `domain.service` | Session CRUD, message history, compaction operations |
| `CompactionService` | `domain.service` | LLM-powered summarization for context overflow |
| `AutoCompactionSystem` | `domain.system` | Order=18: automatic compaction when context exceeds threshold |
| `AgentSession` | `domain.model` | Session model: id, messages, state, timestamps |

---

## Debugging

### Log Messages

```
[Context] Memory context: 1250 chars
[MemoryPersist] Appended memory entry (85 chars)
[AutoCompact] Context too large: ~150000 tokens (threshold 102400), 45 messages. Compacting...
[AutoCompact] Compacted with LLM summary: removed 35 messages, kept 10
```

### Useful Commands

| Command | What It Shows |
|---------|--------------|
| `/status` | Session message count, memory state |
| `/compact [N]` | Manually compact conversation, keep last N messages |
| `/stop` | Interrupt the current run (messages will be queued until your next message) |
| `/new` | Clear session messages (memory files preserved) |
