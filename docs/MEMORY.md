# Memory Guide

How the bot persists and recalls context for long-running coding sessions.

> **See also:** [RAG Guide](RAG.md), [Configuration Guide](CONFIGURATION.md), [Model Routing](MODEL_ROUTING.md#context-overflow-protection).

---

## Overview

Memory V2 is structured-only and works in two stages:

1. **Write stage**: finalized turn -> structured `TurnMemoryEvent` -> extracted `MemoryItem` records.
2. **Read stage**: query + runtime limits -> ranked `MemoryPack` -> injected under `# Memory`.

```text
System prompt
├── # Memory            <- MemoryPack (structured retrieval)
├── # Relevant Memory   <- RAG result (optional)
└── conversation        <- session messages
```

| Layer | Storage | Scope | Survives `/new`? | Writer |
|-------|---------|-------|------------------|--------|
| Session messages | `sessions/{id}.json` | Current conversation | No | `SessionService` |
| Structured memory | `memory/items/*.jsonl` | Cross-session durable memory | Yes | `MemoryPersistSystem` + `MemoryWriteService` |
| RAG | LightRAG graph/index | Broad historical retrieval | Yes | `RagIndexingSystem` |

---

## Write Path

`MemoryPersistSystem` (order=50):

1. Reads the latest user message.
2. Resolves assistant final text (`TurnOutcome` first, fallback `LLM_RESPONSE`).
3. Builds `TurnMemoryEvent` with message text, active skill, and tool outputs.
4. Calls `memoryComponent.persistTurnMemory(event)`.

`MemoryWriteService` then:

1. Extracts candidate `MemoryItem` entries.
2. Writes episodic JSONL records.
3. Optionally promotes high-confidence items to semantic/procedural stores.
4. Applies dedup and decay policy.

Persistence is fail-safe: failures are logged and do not block user responses.

---

## Read Path

`ContextBuildingSystem` (order=20):

1. Builds `MemoryQuery` from user text, active skill, and runtime top-k/budget limits.
2. Calls `memoryComponent.buildMemoryPack(query)`.
3. Stores pack diagnostics in context attributes.
4. Injects `pack.renderedContext` under `# Memory` when non-empty.

`MemoryRetrievalService` scoring combines:

- lexical relevance
- recency
- salience
- confidence
- type/skill boosts

`MemoryPromptPackService` enforces soft/hard prompt budgets and renders compact markdown sections.

---

## Storage Layout

```text
~/.golemcore/workspace/
├── memory/
│   └── items/
│       ├── episodic/
│       │   └── 2026-02-22.jsonl
│       ├── semantic.jsonl
│       └── procedural.jsonl
└── sessions/
    └── {channel}:{chatId}.json
```

---

## Configuration

Runtime config (`preferences/runtime-config.json`):

```json
{
  "memory": {
    "enabled": true,
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
    "codeAwareExtractionEnabled": true
  }
}
```

Field notes:

1. `softPromptBudgetTokens` / `maxPromptBudgetTokens`: memory prompt budget targets.
2. `*TopK`: layer-specific retrieval limits.
3. `promotion*`: controls semantic/procedural promotion.
4. `decay*`: staleness window for retrieval and cleanup.

---

## Pipeline Integration

| Order | System | Memory behavior |
|-------|--------|-----------------|
| 18 | `AutoCompactionSystem` | Compacts oversized session history |
| 20 | `ContextBuildingSystem` | Reads scored `MemoryPack` and injects `# Memory` |
| 50 | `MemoryPersistSystem` | Writes structured turn memory events |
| 55 | `RagIndexingSystem` | Indexes exchange for RAG |

Read path runs before write path in a turn, so the model sees pre-turn memory state.

---

## Key Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `MemoryComponent` | `domain.component` | Memory V2 interface for query/pack/persist/upsert |
| `MemoryService` | `domain.service` | Memory facade implementation |
| `MemoryWriteService` | `domain.service` | Event extraction, JSONL persistence, promotion |
| `MemoryRetrievalService` | `domain.service` | Candidate loading, ranking, top-k selection |
| `MemoryPromptPackService` | `domain.service` | Budgeted rendering into prompt context |
| `MemoryPersistSystem` | `domain.system` | Pipeline writer for structured turn events |
| `MemoryTool` | `tools` | Explicit memory operations for autonomous workflows |

---

## Debugging

Typical logs:

```text
[Context] Memory context: 1240 chars
[Context] Built context: 12 tools, memory=true, skills=true, systemPrompt=8420 chars
```

Quick checks:

1. Verify memory enabled and budgets in `preferences/runtime-config.json`.
2. Inspect `memory/items/` JSONL files for extracted records.
3. Confirm `ContextAttributes.MEMORY_PACK_DIAGNOSTICS` is populated in tests.
