# Context Window Management Plan

## Goal

Build request-time context hygiene that protects the model from both context
overflow and irrelevant context bloat, without mutating raw session history or
losing auditability.

The current implementation already has emergency compaction and basic
system-prompt layer budgeting. The next work should make context construction
relevance-aware, budget-aware, observable, and explicit about which artifacts
are prompt-visible.

## Current Problems

1. History compaction is mostly threshold-based.
   `AutoCompactionSystem` and `LlmRequestPreflightPhase` protect against
   provider context overflow, but they do not select the most relevant history
   for each request.

2. System prompt budgeting needs richer semantics.
   `ContextLayerResult` and `PromptComposer` can enforce a global token cap, but
   the composer still only drops whole layers. It does not yet compress
   compressible sections, emit a full hygiene report, or enforce sub-budgets for
   memory, tools, diary, and conversation summaries.

3. Raw tool results and internal artifacts can pollute future turns.
   `DefaultHistoryWriter` preserves assistant tool calls, tool results,
   recovery hints, and final assistant answers in raw history. That is useful for
   debugging, but raw JSON, HTML, base64, stack traces, search results, and large
   tool payloads should not automatically be resent to the LLM.

4. One config value currently represents two different limits.
   `AgentLoop` uses `runtimeConfigService.getTurnMaxLlmCalls()` for outer loop
   iterations, while `DefaultToolLoopSystem` uses the same setting for internal
   LLM calls inside the tool loop. Skill-transition iterations and tool-loop LLM
   calls should be configured separately.

## Architecture To Add

### 1. ContextWindowProjector

Builds the request-time message view from raw session history.

```java
public interface ContextWindowProjector {
    ConversationView project(
            AgentContext context,
            ConversationView rawView,
            ContextBudget budget);
}
```

Responsibilities:

- Keep raw `session.messages` unchanged.
- Preserve tool-call continuity for unfinished assistant tool call / tool result
  pairs.
- Pin latest user input, active skill contract, active task/current goal,
  explicit user preferences, and durable pinned memory.
- Score candidate context items by relevance, recency, active task match,
  explicit user preference, token cost, and noise.
- Compress old/noisy items when possible.
- Drop low-value prompt-visible garbage from the LLM request view only.
- Emit `ContextHygieneReport`.

### 2. HygieneConversationViewBuilder

Decorates the existing `DefaultConversationViewBuilder`.

Integration point:

- `LlmCallPhase.buildRequest()` already calls
  `viewBuilder.buildView(context, selection.model())` immediately before
  creating `LlmRequest`.
- That is the right place to clean the request view for every LLM call,
  including calls after tool execution.

```java
public final class HygieneConversationViewBuilder implements ConversationViewBuilder {
    private final ConversationViewBuilder delegate;
    private final ContextWindowProjector projector;
    private final ContextBudgetResolver budgetResolver;

    @Override
    public ConversationView buildView(AgentContext context, String targetModel) {
        ConversationView rawView = delegate.buildView(context, targetModel);
        ContextBudget budget = budgetResolver.resolve(context, targetModel);
        return projector.project(context, rawView, budget);
    }
}
```

Pipeline:

1. Raw session messages.
2. Existing provider/model masking through `DefaultConversationViewBuilder`.
3. Context item classification.
4. Required item pinning.
5. Relevant memory/tasks/diary selection.
6. Compression or projection of noisy items.
7. Token budget enforcement.
8. `ConversationView` plus diagnostics.

### 3. BudgetedPromptComposer

Evolve the current `PromptComposer` into a richer budget-aware composer.

Target API:

```java
public final class BudgetedPromptComposer {
    public String compose(
            ContextBlueprint blueprint,
            ContextBudget budget,
            ContextHygieneReport report) {
        // Include pinned layers.
        // Select high/normal layers while under budget.
        // Compress or truncate compressible layers.
        // Drop optional/low layers first.
        // Record decisions in the report.
    }
}
```

Layer metadata contract:

- `priority`: `PINNED`, `HIGH`, `NORMAL`, `LOW`, `OPTIONAL`.
- `compressible`: `true` or `false`.
- `ttl`: `turn`, `session`, or `persistent`.
- `source`: `identity`, `safety`, `skill`, `tools`, `workspace`, `memory`,
  `rag`, `diary`, `goals`, `diagnostics`.

