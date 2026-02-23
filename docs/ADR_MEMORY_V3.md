# ADR: Memory V3 Architecture for Autonomous Coding Workflows

Status: Proposed  
Date: 2026-02-22  
Owners: Core AI Engineering

## Context

Memory V2 improved structure and configurability, but it still has hard limits for long-running autonomous coding tasks:

1. Retrieval quality is mostly lexical, so semantic matches are weak.
2. Extraction quality is heuristic, so type assignment and titles are noisy.
3. Items are mostly flat records, so causal chains are lost.
4. Forgetting is mostly age-based, so valuable items can be dropped while stale items survive.
5. Contradictory facts can coexist and pollute prompts.
6. Memory is mostly passive (store + inject), with limited behavior impact.
7. Scope separation across concurrent projects is not explicit enough.

For an autonomous coding agent, these limits cause rework, repeated mistakes, and token waste.

## Decision Drivers

1. Increase correctness of recalled context for coding tasks.
2. Keep token usage rational and predictable.
3. Preserve reliability with explicit fallbacks.
4. Avoid embedding-first dependency for now.
5. Support incremental rollout without breaking Memory V2 data.

## Considered Options

### Option A: Keep Memory V2 and tune parameters

- Pros: zero architecture changes, low risk.
- Cons: does not solve semantic retrieval, extraction quality, contradiction handling.
- Decision: Rejected.

### Option B: Embedding-first redesign

- Pros: strong semantic recall.
- Cons: higher infra complexity, vendor coupling, storage/index overhead.
- Decision: Deferred.

### Option C: Memory V3 with LLM extraction + LLM rerank + lifecycle intelligence

- Pros: solves highest-impact gaps with current system primitives.
- Cons: introduces additional LLM calls and orchestration complexity.
- Decision: Accepted.

## Decision

Implement Memory V3 with three explicit paths:

1. Write Path: semantic extraction, dedup/upsert, contradiction handling.
2. Read Path: lexical recall, LLM rerank, graph-aware expansion, budgeted packing.
3. Lifecycle Path: access-aware decay, type-aware TTL, consolidation jobs.

Embeddings are optional and not required for V3.

---

## Architecture

### 1) Data Model (Backward Compatible)

Extend `MemoryItem` with:

- `scope` (string): project/skill/repo scope, plus `global`.
- `status` (enum): `active | superseded | archived`.
- `supersededById` (string, nullable).
- `accessCount` (int, default `0`).
- `relatedItems` (list of links): `{ id, relation }`.
- `relation` enum: `caused_by | resolved_by | supersedes | contradicts | depends_on`.

Defaults for V2 records during read:

- `scope = global`
- `status = active`
- `accessCount = 0`
- `relatedItems = []`

### 2) Write Path (Extraction + Upsert)

Flow per turn:

1. Heuristic gate detects memory-worthy turn (`code/error/decision/change` signals).
2. If gated, run a single low-cost LLM extraction call (JSON schema output).
3. Normalize and validate items.
4. Dedup/upsert by fingerprint.
5. Run contradiction/supersede check for semantic/procedural facts.
6. Persist items and links.

Fallback:

- If LLM extraction fails (timeout/parse/model error), fallback to current heuristic extraction.

### 3) Read Path (Two-Phase Retrieval)

Flow per query:

1. Scope filter: `currentScope + global`.
2. Lexical recall for candidate expansion (cheap, high recall).
3. Optional LLM rerank for top candidates when trigger conditions are met.
4. Graph expansion (1 hop by default) for top-ranked items.
5. Token-budgeted packing into final prompt section.

Rerank trigger conditions:

- Low lexical confidence margin.
- Ambiguous query intent.
- Conflict-prone contexts (multiple close facts with same entity).

### 4) Lifecycle Path (Forgetting + Consolidation)

Forgetting policy:

- Access-aware: retrieval writes to access log; background merge updates `lastAccessedAt/accessCount`.
- Type-aware TTL defaults:
  - `PREFERENCE`: no decay by default.
  - `PROJECT_FACT`: long TTL.
  - `FIX`: medium TTL.
  - `TASK_STATE`: short TTL.
- Superseded items are hidden from default retrieval.

Consolidation:

- Periodic job summarizes dense episodic clusters into compact semantic/procedural summaries.
- Links old episodic items to summary and archives redundant fragments.

### 5) Active Memory Hooks

Add controlled active behaviors:

1. Planning pre-brief: inject a short checklist of relevant constraints/decisions before complex plans.
2. File-aware salience boost: when working file paths match memory references, boost those items.

These hooks are optional and feature-flagged.

---

## Configuration Additions (Memory V3)

Proposed runtime config fields:

- `memory.v3.enabled` (bool)
- `memory.v3.extractionMode` (`heuristic | llm | hybrid`)
- `memory.v3.rerank.enabled` (bool)
- `memory.v3.rerank.maxCandidates` (int)
- `memory.v3.rerank.triggerMinMargin` (float)
- `memory.v3.maxLlmCallsPerTurn` (int, default `1`)
- `memory.v3.scope.mode` (`skill | workspace | manual`)
- `memory.v3.lifecycle.typeTtlDays.*` (per type)
- `memory.v3.consolidation.enabled` (bool)
- `memory.v3.consolidation.interval` (duration)

Existing V2 fields remain valid.

## Token and Cost Controls

1. Hard cap memory-related LLM calls per turn (`maxLlmCallsPerTurn`).
2. Prefer `hybrid` extraction mode:
   - LLM only for high-value or ambiguous turns.
3. Rerank is conditional, not always-on.
4. Consolidation runs asynchronously, never on hot request path.

## Observability and Evaluation

Add metrics:

- `memory.v3.extraction.calls`
- `memory.v3.extraction.fallback.count`
- `memory.v3.rerank.calls`
- `memory.v3.rerank.skipped`
- `memory.v3.retrieval.candidate_count`
- `memory.v3.retrieval.final_count`
- `memory.v3.contradiction.detected`
- `memory.v3.items.superseded`
- `memory.v3.scope.mismatch.filtered`
- `memory.v3.token.overhead`

Quality KPIs:

1. Recall@K on coding memory eval set.
2. Contradiction rate in injected prompt packs.
3. Repeated failure rate in autonomous tasks.
4. Median memory token overhead per turn.

## Rollout Plan

Phase 1: Schema + scope + lifecycle foundation  
Phase 2: LLM extraction (`hybrid` default)  
Phase 3: Two-phase retrieval + LLM rerank  
Phase 4: Contradiction/supersede engine  
Phase 5: Consolidation + active hooks + graph expansion

Each phase ships behind feature flags with fallback to V2 behavior.

## Migration Strategy

1. No offline migration required for initial rollout.
2. Lazy upgrade on read/write (fill defaults for missing V3 fields).
3. Keep JSONL compatibility during transition.
4. Add background optional migration tool only after V3 proves stable.

## Risks and Mitigations

1. Increased latency from LLM calls.
   - Mitigation: trigger-based calls, low-cost model, strict call caps.
2. LLM extraction drift/format errors.
   - Mitigation: strict schema validation + heuristic fallback.
3. Over-linking and noisy graph expansion.
   - Mitigation: one-hop default, confidence thresholds, budget guardrails.
4. Scope misclassification.
   - Mitigation: explicit override channel and telemetry for mismatch rate.

## Consequences

Positive:

- Better semantic recall and less repeated coding mistakes.
- Cleaner long-term memory with contradiction handling.
- Lower prompt noise through scope and lifecycle governance.

Negative:

- Higher orchestration complexity.
- Additional runtime cost if feature flags are misconfigured.

## References

- `docs/MEMORY.md`
- `docs/MEMORY_EPIC_ARCHITECTURE_DRAFT.md`
- `docs/CONFIGURATION.md`
