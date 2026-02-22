# Memory V2 Epic Architecture Draft

Status: Draft  
Owner: AI Platform  
Branch: `feat/memory-epic-architecture-draft`

## 1. Why We Are Changing Memory

Current memory behavior is simple and stable, but it is not optimal for long autonomous coding sessions:

1. `MemoryService` injects structured, ranked memory packs into prompt context.
2. `MemoryPersistSystem` writes flat short lines (`User | Assistant`) with minimal structure.
3. Prompt injection uses a large `# Memory` block without explicit budget arbitration against other sections.
4. Memory quality depends on full-text accumulation, not relevance scoring.
5. There is no code-aware memory extraction for test failures, touched files, commands, and blockers.

The result is acceptable continuity, but weak precision over long horizons and gradual context bloat.

## 2. Goals

1. Improve long-term autonomous coding performance.
2. Keep prompt growth rational and controlled, without hard aggressive compression.
3. Preserve backward compatibility with existing memory files during migration.
4. Make memory machine-readable for ranking, promotion, decay, and governance.
5. Support safe autonomous writing to memory through explicit APIs, not implicit filesystem coupling.

## 3. Non-goals

1. Replacing Session compaction or ToolLoop.
2. Replacing LightRAG.
3. Building multi-tenant memory isolation beyond existing session/channel model.
4. Solving every prompt quality issue in this epic.

## 4. Current State Summary

### Read path

1. `ContextBuildingSystem` reads `memoryComponent.buildMemoryPack(...)`.
2. It appends `# Memory` before `# Relevant Memory` (RAG).
3. Memory context is a packed markdown block assembled by `MemoryPromptPackService`.

### Write path

1. `MemoryPersistSystem` creates one structured `TurnMemoryEvent` per finalized turn.
2. `MemoryWriteService` extracts typed items into JSONL stores.
3. Events and items carry confidence/salience metadata.

### Config and UI

1. Runtime memory config exposes Memory V2 retrieval/promotion/decay controls.
2. Dashboard Memory tab exposes only these fields.

## 5. Target Architecture (Memory V2)

Memory V2 introduces layered structured memory plus retrieval-time packing.

### 5.1 Logical Layers

1. Working Memory: short horizon facts for the current active task.
2. Episodic Memory: timestamped events from completed turns.
3. Semantic Memory: durable facts and decisions validated over time.
4. Procedural Memory: stable operating rules and coding conventions.

### 5.2 Core Principle

Store rich structured events, retrieve small high-value packs.

## 6. Data Model Changes

### 6.1 New model: MemoryItem

Planned class: `src/main/java/me/golemcore/bot/domain/model/MemoryItem.java`

Proposed fields:

1. `id` (UUID)
2. `layer` (`WORKING`, `EPISODIC`, `SEMANTIC`, `PROCEDURAL`)
3. `type` (`DECISION`, `CONSTRAINT`, `FAILURE`, `FIX`, `PREFERENCE`, `PROJECT_FACT`, `TASK_STATE`, `COMMAND_RESULT`)
4. `title` (short human-readable summary)
5. `content` (full text)
6. `tags` (for retrieval filters, examples: `java`, `maven`, `tests`, `docker`)
7. `source` (`user`, `assistant`, `tool:<name>`, `system`)
8. `confidence` (0.0 to 1.0)
9. `salience` (0.0 to 1.0)
10. `ttlDays` (nullable)
11. `createdAt`, `updatedAt`, `lastAccessedAt`
12. `references` (file paths, command IDs, test names, URLs)
13. `fingerprint` (dedup key)

### 6.2 Prompt pack model

Planned class: `src/main/java/me/golemcore/bot/domain/model/MemoryPack.java`

Fields:

1. `items` (ordered selected items)
2. `diagnostics` (budget used, dropped candidates, scoring traces)
3. `renderedContext` (final markdown/text injected in prompt)

## 7. Storage Layout Changes

### 7.1 Structured store