Prompt inclusion priority:

1. Invariant identity, safety, and output contract.
2. Current user request and active skill contract.
3. Tool schema and tool usage instructions.
4. Active goal and active task only.
5. Retrieved memory and RAG.
6. Rolling summaries.
7. Optional diagnostics.

Hard requirement:

- The system prompt must not exceed its allocated budget unless only pinned
  layers remain. If pinned layers exceed the budget, record it explicitly in the
  hygiene report.

### 4. ContextGarbagePolicy

Defines what is prompt-visible, trace-visible, compressible, or droppable.

```java
public enum GarbageReason {
    TRACE_OR_TELEMETRY,
    DUPLICATE_INSTRUCTION,
    STALE_SKILL_CONTEXT,
    COMPLETED_TASK,
    OLD_DIARY_ENTRY,
    RAW_TOOL_BLOB,
    LARGE_JSON_OR_HTML,
    BASE64_OR_BINARY,
    REPEATED_ERROR,
    LOW_RELEVANCE_MEMORY,
    SUPERSEDED_PLAN,
    PROVIDER_COMPAT_ONLY,
    BUDGET_EXCEEDED
}
```

Always keep in the LLM view:

- Latest user message.
- Any unfinished assistant tool-call / tool-result boundary.
- Current-turn final assistant answer when needed for retry/recovery logic.
- Active skill contract.
- Active task and current goal.
- Explicit user preferences.
- Pinned memory.

Compress:

- Old user/assistant turns.
- Old tool results.
- Browser/search/RAG outputs.
- Old diary entries.
- Superseded plans and reasoning-like notes.

Drop from the LLM view:

- Trace IDs, span IDs, and telemetry-only metadata.
- Typing/progress events.
- Repeated boilerplate.
- Completed tasks unrelated to the current request.
- Old errors after a successful retry.
- Raw HTML, JSON, or base64 when an artifact reference or summary exists.
- Tool outputs already consumed by a final answer.

### 5. ContextHygieneReport

Records why the request context changed.

Target event payload:

```json
{
  "contextHygiene": {
    "rawTokens": 42000,
    "projectedTokens": 11000,
    "systemPromptTokens": 2600,
    "historyTokens": 6200,
    "memoryTokens": 1500,
    "toolTokens": 700,
    "dropped": {
      "RAW_TOOL_BLOB": 8,
      "OLD_DIARY_ENTRY": 12,
      "TRACE_OR_TELEMETRY": 20
    },
    "compressed": 5,
    "pinned": 7
  }
}
```

Emit this through existing runtime trace/event infrastructure and expose a
compact version through diagnostics where useful.

## Budget Model

For each LLM request:

- `modelMaxInputTokens` comes from model registry / provider metadata.
- `outputReserve = max(1024, min(32768, 10-20% of modelMaxInputTokens))`.
- `inputBudget = modelMaxInputTokens - outputReserve`.

Default budget split:

- System prompt: 25-35% of `inputBudget`.
- Conversation history: 35-45% of `inputBudget`.
- Retrieved memory: 10-20% of `inputBudget`.
- Tool results: 5-15% of `inputBudget`.
- Slack: 5-10% of `inputBudget`.

Drop/compress order:

1. Drop optional diagnostics.
2. Drop old completed tasks.
3. Compress old diary.
4. Compress old tool results.
5. Compress old conversation turns.
6. Reduce retrieved memory top-k.
7. Keep only pinned items, latest turns, and summary as the last resort.

## Scoring Model

Use scoring instead of `last N messages` as the primary strategy.

```text
score =
  100 * pinned
+  25  * is_current_turn
+  20  * semantic_similarity_to_current_request
+  15  * active_task_match
+  10  * explicit_user_preference
+   8  * recency
-  20  * stale_or_completed
-  15  * duplicate
-       token_cost_penalty
-       noise_penalty
```

Selection loop:

1. Add pinned items first.
2. Sort candidates by score.
3. Include candidates while they fit.
4. Compress compressible candidates that do not fit.
5. Drop the rest with `GarbageReason.BUDGET_EXCEEDED`.

