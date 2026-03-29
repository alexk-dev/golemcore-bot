# Memory Guide

How the bot persists and recalls context for long-running coding sessions.

> **See also:** [RAG Guide](RAG.md), [Configuration Guide](CONFIGURATION.md), [Model Routing](MODEL_ROUTING.md#context-overflow-protection).

---

## Overview

Memory V2 is structured-only and works in two stages:

1. **Write stage**: finalized turn -> structured `TurnMemoryEvent` -> extracted `MemoryItem` records.
2. **Read stage**: query + runtime limits -> ranked `MemoryPack` -> injected under `# Memory`.

The read side now uses **progressive disclosure**:

- the model first gets a compact memory view
- detailed snippets are only shown when the policy says they are worth the prompt budget
- the agent can still expand memory on demand through memory tools when needed

In practice this means memory is now **summary-first**, not "dump everything into the prompt".

```text
System prompt
├── # Memory            <- MemoryPack (structured retrieval)
├── # Relevant Memory   <- RAG result (optional)
└── conversation        <- session messages
```

| Layer | Storage | Scope | Survives `/new`? | Writer |
|-------|---------|-------|------------------|--------|
| Session messages | `sessions/{id}.json` | Current conversation | No | `SessionService` |
| Structured memory | `memory/items/*.jsonl` | Cross-session durable memory | Yes | `MemoryPersistSystem` + `MemoryLifecycleOrchestrator` |
| RAG | LightRAG graph/index | Broad historical retrieval | Yes | `RagIndexingSystem` |

---

## Write Path

`MemoryPersistSystem` (order=50):

1. Reads the latest user message.
2. Resolves assistant final text (`TurnOutcome` first, fallback `LLM_RESPONSE`).
3. Builds `TurnMemoryEvent` with message text, active skill, and tool outputs.
4. Calls `memoryComponent.persistTurnMemory(event)`.

`MemoryLifecycleOrchestrator` then:

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

Read flow at a high level:

1. Collect candidates from working / episodic / semantic / procedural memory.
2. Run first-pass scoring.
3. Run deterministic **re-ranking**.
4. Apply layer limits and dedup.
5. Apply **progressive disclosure** policy.
6. Render the final prompt pack.

First-pass scoring combines:

- lexical relevance
- recency
- salience
- confidence
- type/skill boosts

Second-pass re-ranking can then further prefer:

- exact title matches
- exact content phrase matches
- titles that cover the whole query intent
- memories tagged for the active skill

`MemoryPromptPackService` then enforces prompt budgets and renders the final markdown sections.

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
    "retrievalLookbackDays": 21,
    "codeAwareExtractionEnabled": true,
    "disclosure": {
      "mode": "summary",
      "promptStyle": "balanced",
      "toolExpansionEnabled": true,
      "disclosureHintsEnabled": true,
      "detailMinScore": 0.80
    },
    "reranking": {
      "enabled": true,
      "profile": "balanced"
    },
    "diagnostics": {
      "verbosity": "basic"
    }
  }
}
```

### Core Recall

- `enabled`: turns structured memory on or off completely.
- `softPromptBudgetTokens`: target budget for memory. The system tries to stay under this first.
- `maxPromptBudgetTokens`: hard ceiling for memory content in the prompt. Use this to stop memory from crowding out the conversation.
- `workingTopK`, `episodicTopK`, `semanticTopK`, `proceduralTopK`: per-layer recall limits before budget trimming.
- `retrievalLookbackDays`: how many recent episodic day-files are scanned.

Practical rule:
Higher `*TopK` values increase recall but also add noise. Lower `retrievalLookbackDays` makes memory feel more current; higher values help on long-running tasks.

### Write and Promotion

- `promotionEnabled`: allows strong episodic memories to be promoted into longer-lived semantic or procedural memory.
- `promotionMinConfidence`: promotion threshold. Raise it if promotion feels noisy. Lower it if useful knowledge is not sticking.
- `decayEnabled`: enables age-based cleanup of stale memory.
- `decayDays`: main retention window for decayed items.
- `codeAwareExtractionEnabled`: helps extraction pay more attention to code changes, errors, fixes, commands, and implementation details.

### Progressive Disclosure

This is the main policy layer that controls **how much memory the model sees up front**.

- `disclosure.mode`: main disclosure strategy.

Supported values:

1. `index`
   Minimal prompt footprint. The model gets a tiny memory index and usually needs tools for detail.
2. `summary`
   Recommended default. The model gets concise summaries without too many raw snippets.
3. `selective_detail`
   Summary-first, but high-confidence memories can also include direct detail snippets.
4. `full_pack`
   Most direct memory in the prompt. Useful for debugging or compatibility, but easiest to bloat.

Quick guidance:

- Use `index` for lightweight chat.
- Use `summary` for most coding sessions.
- Use `selective_detail` for autonomous or deep work.
- Use `full_pack` only when you really want maximum direct prompt memory.

- `disclosure.promptStyle`: how dense the rendered memory should look.
- `compact`: shortest wording, best when prompt space is tight
- `balanced`: readable default
- `rich`: fuller explanations, useful when budget is relaxed

- `disclosure.toolExpansionEnabled`: lets the system actively steer the agent toward memory tools when the prompt only shows a compact view.
- `disclosure.disclosureHintsEnabled`: adds human-readable hints such as "more memory is available on demand".
- `disclosure.detailMinScore`: minimum score required before raw detail snippets are shown in `selective_detail` mode.

Practical rule:
Raise it if details feel noisy. Lower it if important snippets are being hidden.

### Re-ranking

Re-ranking is a second pass that runs **after** normal scoring and **before** final selection.

- `reranking.enabled`: turns the second pass on or off.
- `reranking.profile`: controls how strongly the second pass reorders candidates.
- `balanced`: safer default, keeps ordering stable unless there is a clear better match
- `aggressive`: pushes stronger exact-match behavior and is better for deep task-focused profiles

Practical rule:
If recall feels "technically relevant but not precise enough", reranking usually helps.

### Diagnostics

- `diagnostics.verbosity`: controls how much memory telemetry is exposed in diagnostics.
- `off`: almost nothing
- `basic`: useful default
- `detailed`: best for tuning memory behavior and debugging prompt assembly

### Presets

If you do not want to tune fields one by one, use a preset.

Recommended starting points:

| Preset | Best for | What it does |
|--------|----------|--------------|
| `coding_fast` | Short coding sessions | Keeps memory compact and practical |
| `coding_balanced` | Most dev work | Best default overall |
| `coding_deep` | Long autonomous runs | Higher budget, more selective detail, stronger reranking |
| `general_chat` | Lightweight conversation | Minimal direct memory in prompt |
| `research_analyst` | Fact-heavy work | Favors semantic recall over raw episodes |
| `ops_support` | Incidents and troubleshooting | Stronger procedural/detail recall |
| `disabled` | Privacy-sensitive or controlled runs | Turns memory off |

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
| `MemoryService` | `domain.service` | Compatibility bridge onto the new memory orchestration API |
| `MemoryOrchestratorService` | `domain.memory.orchestrator` | High-level entry point for build/query/persist operations |
| `MemoryContextOrchestrator` | `domain.memory.orchestrator` | Prompt-side assembly pipeline |
| `MemoryLifecycleOrchestrator` | `domain.memory.orchestrator` | Write/promotion lifecycle pipeline |
| `MemoryRetrievalService` | `domain.service` | Candidate loading, ranking, top-k selection |
| `MemoryCandidateReranker` | `domain.memory.retrieval` | Deterministic second-pass reranking before final selection |
| `MemoryPromptPackService` | `domain.service` | Budgeted rendering into prompt context |
| `MemoryPersistSystem` | `domain.system` | Pipeline writer for structured turn events |
| `MemoryTool` | `tools` | Explicit memory search/read/expand operations for autonomous workflows |

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
4. If memory feels too noisy, lower `*TopK`, use `summary`, or raise `detailMinScore`.
5. If memory feels too vague, try `selective_detail` or switch reranking to `aggressive`.