1. `memory/items/episodic/YYYY-MM-DD.jsonl`
2. `memory/items/semantic.jsonl`
3. `memory/items/procedural.jsonl`
4. `memory/index/` (optional auxiliary indexes)

JSONL is chosen for append-friendly writes and easy streaming reads.

## 8. Pipeline and Service Changes

### 8.1 Write side

### A. Replace flat append-only strategy

Current: `MemoryPersistSystem` appends a single formatted line.

Target:

1. `MemoryPersistSystem` builds a `TurnMemoryEvent`.
2. `MemoryWriteService` extracts candidate `MemoryItem` objects.
3. `MemoryPromotionService` decides whether to store in episodic only or promote to semantic/procedural.
4. Emit diagnostics for observability and debugging.

### B. Code-aware extraction

Extractor inputs:

1. User message
2. Assistant final answer
3. Tool outputs from this turn

Specialized extractors:

1. Failure extractor: test names, stack fingerprints, error classes.
2. Fix extractor: patch intent, changed file paths, config deltas.
3. Command extractor: successful and failed command patterns.
4. Constraint extractor: explicit user/project limits.

### 8.2 Read side

### A. Retrieval pipeline

New service: `MemoryRetrievalService`

Pipeline:

1. Candidate fetch from layers based on query and task context.
2. Hybrid scoring:
   1. lexical relevance
   2. recency
   3. salience
   4. confidence
   5. code-domain boost (`failure`, `fix`, `constraint`, file-matched tags)
3. Deduplicate by fingerprint.
4. Assemble by budget.

### B. Prompt packing

New service: `MemoryPromptPackService`

Responsibilities:

1. Build compact memory rendering with strict section templates.
2. Enforce per-layer soft budgets.
3. Emit diagnostics for observability.

### C. Context integration

`ContextBuildingSystem` change:

1. Call `memoryComponent.buildMemoryPack(...)`.
2. Inject packed output into `# Memory`.
3. Store pack diagnostics in context attributes for logs/debug.

## 9. Runtime Config Changes

Extend `RuntimeConfig.MemoryConfig` and dashboard form.

Proposed fields:

1. `enabled` (existing)
2. `softPromptBudgetTokens` (default 1800)
3. `maxPromptBudgetTokens` (default 3500)
4. `workingTopK` (default 6)
5. `episodicTopK` (default 8)
6. `semanticTopK` (default 6)
7. `proceduralTopK` (default 4)
8. `promotionEnabled` (default true)
9. `promotionMinConfidence` (default 0.75)
10. `decayEnabled` (default true)
11. `decayDays` (default 30)
12. `codeAwareExtractionEnabled` (default true)

## 10. API and Tooling Changes

### 10.1 MemoryComponent contract

Current interface is structured (`buildMemoryPack`, `persistTurnMemory`, `queryItems`).

Target additions:

1. `MemoryPack buildMemoryPack(MemoryQuery query)`
2. `void persistTurnMemory(TurnMemoryEvent event)`
3. `List<MemoryItem> queryItems(MemoryQuery query)`
4. `void upsertSemanticItem(MemoryItem item)`

Legacy string-memory methods are removed.

### 10.2 Dedicated memory tool

Add new tool: `MemoryTool`

Operations:

1. `memory_add`
2. `memory_search`
3. `memory_update`
4. `memory_promote`
5. `memory_forget`

Reason: autonomous updates should not rely on filesystem sandbox path assumptions.

## 11. Compatibility and Migration Strategy

### Phase 0: Architecture-only draft

1. Document design and acceptance criteria.
2. Confirm API and data model direction.

### Phase 1: Structured write and read

1. `MemoryPersistSystem` writes structured episodic JSONL.
2. Prompt read uses `buildMemoryPack(...)`.

### Phase 2: Promotion and ranking tuning

1. `ContextBuildingSystem` uses packed retrieval from structured store.
2. No markdown fallback path.

### Phase 3: Decay and cleanup

1. Enable semantic/procedural promotion.
2. Enable decay cleanup jobs and dedup compaction.