## Structured Summary Format

The existing compaction flow should produce a structured summary instead of a
generic continuation summary.

```text
[Conversation summary]
Current user goal:
Open tasks:
Decisions:
User preferences:
Files / IDs / URLs:
Tool outputs already consumed:
Risks / blockers:
Last known state:
Source message range:
Source hash:
```

Requirements:

- Preserve tool-call boundaries during compaction.
- Include source range and source hash to avoid duplicate summaries.
- Make summaries updateable and auditable.
- Keep summaries separate from raw persisted history.

## Implementation Plan

### PR 1: Split Outer Loop And Tool Loop Limits

Config changes:

```yaml
turn:
  maxSkillTransitions: 3
  deadlineSeconds: 300

toolLoop:
  maxLlmCalls: 20
  maxToolExecutions: 80

context:
  maxInputTokensRatio: 0.75
  outputReserveRatio: 0.15
  maxSystemPromptRatio: 0.35
```

Steps:

1. Add `turn.maxSkillTransitions` and keep backward compatibility with
   `turn.maxLlmCalls` during migration.
2. Move internal tool-loop LLM limit resolution to `toolLoop.maxLlmCalls`.
3. Update `AgentLoop` to use `getTurnMaxSkillTransitions()` for
   `AgentContext.maxIterations`.
4. Update `DefaultToolLoopSystem` to use `getToolLoopMaxLlmCalls()`.
5. Update `SystemController` and runtime settings views so labels reflect the
   new split.
6. Add config validation tests and migration/defaults tests.

Acceptance criteria:

- Outer skill-transition limit and inner tool-loop LLM limit can be configured
  independently.
- Existing configs still work through a documented compatibility fallback.
- Unit tests prove both limits stop the correct loop.

### PR 2: Add HygieneConversationViewBuilder

Steps:

1. Add `ContextBudget`, `ContextBudgetResolver`, `ContextWindowProjector`,
   `ContextItem`, `ContextItemKind`, and `ContextHygieneReport`.
2. Implement `HygieneConversationViewBuilder` as a decorator around
   `DefaultConversationViewBuilder`.
3. Classify `Message` instances into context items.
4. Pin latest user message and unfinished tool-call boundaries.
5. Add garbage detection for telemetry, raw blobs, large JSON/HTML, base64, old
   errors, and consumed tool outputs.
6. Add token estimation and per-kind budgets for conversation history and tool
   results.
7. Wire the decorator in `ToolLoopAutoConfiguration`.
8. Store report diagnostics on `ConversationView` and trace events.

Acceptance criteria:

- Raw `session.messages` is never mutated by hygiene projection.
- Large old tool outputs are summarized or dropped from request view.
- Pending tool-call pairs are never split.
- `LlmCallPhase.buildRequest()` receives the projected view.
- Tests cover large JSON/HTML/base64, provider masking, current-turn pinning,
  and tool-call boundary preservation.

### PR 3: Evolve PromptComposer Into BudgetedPromptComposer

Steps:

1. Extend layer metadata with priority class, source, TTL, and compressible flag.
2. Add `ContextLayerPriority` while preserving existing numeric priority for
   compatibility during migration.
3. Replace whole-layer-only selection with sub-budget-aware selection.
4. Add compression/truncation hooks for compressible layers.
5. Record included, compressed, and dropped layers in `ContextHygieneReport`.
6. Add hard budget guarantees for system prompt composition.
7. Add tests for pinned overflow, optional layer dropping, compressible layer
   truncation, ordering stability, and diagnostics.

Acceptance criteria:

- System prompt stays within `ContextBudget.systemPromptTokens` unless pinned
  layers alone exceed it.
- Optional/low layers drop before high-value layers.
- Prompt order remains stable after selection.
- Hygiene report explains every dropped or compressed layer.

### PR 4: Make Goals, Tasks, Diary, And Memory Retrieval-Based

Steps:

1. Change auto-mode context assembly to inject active goal/task first.
2. Replace fixed recent diary injection with relevant diary selection.
3. Add top-k retrieval for diary/goals/tasks using current user request, active
   skill, and active task.