### Phase 4: Hardening

1. Improve ranking quality and budget adaptation.
2. Expand autonomous coding extraction patterns.

## 12. Observability and Quality Gates

Add metrics:

1. `memory.pack.tokens`
2. `memory.pack.items.selected`
3. `memory.pack.items.dropped`
4. `memory.retrieval.latency.ms`
5. `memory.promotion.count`
6. `memory.decay.deleted`
7. `memory.hit.rate` (item referenced by assistant output)

Add logs:

1. top selected memory IDs and scores
2. budget usage per turn
3. promotion decisions with reason

## 13. Testing Plan

### Unit tests

1. Scoring and ranking behavior.
2. Budget allocator behavior.
3. Dedup and promotion rules.
4. Code-aware extractors for failures/fixes/commands.

### Integration tests

1. `ContextBuildingSystem` prompt contains compact memory pack.
2. `MemoryPersistSystem` dual-write behavior.
3. Migration toggles and backward compatibility.

### Regression tests

1. Existing memory flows still work when V2 disabled.
2. No breakage in RAG injection order.

## 14. Security and Safety

1. Memory writes must pass sanitization for prompt-injection markers.
2. Untrusted extracted facts start with lower confidence.
3. Promotion requires repeated confirmation or tool-backed evidence.
4. Memory tool operations should respect confirmation policy for destructive actions.

## 15. Performance Expectations

1. Slightly higher write-time CPU due to extractors.
2. Controlled read-time overhead via bounded candidate windows.
3. Better response quality stability over long coding sessions.
4. Rational token usage by dynamic soft budgets instead of strict hard compression.

## 16. Detailed Change Map (Planned)

Potential new files:

1. `src/main/java/me/golemcore/bot/domain/model/MemoryItem.java`
2. `src/main/java/me/golemcore/bot/domain/model/MemoryPack.java`
3. `src/main/java/me/golemcore/bot/domain/model/MemoryQuery.java`
4. `src/main/java/me/golemcore/bot/domain/model/TurnMemoryEvent.java`
5. `src/main/java/me/golemcore/bot/domain/service/MemoryRetrievalService.java`
6. `src/main/java/me/golemcore/bot/domain/service/MemoryPromptPackService.java`
7. `src/main/java/me/golemcore/bot/domain/service/MemoryWriteService.java`
8. `src/main/java/me/golemcore/bot/domain/service/MemoryPromotionService.java`
9. `src/main/java/me/golemcore/bot/tools/MemoryTool.java`

Likely modified files:

1. `src/main/java/me/golemcore/bot/domain/component/MemoryComponent.java`
2. `src/main/java/me/golemcore/bot/domain/service/MemoryService.java`
3. `src/main/java/me/golemcore/bot/domain/system/MemoryPersistSystem.java`
4. `src/main/java/me/golemcore/bot/domain/system/ContextBuildingSystem.java`
5. `src/main/java/me/golemcore/bot/domain/model/RuntimeConfig.java`
6. `src/main/java/me/golemcore/bot/domain/service/RuntimeConfigService.java`
7. `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsController.java`
8. `dashboard/src/api/settings.ts`
9. `dashboard/src/pages/settings/MemoryTab.tsx`

Likely docs updates:

1. `docs/MEMORY.md`
2. `docs/CONFIGURATION.md`
3. `docs/RAG.md` (clarify Memory V2 and RAG interplay)

## 17. Rollout Recommendation

1. Merge this architecture draft first as a standalone PR.
2. Implement structured write/read changes in a feature PR with tests.
3. Tune promotion and decay in incremental PRs with runtime metrics.
4. Keep rollback focused on Memory V2 feature flags (without text-memory fallbacks).

## 18. Open Questions

1. Should semantic/procedural stores be channel-specific or globally shared?
2. Should promotion require explicit user confirmation for high-impact facts?
3. Do we need a background compactor for JSONL files in first release?
4. Should memory scoring use a lightweight local reranker, or pure heuristic scoring first?