4. Add diversity filtering to avoid repeated entries.
5. Add short summaries for selected entries.
6. Keep full goal tree out of the system prompt unless explicitly requested.
7. Add tests for issue #34 and #35 scenarios: large goal trees and long diary
   histories.

Acceptance criteria:

- Active task/current goal are always available.
- Large goal trees do not dominate the system prompt.
- Long diaries are selected by relevance, not only recency.
- Prompt token usage for auto-mode stays within configured budgets.

### PR 5: Add Context Attribute TTL And Cleanup Hooks

New model:

```java
public enum ContextScope {
    ITERATION,
    TURN,
    SESSION,
    PERSISTENT
}

public record ContextAttributeSpec(
        String key,
        ContextScope scope,
        boolean promptVisible,
        boolean traceVisible) {
}
```

Service:

```java
public interface ContextHygieneService {
    void afterSystem(AgentContext context, String systemName);
    void afterOuterIteration(AgentContext context);
    void beforePersist(AgentContext context);
}
```

Steps:

1. Add a registry for known `ContextAttributes`.
2. Mark each key with scope, prompt visibility, and trace visibility.
3. Clean iteration-scoped attributes after each outer loop iteration.
4. Clean turn-scoped attributes before persistence.
5. Keep session/persistent attributes only when explicitly durable.
6. Add tests for stale `LLM_RESPONSE`, consumed tool results, routing outcome,
   preflight diagnostics, retry flags, and recovery hints.

Acceptance criteria:

- Transient attributes do not leak across iterations or turns.
- Persistence keeps audit-relevant data and drops prompt-only garbage.
- Cleanup does not break final response preparation or recovery systems.

### PR 6: Observability And Regression Tests

Steps:

1. Emit `contextHygiene` runtime event for every LLM request.
2. Include raw tokens, projected tokens, system/history/memory/tool splits,
   dropped counts by reason, compressed count, and pinned count.
3. Add functional tests around `AgentLoop` with a fake OpenAI-compatible API.
4. Add CI workflow inputs/secrets for external model testing:
   `CONTEXT_E2E_API_BASE_URL`, `CONTEXT_E2E_API_TOKEN`, and
   `CONTEXT_E2E_MODEL`.
5. Add tests for context-window budgeting, tool-result hygiene, prompt budget
   enforcement, and no-mutation raw session history.

Acceptance criteria:

- CI can run isolated context-window E2E against an OpenAI-compatible endpoint.
- Reports show why context was dropped or compressed.
- Tests fail if raw tool blobs re-enter the LLM request view.
- Tests fail if system prompt exceeds budget.

## Non-Goals

- Do not delete or rewrite raw `session.messages` as part of request hygiene.
- Do not rely on large model context windows as the primary fix.
- Do not use `last N turns` as the primary relevance strategy.
- Do not hide prompt budget decisions without diagnostics.
- Do not break provider/model masking already handled by
  `DefaultConversationViewBuilder`.

## Test Matrix

Unit tests:

- `ContextBudgetResolver`.
- `ContextWindowProjector`.
- `ContextGarbagePolicy`.
- `HygieneConversationViewBuilder`.
- `BudgetedPromptComposer`.
- `ContextAttributeSpec` registry and cleanup.

Integration tests:

- `LlmCallPhase` builds requests from projected history.
- Tool-call boundaries remain valid.
- Large tool outputs are compressed or dropped.
- System prompt budget is enforced.
- Auto-mode active task context survives budget pressure.
- Diary/goals are selected by relevance.

E2E tests:

- Long-running `AgentLoop` with noisy tool results.
- OpenAI-compatible fake or external endpoint.
- Low context-window model settings to force projection and compression.
- Assertions on `ContextHygieneReport`.

CI gates:

- Formatter.
- PMD.
- SpotBugs.
- Javadoc.
- Unit and integration tests.
- Context Window E2E.
- Sonar quality gate.

## Rollout Order

1. Split config limits first because it is low-risk and clarifies turn control.
2. Add hygiene projection for message history without changing persistence.
3. Add richer prompt composition and diagnostics.
4. Switch goals/tasks/diary to relevance-based selection.
5. Add TTL cleanup for transient attributes.
6. Expand observability and E2E coverage.

Each PR should keep raw history intact and add tests proving that the request
view is cleaner than persisted session state.
