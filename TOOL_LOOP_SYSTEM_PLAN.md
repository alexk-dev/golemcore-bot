# ToolLoopSystem — Engineering Plan (Hexagonal Architecture)

> Repository: `alexk-dev/golemcore-bot`
>
> Goal: introduce an explicit, reliable **tool loop** as a first-class domain system that owns the iterative cycle:
> `LLM → Tool(s) → LLM → … → Final`.

## 1) Motivation / Problems to Solve

1. **Implicit loop behavior is fragmented** across `AgentLoop` (iteration), `LlmExecutionSystem` (LLM call), `ToolExecutionSystem` (tool execution), and `ResponseRoutingSystem` (delivery), making it hard to:
   - enforce global limits (iterations/deadlines)
   - provide consistent error handling and metrics
   - guarantee “closure” (no dangling tool calls)
2. **Provider/model differences**: tool-call artifacts differ between vendors. When switching models, history may contain incompatible tool-call structures.
3. Need **Synthetic Tool Results** to keep conversation coherent when:
   - a tool call is blocked by policy/safety
   - max depth/timeout is reached
   - user confirmation is required but not granted
   - a model switch requires masking/remapping

## 2) Baseline (What the code does today)

### Current pipeline systems
- `ContextBuildingSystem` (~20)
- `LlmExecutionSystem` (30)
- `ToolExecutionSystem` (40)
- `MemoryPersistSystem` (50)
- `ResponseRoutingSystem` (60, last)

### Where the loop currently “lives”
- `AgentLoop` performs iterations and decides whether to continue based on:
  - `ContextAttributes.FINAL_ANSWER_READY` and
  - whether the last `LlmResponse` contains tool calls (`LlmResponse.hasToolCalls()`).

### Existing model-switch masking (important requirement)
- `LlmExecutionSystem` currently performs `flattenOnModelSwitch()` which:
  - checks session metadata `ContextAttributes.LLM_MODEL`
  - rewrites conversation by flattening tool-call artifacts via `Message.flattenToolMessages()`
  - updates `LLM_MODEL`

**Issue:** masking is currently a side effect of request-building; it mutates session/context history implicitly.

### Existing synthetic-ish tool results
- `ToolExecutionSystem` already produces a non-executed result when confirmation is denied (e.g. `ToolResult.failure("Cancelled by user")`) and emits a `role=tool` message.

**Gap:** stop/guard cases (iteration limit, deadline, repeat guard) do not consistently generate closure for tool chains.

## 3) Target Behavior (One Turn)

Given an incoming user message / auto-iteration:

1. Build context (memory, skills summary, plan state, preferences).
2. Run **`ToolLoopSystem`** which:
   - calls LLM
   - if LLM returns tool calls → executes tools (or emits synthetic results) → appends tool results into the conversation → calls LLM again
   - repeats until:
     - LLM returns a final assistant response without tool calls, or
     - stop condition triggers (iteration limit, deadline, etc.)
3. Persist memory once per turn after the loop finishes.
4. Route response (telegram / CLI / etc.).

**Hard requirement:** never leave a tool call without a corresponding tool-result message (real or synthetic).

## 4) Proposed Components (Hexagonal)

### 4.1 `ToolLoopSystem` (domain system)
**Responsibility:** own the loop and produce a settled outcome.

Inputs (via `AgentContext`):
- conversation/messages so far
- resolved model / tier / provider
- access to tool execution (via port/service)
- loop constraints (max iterations, deadline)

Outputs (via `AgentContext`):
- final assistant message (or safe fallback)
- updated conversation with tool-call + tool-result messages
- diagnostics: iteration count, stop reason, tool execution outcomes, synthetic flags

### 4.2 Domain result model: `ToolExecutionOutcome`
Introduce a structured domain result type:

- `ToolExecutionOutcome { toolName, toolCallId, status, resultText, errorCode, durationMs, synthetic, truncated }`

Recommended `status`:
- `SUCCESS | FAILED | BLOCKED | SKIPPED | TIMEOUT | INVALID`

Rationale:
- decouple provider formatting from domain logic
- make stop/guard decisions testable
- enable consistent telemetry

### 4.3 `ToolMessageMasker` (domain service)
Move model-switch masking out of `LlmExecutionSystem` side effects.

API sketch:
- `MaskingResult maskForModelSwitch(prevModel, nextModel, List<Message> messages)`

Rules:
- preserve semantics: what tool was requested and what result was produced
- keep a stable, provider-agnostic representation
- last resort: flatten tool call + result into plain assistant/system text

**Non-negotiable:** “we mask tool calls when changing model, otherwise requests may break.”

### 4.4 Tool execution boundary (port)
Keep `ToolExecutionSystem` as an implementation detail behind a port-like interface (or refactor it into a smaller service used by ToolLoopSystem).

Minimal interface:
- `List<ToolExecutionOutcome> execute(List<ToolCall> calls, ToolExecutionContext ctx)`

## 5) Loop Controls / Safety

### 5.1 Hard limits
- `maxIterations` (e.g., 6–10)
- `deadline` / `maxWallTimeMs` per turn
- `maxToolCallsPerIteration`
- `maxConversationBytesAddedPerIteration`

### 5.2 Guards
- repeat guard (same tool + semantically same args repeated)
- total tools per turn cap
- tool result truncation policy (already exists in `ToolExecutionSystem`; should be surfaced in `ToolExecutionOutcome.truncated`)

### 5.3 Stop behavior (closure rules)
If a stop condition triggers while there are pending tool calls or the model expects a continuation:
- emit **synthetic tool results** for any tool calls that won’t run
- produce a final assistant message explaining what happened + next action

Stop reasons (domain enum):
- `FINAL_ANSWER | MAX_ITERATIONS | DEADLINE | REPEAT_GUARD | MODEL_SWITCH_MASKING | TOOL_FAILURE_POLICY | USER_CANCELLED`

## 6) Where it Fits in the Pipeline (refactor target)

Desired ordering:
- `ContextBuildingSystem` (order ~20)
- **`ToolLoopSystem`** (new, order ~35)
- `MemoryPersistSystem` (50)
- `ResponseRoutingSystem` (60)

### Key refactor goals
- `AgentLoop` should run **one turn** and not own the iterative tool loop.
- `LlmExecutionSystem` should become a “single LLM call” service used by ToolLoopSystem (or be slimmed to a helper).
- `ToolExecutionSystem` should become a tool-execution service used by ToolLoopSystem (or be split into: confirmation + executor + message-writer).

### Important nuance discovered in current code
- `ResponseRoutingSystem` is the last step and relies on `llm.response` or `llm.error` in context.
- Therefore ToolLoopSystem should set a definitive `LlmResponse` (final) into `ContextAttributes.LLM_RESPONSE` (and clear intermediate transient values).

## 7) Single Source of Truth for tool results

Current code keeps both:
- `context.toolResults` map and
- `Message(role="tool")` messages

Decision (recommended):
- Treat **conversation messages** as the canonical, provider-agnostic log.
- Treat `toolResults` map as adapter-level convenience (optional) and derive it from messages when needed.

This improves masking safety (messages can always be flattened) and reduces duplicated state.

## 8) Implementation Strategy (incremental, low-risk)

### Phase 0 — Make behavior explicit (no big refactor)
- Add a `ToolMessageMasker` service and route current masking through it.
- Add stop/limit closure improvements: when iteration limit hits, produce a final assistant message that summarizes tool outcomes and gives a “continue” instruction.

### Phase 1 — Introduce ToolLoopSystem skeleton
- Add `ToolLoopSystem implements AgentSystem` (order ~35) that:
  - calls `LlmExecutionSystem` and `ToolExecutionSystem` internally (composition)
  - loops until final

### Phase 2 — Move loop ownership
- Make `AgentLoop` single-pass (no iterations) or make iterations a no-op when ToolLoopSystem is enabled.
- Ensure memory persistence happens once after the loop.

### Phase 3 — First-class synthetic outcomes
- Introduce `ToolExecutionOutcome` and adapt `ToolExecutionSystem` to return outcomes.
- Ensure synthetic results are emitted for: confirmation deny, stop conditions, repeat guard.

### Phase 4 — Tests & docs
- Unit tests for stop conditions + masking
- Integration tests with simulated LLM tool calls
- Add `docs/TOOL_LOOP.md`

## 9) Testing Strategy

### Unit tests
- stops after final answer when no tool calls
- synthetic results generated when:
  - confirmation denied
  - max iterations reached
  - deadline reached
  - repeat guard triggered
- masking:
  - switching model removes/rewrites incompatible tool artifacts

### Integration tests
- simulate LLM responses with tool calls, validate final conversation + `llm.response`
- ensure `MemoryPersistSystem` persists only settled final state (no intermediate loop fragments)

## 10) Documentation Deliverables

Add/Update `docs/TOOL_LOOP.md`:
- explanation of the loop
- sequence diagram (Mermaid)
- configuration knobs (maxIterations, deadlines, masking rules)
- examples of synthetic tool results
- troubleshooting: “why tool calls were flattened” / “why tool execution was synthetic”

---

## Implementation Checklist (next actions)
1. Ensure `main` is up to date.
2. Implement `ToolMessageMasker` and route model-switch masking through it (remove side effects from request-building).
3. Add `ToolLoopSystem` skeleton + loop constraints.
4. Add `ToolExecutionOutcome` + synthetic tool result generation for all stop/guard cases.
5. Decide/implement canonical tool history representation (messages as truth).
6. Add docs `docs/TOOL_LOOP.md`.
7. Add tests.


## 11) Code Review Notes — ToolExecutionSystem (findings)

### 11.1 Current responsibilities (too broad)
`ToolExecutionSystem` currently bundles:
- tool registry + channel registry
- confirmation flow (`ToolConfirmationPolicy` + `ConfirmationPort`)
- execution + timeout mapping (currently sequential, timeout per call)
- conversation history writing (assistant tool-call message + tool result messages)
- attachment extraction (base64 screenshot / file_bytes)
- truncation (`maxToolResultChars`)
- loop continuation signaling (`ContextAttributes.FINAL_ANSWER_READY`) (legacy `TOOLS_EXECUTED` removed)

**Action:** split into smaller services so `ToolLoopSystem` can own loop decisions and history formation.

### 11.2 Execution is sequential (doc mismatch)
Despite javadoc, tool calls are executed in a `for` loop; each call blocks up to `TOOL_TIMEOUT_SECONDS`.

**Actions:**
- implement real parallel execution (`allOf`) with a global per-iteration budget
- add config for tool timeout (avoid hardcoded 30s)
- add `maxToolCallsPerIteration`

### 11.3 Canonical history vs toolResults map
Tool results are stored both as:
- `AgentContext.toolResults: Map<toolCallId, ToolResult>`
- `Message(role="tool", toolCallId=..., content=...)`

Masking/flattening (`Message.flattenToolMessages`) relies on **messages**, not the map.

**Decision (recommended):** treat `List<Message>` as the canonical log; treat `toolResults` map as adapter convenience only.

### 11.4 Confirmation deny already acts like synthetic tool result
Deny path produces a failure `ToolResult` and a tool message `"Error: Cancelled by user"`.

**Action:** unify all synthetic results under a stable format and structured outcome (`ToolExecutionOutcome.status=BLOCKED` + `errorCode=USER_CANCELLED`).

### 11.5 Tool-call assistant message content concerns
Tool-call assistant messages are written with `content = llmResponse.getContent()` which may be null/empty or provider-noisy.

**Action:** define explicit rules for when to keep assistant content in a tool-call message, and/or mark metadata so masker can safely flatten.

### 11.6 sanitizeToolName is useful but belongs earlier
Tool name sanitization is valuable but is more of a parsing/adapter concern than execution.

**Action:** move normalization into `ToolLoopSystem` or a pre-execution normalizer.

### 11.7 Recommended extraction into services
To align with hexagonal/tool loop plan:
- `ToolRegistry` (find/list tools; normalization)
- `ToolConfirmationService` (approval decision)
- `ToolRunner` (execute + timeout/exception mapping; returns `ToolExecutionOutcome` + attachments)
- `ToolHistoryWriter` (convert tool calls/outcomes into canonical messages)


## 12) Code Review Notes — Preserve raw history + metadata, transform at request time

User requirement confirmed: keep **original tool-call/message history** intact + store metadata; dynamically filter/transform for provider compatibility.

### 12.1 Current behavior conflicts with this goal
`LlmExecutionSystem.flattenOnModelSwitch()` mutates both:
- `context.messages`
- `session.messages`

This permanently loses the original structure (toolCall ids, role=tool messages) and replaces it with a plain-text approximation.

### 12.2 Proposed rule: never destructively rewrite persisted history
- `AgentSession.messages` should remain the canonical, raw log.
- Any provider-specific compatibility transformation should be applied to a **derived view** when building `LlmRequest`.

### 12.3 How to implement non-destructive masking
Introduce a masking layer that produces a *request view*:
- Input: raw `List<Message>` + (prevModel/provider, nextModel/provider)
- Output: `MaskedConversation { List<Message> requestMessages, MaskingDiagnostics diagnostics }`

Keep diagnostics so the system can explain why masking happened.

### 12.4 Emergency truncation is also destructive today
`truncateLargeMessages()` currently truncates both `context.messages` and `session.messages`.

To align with “raw history + derived views”:
- store truncation as metadata (e.g., `metadata["truncated.originalLength"]=...`)
- apply truncation to request view only
- optionally keep a compacted shadow copy, but do not overwrite raw

### 12.5 Implication for ToolLoopSystem
ToolLoopSystem should:
- append raw tool interactions to session/context history as they occur
- build LLM requests through a *view builder* (`ConversationViewBuilder`) that applies:
  - masking (provider switch)
  - truncation (budget-based)
  - optional compaction/summarization

This makes model switching safe without corrupting the audit trail.


## 13) Code Review Notes — ResponseRoutingSystem & MemoryPersistSystem (tool-loop implications)

### 13.1 ResponseRoutingSystem expects a single final `llm.response`
- `ResponseRoutingSystem.shouldProcess()` triggers primarily on `ContextAttributes.LLM_RESPONSE` / `LLM_ERROR`.
- It appends an assistant message to `AgentSession` in `addAssistantMessage(...)`.

**Implication:** ToolLoopSystem must produce a *final* `LlmResponse` in `ContextAttributes.LLM_RESPONSE` and avoid leaving intermediate responses there.

### 13.2 Risk: multiple assistant messages per turn
With tool-loop, the conversation may contain:
- assistant tool-call messages (with `toolCalls`)
- tool result messages
- final assistant answer

`ResponseRoutingSystem` currently sends based on `llm.response.getContent()` rather than scanning messages; this is good *if* ToolLoopSystem guarantees `llm.response` is final.

**Rule:** tool-call assistant messages must not be treated as user-visible final responses.

### 13.3 MemoryPersistSystem currently persists based on `llm.response` only
- It finds the last user message from `context.messages`.
- It persists using `context.getAttribute("llm.response")` and truncates it.

**Implication:** Memory persistence will ignore tool interactions (fine), but it will also record the wrong content if `llm.response` is intermediate.

**Action:** after introducing ToolLoopSystem, ensure MemoryPersistSystem persists only settled final answer:
- either by consuming `ContextAttributes.LLM_RESPONSE` (final) as now,
- or by reading a new attribute like `toolLoop.finalResponse`.

### 13.4 Attachments are first-class tool execution outcomes
Attachments flow through the pipeline as explicit values:

- `ToolCallExecutionResult.extractedAttachment` carries the attachment alongside the tool result.
- `ToolExecutionOutcome.attachment` carries it through the tool loop.
- `DefaultToolLoopSystem` accumulates attachments per turn and includes them in `OutgoingResponse`.
- `ResponseRoutingSystem` sends attachments only from `OutgoingResponse.attachments`.

### 13.5 Avoid duplicating assistant final message in session
Currently:
- ToolExecutionSystem writes assistant tool-call messages to session.
- ResponseRoutingSystem writes final assistant message to session.

In ToolLoopSystem future:
- keep raw tool-call + tool-result messages in session
- ResponseRoutingSystem should still be the single place that appends the final assistant response message (unless ToolLoopSystem takes over; pick one).

Recommended: keep it in ResponseRoutingSystem for now, but ensure ToolLoopSystem does not also append the same final assistant message.


## 14) Requirement — Preserve provider-specific tool-call fields in raw history

User requirement: raw history must retain provider/vendor-specific tool-call artifacts and fields (for audit/debug/replay).

### 14.1 Data model implication
- `Message` / `ToolCall` should support an **extensions** map for provider-specific fields (e.g., raw JSON fragments, vendor IDs, schema versions).
- Do not discard these fields during masking/truncation; masking must operate on a **request view**.

### 14.2 View-building implication
`ConversationViewBuilder` must:
- take raw messages as input
- produce a derived message list suitable for the target provider
- optionally drop/flatten provider-specific fields ONLY in the derived view

### 14.3 Migration note
If current `Message.ToolCall` does not have an extensions container, add:
- `Map<String,Object> raw` / `extensions` on `ToolCall`
- `Map<String,Object> provider` on `Message.metadata` (for per-message provenance)


## 15) Decision — Variant A for provider fields + explainable truncation (persist raw, project views)

**Decision:** extend `Message.ToolCall` (Variant A) to persist provider-specific fields in raw history.

**Additionally required:** truncation must be *explainable* (diagnostics + metadata) and must not mutate raw history.

### 15.1 Proposed model changes (domain)
Update `src/main/java/me/golemcore/bot/domain/model/Message.java`:
- `ToolCall.extensions: Map<String,Object>` (or `providerFields`) — persisted by Jackson.
- optional provenance (if useful): `provider`, `model`, `schemaVersion`.

### 15.2 Explainable truncation (view-only)
Introduce `TruncationDiagnostics` and attach to request-time view (NOT raw):
- which messages were truncated
- original length vs truncated length
- reason (max_chars, max_tokens, safety)
- budget snapshot (optional)

### 15.3 Persistence impact
`AgentSession` is serialized via Jackson (`SessionService.save()` uses `objectMapper.writeValueAsString(session)`). Adding fields to `ToolCall` is safe and backward compatible.

### 15.4 Key constraint
Never call `flattenToolMessages()` or truncation logic on `session.messages` except inside an explicit **compaction** operation. Any cross-model masking/flattening must be request-time projection.

## 16) Code Review Notes — Data-loss points & policy (flattening + truncation)

### 16.1 `flattenToolMessages()` call sites
Found usages:
- `SessionService.compactMessages()` / `compactWithSummary()` — **acceptable data loss** per user decision (tool history not needed post-compaction).
- `LlmExecutionSystem.flattenOnModelSwitch()` — **NOT acceptable** under immutable raw history approach: it rewrites both `context.messages` and `session.messages`.

### 16.2 `flattenOnModelSwitch()` must become view-only
Current behavior destructively clears and replaces message lists in both context and session. This conflicts with:
- preserving raw provider-specific tool-call fields
- audit/replay/debug goals

Action: replace with request-time masking in `ConversationViewBuilder` (or inside future `ToolLoopSystem` before each LLM call).

### 16.3 Emergency truncation currently mutates persisted session
`LlmExecutionSystem.truncateLargeMessages()`:
- truncates `context.messages` (in-place)
- truncates `session.messages` (persisted)

This violates explainable truncation + raw immutability. Desired policy:
- truncation is view-only by default
- optionally persist truncation only as part of explicit **compaction** (user-approved), not silently.

### 16.4 Explainability requirements for truncation
When truncation happens (view-only):
- record diagnostics: message ids, roles, original length, truncated length, budget (maxMessageChars)
- optionally add a synthetic note to request view (NOT raw): "Some long messages were truncated to fit context limits".

## 17) Code Review Notes — Langchain4jAdapter (tool-call schema + provider fields)

### 17.1 Tool-call representation is canonical-only today
`Langchain4jAdapter.convertResponse()` maps LangChain4j `ToolExecutionRequest` -> `Message.ToolCall {id,name,arguments}` and discards any provider-specific fields.

### 17.2 Adapter currently relies on upstream destructive flattening
`convertMessages()` comment: model switches handled upstream by `LlmExecutionSystem.flattenOnModelSwitch()`.
This couples adapter safety to a data-loss mechanism and conflicts with immutable raw history.

### 17.3 Required change for Variant A
When extending `Message.ToolCall` with `extensions`, populate it in adapters where possible.
For LangChain4j, available data may be limited to:
- `id`, `name`, `arguments` (string)
Therefore `extensions` can at minimum store:
- original raw arguments string
- potentially the raw `ToolExecutionRequest` fields snapshot (if accessible)

### 17.4 Request view responsibility
Provider-switch masking/flattening must move to request-time view building.
`Langchain4jAdapter` should accept already-safe `request.messages` and not depend on mutating session/context.

### 17.5 Explainable truncation integration
Truncation should occur before `convertMessages()`, with diagnostics stored in `ConversationView` / transient context attributes.

## 18) Code Review Notes — CustomLlmAdapter (OpenAI-compatible) + provider-field preservation

### 18.1 Custom adapter has access to raw OpenAI-style tool call objects
`CustomLlmAdapter.ApiToolCall` includes:
- `id`
- `type`
- `function { name, arguments (string) }`

This is a good place to preserve provider-specific fields because the DTO is already close to the wire format.

### 18.2 Variant A change
When mapping `ApiToolCall` -> `Message.ToolCall`, populate `extensions` with (at least):
- `rawToolCall`: the full `ApiToolCall` object (or a Map snapshot)
- `type`: tool call type
- `rawArguments`: `tc.getFunction().getArguments()` before JSON parse

### 18.3 Request building must not leak incompatible tool artifacts
`buildRequest()` currently passes through tool_calls / tool_call_id if present. This is correct for OpenAI-compatible providers, but may break if we switch providers.
Therefore request-time view builder must ensure:
- if provider switch requires masking: do not include tool_calls/tool messages in request; flatten to assistant/user text.

### 18.4 Provenance placement
`CustomLlmAdapter.convertResponse()` returns `model=apiResponse.getModel()`. This may differ from `request.model`. Consider storing both in tool-call `extensions` or in LlmResponse metadata.

## 19) Code Review Notes — LlmAdapterFactory and the "provider vs model" contract

### 19.1 Active provider is a single global adapter
`LlmAdapterFactory` selects provider from `bot.llm.provider` and then delegates.
However, routing selects models like `provider/model` (e.g. `anthropic/claude...`).
This implies:
- either active provider must be `langchain4j` to support multiple providers behind one adapter,
- or `ToolLoopSystem` / LlmExecution must ensure provider/model combinations are compatible with the active adapter.

### 19.2 Tool-loop design implication
ToolLoopSystem should treat "provider" as part of `ProviderKey` used by view-builder, independent of which Java adapter happens to execute the call.

## 20) Decision & task list — Remove CustomLlmAdapter (legacy)

Decision: **fully remove** `CustomLlmAdapter` and all `bot.llm.custom.*` configuration, docs, and tests (breaking change acceptable).

### Checklist (to execute later)
- [ ] Delete `src/main/java/me/golemcore/bot/adapter/outbound/llm/CustomLlmAdapter.java`
- [ ] Remove `custom` mentions from Javadoc/comments:
  - [ ] `LlmAdapterFactory` javadoc
  - [ ] `LlmProviderAdapter` javadoc
  - [ ] `domain/component/LlmComponent` javadoc
- [ ] Remove config properties:
  - [ ] `src/main/resources/application.properties` block `bot.llm.custom.*`
  - [ ] Remove env examples in `docs/CONFIGURATION.md` (`BOT_LLM_PROVIDER=custom`, custom model examples)
- [ ] Remove tests for custom adapter:
  - [ ] `src/test/java/me/golemcore/bot/adapter/outbound/llm/CustomLlmAdapterTest.java`
  - [ ] Update `LlmAdapterFactoryTest` to not reference `custom`
  - [ ] Update `ModelConfigServiceTest` constants if they reference custom provider
- [ ] Search & remove remaining references:
  - [ ] `PromptSectionServiceTest` references to section named `custom` (verify what this means; rename or delete)
- [ ] Ensure Spring still wires `LlmAdapterFactory` with remaining providers (`langchain4j`, `none`) and all tests pass

Notes:
- `docs/CONFIGURATION.md` currently contains multiple explicit references to `custom` provider.
- `application.properties` has a dedicated custom section.

## 21) Code Review Notes — ToolExecutionSystem (current responsibilities vs ToolLoop target)

### 21.1 ToolExecutionSystem currently does orchestration + persistence + UX
Observations:
- Reads tool calls from `context[ContextAttributes.LLM_RESPONSE]` (via `LlmResponse.getToolCalls()`).
- Adds **assistant** message with toolCalls to both `context.messages` and `session.messages`.
- Executes tools sequentially, handling:
  - confirmation policy + async user confirmation
  - optional notifications when confirmation disabled
  - timeout (30s)
  - attachment extraction
  - truncation of tool output to `autoCompact.maxToolResultChars`
- Adds **tool** result messages to both `context.messages` and `session.messages`.
- Legacy: used to set `context["tools.executed"]=true` for loop continuation (attribute removed).

This is a lot of responsibilities; ToolLoopSystem should own the orchestration/loop, while ToolExecutionSystem should become a lower-level executor (or adapter) that:
- executes a *single* tool call and returns `ToolResult` (+ attachments)
- does NOT mutate conversation history

### 21.2 Good: tool name sanitization
`sanitizeToolName()` protects against leaked special tokens in tool names. Keep this logic, but move it to the executor layer that normalizes tool calls.

### 21.3 Issue: assistant message content for tool-call message is taken from `llm.response.content`
ToolExecutionSystem builds the assistant tool-call message with:
- `content = llmResponse != null ? llmResponse.getContent() : null`
- `toolCalls = toolCalls`

In many providers, tool-call messages have `content=null`. Keeping content can be fine, but must be treated as raw provider payload. With the new raw-history policy, this is acceptable, but ToolLoopSystem must ensure `ContextAttributes.LLM_RESPONSE` remains "final answer" only.

### 21.4 Data model gap for provider-specific tool-call fields
ToolExecutionSystem uses `Message.ToolCall` obtained from `llm.toolCalls` (parsed by adapter). Once Variant A adds `extensions`, ToolExecutionSystem must preserve them when storing tool-call messages.

### 21.5 Synthetic tool results support hook
When a tool call is denied (or cannot run), ToolExecutionSystem creates a tool message:
- role=tool, content="Error: Cancelled by user"

For ToolLoopSystem, this pattern generalizes into "synthetic tool result" (e.g. denied, skipped, repeat-guard, deadline) with:
- `ToolResult` status and a canonical error reason
- persisted as a tool message linked by toolCallId

### 21.6 Truncation of tool results
`truncateToolResult()` is currently *persisting* a truncated tool output into history.
Decision needed:
- Tool results can be extremely large; persisting truncation may be acceptable even under raw-history policy because the raw tool output is not realistically storable in-chat.
- If strict raw preservation is desired, we could store full output in external artifact storage and persist only a pointer + truncated preview.

### 21.7 Parallel execution
Despite class-level comment "executes tools in parallel", implementation executes sequentially in a for-loop.
ToolLoopSystem should decide execution strategy:
- sequential (safer, deterministic) by default
- optional parallel for independent tool calls, with per-tool timeouts

### 21.8 Context holder global state
`AgentContextHolder.set/clear` introduces thread-local global state. With parallel tool execution, ensure tools do not rely on unsafe shared context.

## 22) Code Review Notes — AgentLoop (iteration ownership) + “continue” contract (tools.executed + llm.response)

### 22.1 Current loop ownership
- **`AgentLoop`** owns the outer iteration loop (`for iteration in maxIterations`), and runs all `AgentSystem`s in order each iteration.
- Loop continuation is decided by `shouldContinueLoop(context)`.

### 22.2 Current continuation contract (problematic for ToolLoopSystem)
`shouldContinueLoop()` continues only when:
- `ContextAttributes.LOOP_COMPLETE != true`, and
- `ContextAttributes.FINAL_ANSWER_READY == false`, and
- `ContextAttributes.LLM_RESPONSE.hasToolCalls() == true`

Implications:
- The “continue” signal is **overloaded** onto `tools.executed` even when no tools were executed (e.g. skill auto-transition, plan intercept).
- The decision uses `"llm.response"` (string key) instead of `ContextAttributes.LLM_RESPONSE` in places, which increases risk of mismatch and bugs.
- It assumes the only reason to continue is “LLM requested tools and they were executed”, which will be too narrow once ToolLoopSystem supports:
  - synthetic tool outcomes (skipped/denied/maxDepth/deadline)
  - multi-step “think → tool → think → tool → final” inside one pipeline turn
  - safe model switch masking and view-building

### 22.3 Findings: reset logic between iterations mutates loop state in ad-hoc way
After continuing, `AgentLoop` clears a number of attributes:
- `llm.toolCalls = null`
- `tools.executed = false`
- `skill.transition.target = null`
- `context.getToolResults().clear()`

This is effectively a “mini state machine reset” but:
- it is not centralized (other systems set `tools.executed` too)
- it is not strongly typed (string keys)
- it does not cover provider-specific raw history needs

### 22.4 Systems that “fake” tools.executed to force continuation
- `ToolExecutionSystem` sets `tools.executed=true` (real execution)
- `PlanInterceptSystem` sets `ContextAttributes.TOOLS_EXECUTED=true` (synthetic planned tool results)
- `SkillPipelineSystem` sets `tools.executed=true` to force a new iteration for skill transition

This confirms `tools.executed` is not a reliable semantic signal anymore.

### 22.5 Decision: ToolLoopSystem must own “continue/stop” decisions
Proposed refactor direction:
- Introduce an explicit loop decision object, e.g. `ToolLoopDecision { continue: boolean, reason, nextAction }`.
- Replace `tools.executed` gating with one of:
  - `ContextAttributes.LOOP_DECISION` set by ToolLoopSystem, OR
  - `ContextAttributes.NEEDS_ANOTHER_LLM_PASS` boolean
- `AgentLoop` should become a simple orchestrator that:
  - runs systems once per iteration
  - asks **one** canonical decision source whether to continue

### 22.6 Decision: unify attribute keys (stop using string literals)
- Replace ad-hoc string keys (`"llm.response"`, `"tools.executed"`, `"llm.toolCalls"`) with constants under `ContextAttributes` (or a dedicated `ToolLoopAttributes`).
- Add tests that ensure no mixed usage remains.

### 22.7 Alignment with previous decisions
This supports our earlier design goals:
- preserve raw history + metadata; build request-specific views at call time
- Variant A: preserve provider-specific fields on tool calls (no destructive flattening)
- explainable truncation via view-builder diagnostics
- synthetic tool outcomes as first-class history events

### 22.8 Confirmed by maintainer
- ✅ Maintainer agrees: `AgentLoop` continuation must be driven by an explicit loop decision (owned by `ToolLoopSystem`), not by overloaded `tools.executed`.
- ✅ Maintainer agrees: unify attribute keys; eliminate string literals for `llm.response/tools.executed/llm.toolCalls`.

## 23) Code Review Notes — LlmExecutionSystem (request building, model switch masking, truncation)

### 23.1 Finding: inconsistent attribute keys for LLM response
- `process()` sets `context.setAttribute("llm.response", response)` (string key)
- Several other components use `ContextAttributes.LLM_RESPONSE`.

**Decision:**
- Introduce a single canonical key for the last LLM response (likely `ContextAttributes.LLM_RESPONSE`).
- Add a migration shim during refactor (read old key, write new key) to avoid breaking systems during transition.

### 23.2 Finding: `llm.toolCalls` is produced directly from `LlmResponse` and consumed by ToolExecution/PlanIntercept
- `LlmExecutionSystem` is the canonical producer of `context["llm.toolCalls"]`.

**Decision:**
- Under ToolLoopSystem, tool calls should be extracted/normalized inside ToolLoopSystem, not left as an implicit side-effect for downstream systems.
- Long-term: `LlmExecutionSystem` should only set `ContextAttributes.LLM_RESPONSE` and raw provider response metadata; ToolLoopSystem decides what to do next.

### 23.3 Critical finding: `flattenOnModelSwitch()` is destructive and violates “raw history” principle
Current behavior when model changes or legacy tool messages exist:
- `Message.flattenToolMessages(context.getMessages())`
- clears and replaces **both** `context.getMessages()` and `session.messages`

This permanently loses:
- provider-specific tool call IDs/indices
- structured tool-call fields
- explainability of what was flattened and why

**Decision (already aligned with earlier plan):**
- Remove/replace destructive flattening with **request-time view building**:
  - Keep raw history intact (including provider-specific fields per Variant A).
  - At request construction time, build a provider-safe “LLM request view” that:
    - either passes structured tool calls if compatible
    - or converts prior tool-call exchanges into textual summaries (masking) **without mutating raw history**.
- Store model-switch info in session metadata, but do not rewrite messages.

### 23.4 Finding: emergency truncation is destructive and persists in session
`truncateLargeMessages()` truncates both:
- `context.getMessages()`
- `context.getSession().getMessages()`

**Decision:**
- For ToolLoopSystem target, truncation must become **explainable and non-destructive** at raw history level.
- Implement truncation in the view-builder (request-time), producing diagnostics about:
  - which messages were cut
  - why (maxInputTokens budget)
  - old/new lengths
- Keep the current emergency truncation only as a temporary safety net; mark it as “legacy path” until view-builder is complete.

### 23.5 Finding: timeout constant (120s) duplicates other timeout configuration
- `TIMEOUT_SECONDS = 120` is hardcoded, while earlier we added configurable Langchain4j model timeout (300s default).

**Decision:**
- Align LlmExecutionSystem waiting timeout with the configured model timeout (or a dedicated `bot.llm.request-timeout`), so we do not:
  - time out locally before the HTTP client would
  - or wait forever when model timeout is shorter

### 23.6 Finding: request currently passes both `messages` and `toolResults`
`LlmRequest.builder()` includes:
- `.messages(context.getMessages())`
- `.toolResults(context.getToolResults())`

This creates ambiguity: some providers expect tool results only as messages, some as a separate field.

**Decision:**
- ToolLoopSystem should generate a single canonical representation for each provider adapter:
  - either “tool results as tool messages”
  - or “tool results as structured tool outputs”
- Avoid duplicating both in one request unless the adapter explicitly requires it.

### 23.7 Finding: model selection and model-switch policy lives inside LlmExecutionSystem
This makes it harder for ToolLoopSystem to control cross-step behavior (e.g. tool-call step on one model, final answer on another).

**Decision:**
- Extract “model selection policy + model switch compatibility policy” into a dedicated domain service used by ToolLoopSystem.
- LlmExecutionSystem becomes a thin port adapter wrapper: execute request, return response + usage.

### 23.8 Confirmed by maintainer + decision on request timeout flag
- ✅ Maintainer agrees with findings in §23.
- ✅ Decision: introduce a new config flag **`bot.llm.request-timeout`** (plus env var mapping) to control the `CompletableFuture.get(...)` timeout in `LlmExecutionSystem`.
  - Rationale: decouple “how long we wait for an in-flight request” from model/HTTP timeouts; unify later into a user-friendly timeout system.
  - Follow-up (future): consolidate all timeout-related flags into a clear hierarchy and document them.

## 24) Code Review Notes — ResponseRoutingSystem + MemoryPersistSystem (finality, intermediate steps, tool-loop implications)

### 24.1 ResponseRoutingSystem uses `ContextAttributes.LLM_RESPONSE` (good) but other systems still use string keys
- `ResponseRoutingSystem` reads `ContextAttributes.LLM_RESPONSE` and `ContextAttributes.LLM_ERROR`.
- `LlmExecutionSystem` and `MemoryPersistSystem` currently use `"llm.response"`.

**Decision:**
- Canonicalize on `ContextAttributes.LLM_RESPONSE` / `ContextAttributes.LLM_ERROR` everywhere.
- Add a temporary compatibility bridge during refactor.

### 24.2 Critical: “final response routing” is blocked when toolsExecuted && response.hasToolCalls
```java
Boolean toolsExecuted = context.getAttribute(ContextAttributes.TOOLS_EXECUTED);
if (Boolean.TRUE.equals(toolsExecuted) && response.hasToolCalls()) {
  return context; // waiting for next iteration
}
```
This embeds a loop contract into the router:
- It assumes that when tool calls exist, we are not done and should not send anything.
- It relies on `TOOLS_EXECUTED` meaning “a loop continuation will happen”.

**Decision:**
- Under ToolLoopSystem, `ResponseRoutingSystem` should only run when ToolLoopSystem declares the turn “final”.
- Replace `toolsExecuted && hasToolCalls` gating with an explicit `ContextAttributes.LOOP_DECISION` (or `FINAL_RESPONSE_READY`) set by ToolLoopSystem.
- Router should not need to infer loop state from tool-call fields.

### 24.3 Finding: ResponseRoutingSystem can persist assistant message differently depending on toolsExecuted
When sending text:
- if `!toolsExecuted`: `addAssistantMessage(session, content, response.getToolCalls())`
- else: `addAssistantMessage(session, content, null)`

Implication:
- Tool calls may be dropped from session history depending on a flag that is already overloaded.

**Decision:**
- Raw history must always preserve tool-call metadata when present (Variant A), independent of send-path.
- Session persistence of “assistant tool-call messages” vs “assistant final answers” should be owned by ToolLoopSystem / history writer, not by router.

### 24.4 Finding: Auto-mode path persists response content to session without routing
`handleAutoMode()` writes assistant message to session but sends nothing.

**Decision:**
- Keep as-is if auto-mode semantics require no output.
- Ensure ToolLoopSystem sets finality flags consistently so auto-mode does not accidentally persist intermediate tool-call steps as final answers.

### 24.5 Finding: MemoryPersistSystem uses `"llm.response"` and persists even intermediate tool-call steps
`MemoryPersistSystem` runs at order=50 (after tool execution) and does:
- last user msg + `context.getAttribute("llm.response")`
- truncates to daily notes.

This is dangerous with tool-loop:
- `llm.response` can be an intermediate “tool-call step” (assistant content may be empty; or may contain partial reasoning).
- In multi-iteration scenarios, memory may record the wrong assistant text (non-final).

**Decision:**
- Memory persistence should run only on **final** assistant answer for a user turn.
- Gate MemoryPersistSystem on `FINAL_RESPONSE_READY` (or `LOOP_DECISION.final=true`).
- Use `ContextAttributes.LLM_RESPONSE` (canonical) and/or a new `ContextAttributes.FINAL_ASSISTANT_TEXT`.

### 24.6 Finding: ResponseRoutingSystem skips routing if skill.transition.target != null
This is consistent with the current “pipeline depth” mechanism.

**Decision:**
- Under ToolLoopSystem, skill transitions and tool loops should share one explicit “continue” contract; router should only act when final.

### 24.7 Minor: router has its own 30s send timeout
- `channel.sendMessage(...).get(30, TimeUnit.SECONDS)`

**Decision:**
- Keep separate from LLM/tool timeouts; document later as “channel send timeout”.

## 25) Code Review Notes — ContextBuildingSystem + DynamicTierSystem (raw history boundaries, MCP tools lifecycle, tier decisions)

### 25.1 ContextBuildingSystem: good separation of concerns for prompt assembly, but it mutates loop-control attributes
- It consumes and clears `skill.transition.target`.

**Decision:**
- Under ToolLoopSystem, skill transitions are part of the explicit loop decision contract.
- ContextBuildingSystem should become a pure “context assembler” and not clear transition flags implicitly; ToolLoopSystem/loop controller should clear them.

### 25.2 MCP tool registration: currently registered into ToolExecutionSystem registry during context build
ContextBuildingSystem does:
- starts MCP client for active skill
- `toolExecutionSystem.registerTool(adapter)` for each MCP tool
- adds `ToolDefinition` to available tools list

Findings:
- Tool registry becomes a cross-iteration mutable global map.
- There is no visible cleanup/unregister when skill changes (possible tool leakage between skills/sessions).

**Decision:**
- Move MCP tool lifecycle to a dedicated service owned by ToolLoopSystem (or a ToolRegistry service) that can:
  - register tools for the *current request view* only, or
  - scope tools by session/skill, with explicit unregister on transition.
- Ensure tool availability list given to LLM matches the exact executable registry at that moment.

### 25.3 Raw history vs system prompt: ContextBuildingSystem only builds system prompt and available tools (good)
It does not rewrite `context.messages` or session history.

**Decision:**
- Keep this property: prompt building must not mutate raw message history.

### 25.4 RAG context injection uses context attribute `rag.context`
- RAG query is based on last user message.
- Injected into system prompt under `# Relevant Memory`.

**Decision:**
- For request-time view building, keep RAG context as an explicit “system prompt augmentation” rather than as messages.
- Consider adding diagnostics for RAG inclusion (length, source, query mode) as part of explainable truncation/reporting.

### 25.5 Tier resolution split between ContextBuildingSystem (iteration 0) and DynamicTierSystem (iteration >0)
- ContextBuildingSystem resolves tier only for iteration 0.
- DynamicTierSystem upgrades tier on later iterations based on tool usage signals.

Finding:
- With ToolLoopSystem moving iterations into a single “turn”, we may still want dynamic upgrades between tool steps.

**Decision:**
- Extract tier decision policy into a domain service used by ToolLoopSystem.
- Keep DynamicTierSystem logic but refactor it into a callable policy method that can run:
  - after a tool result is appended (within ToolLoopSystem), before next LLM call.

### 25.6 DynamicTierSystem assumes tool calls + tool messages exist as structured messages in `context.messages`
It scans messages after the last user message.

Implication:
- Supports our “raw history contains provider-specific tool-call fields” approach.
- But if view-builder masks tool calls into text for some providers, DynamicTier should still operate on raw history, not the masked view.

**Decision:**
- Dynamic tier decisions should read from raw history/events, not from provider-specific request view.

## 26) Code Review Notes — AutoCompactionSystem + InputSanitizationSystem (raw-history mutability, loop safety)

### 26.1 AutoCompactionSystem is intentionally destructive (session compaction) — must be treated as a controlled “GC” boundary
Behavior:
- Estimates tokens from total message content length.
- If above threshold, calls `SessionPort.compactWithSummary(...)` or `compactMessages(...)`.
- Then refreshes `context.messages` from `session.messages`.

Implications for ToolLoopSystem + Variant A:
- Compaction will delete intermediate tool-call/tool-result history (by design).
- This is acceptable as an internal garbage-collection mechanism, but it must be:
  - explicit in the architecture (“compaction boundary”), and
  - deterministic about what is kept (`keepLastMessages`).

**Decision:**
- Keep compaction as a deliberate mutating operation on *session* history.
- ToolLoopSystem should consider compaction as an external constraint:
  - do not rely on very old tool-call metadata being present.
  - prefer storing any long-lived critical state outside of raw chat history (e.g., plan storage, memory notes, artifacts).

### 26.2 Ordering is correct for safety (AutoCompaction runs before ContextBuilding)
- AutoCompaction at order=18 runs before context build at order=20, preventing prompt overflow early.

**Decision:**
- Preserve this ordering. In the future, view-builder truncation should reduce the need for compaction, but compaction remains as backstop.

### 26.3 Threshold is based on selected model tier (good) but selection is duplicated
`resolveMaxTokens()` resolves a model name from tier by reading router config again.

**Decision:**
- Once ToolLoopSystem owns model selection, expose the selected model on context (e.g. `ContextAttributes.SELECTED_MODEL`) so compaction can use it directly.

### 26.4 Compaction summary message creation must preserve provider-specific fields policy
- `CompactionService.createSummaryMessage(summary)` likely creates a new assistant message.

**Decision:**
- Ensure summary message format is provider-neutral (no tool-call metadata), and mark it in metadata (e.g. `message.metadata["compacted.summary"]=true`) so view-builder can treat it specially.

### 26.5 InputSanitizationSystem mutates the last user message content in-place
- It does `lastMessage.setContent(result.sanitizedInput())` on threats.

Implications:
- This violates strict “raw history immutable” concept, but it is security-critical.
- Also: it does not update session copy explicitly; it relies on `context.messages` and `session.messages` sharing object identity.

**Decision:**
- Treat sanitization as a security boundary where mutating user text is acceptable.
- Add metadata to the message instead of only context attributes:
  - store `originalContent` and `sanitizedContent` (or at least a flag) in `Message.metadata` for explainability/auditing.
- Avoid relying on shared object identity between `context.messages` and `session.messages`; prefer explicit session update or ensure they share references by design.

### 26.6 ToolLoopSystem integration note
- ToolLoopSystem should run **after** sanitization and compaction, so its “raw history” operates on already-sanitized and possibly-compacted session state.

## 27) Code Review Notes — PlanFinalizationSystem + RagIndexingSystem (finality assumptions, context keys)

### 27.1 PlanFinalizationSystem finalizes based on `llm.toolCalls` (string key) + `ContextAttributes.LLM_RESPONSE`
- `shouldProcess()` checks `context.getAttribute("llm.toolCalls")` to ensure no tool calls.
- It reads and mutates `ContextAttributes.LLM_RESPONSE` content by appending plan summary.

Findings:
- Another instance of mixed key usage: tool calls are read from `"llm.toolCalls"` not a typed attribute.
- It mutates the response content in-place (fine for plan UX) but assumes the response is final text.

**Decision:**
- Under ToolLoopSystem, the “no tool calls => finalize plan” trigger should be expressed as an explicit loop decision/state (e.g. `PLAN_MODE_COLLECTING` + `LLM_STEP_FINAL_TEXT`).
- Canonicalize tool-call storage (e.g. `ContextAttributes.LLM_TOOL_CALLS`), or better: ToolLoopSystem passes tool calls explicitly to plan intercept/finalize logic without relying on shared context attributes.
- Response mutation (append plan card) is acceptable, but should happen on the **final response object** that will be routed.

### 27.2 PlanFinalizationSystem publishes PlanReadyEvent and sets `PLAN_APPROVAL_NEEDED`
This is an important side-effect that should only happen once per plan.

**Decision:**
- ToolLoopSystem must ensure this system runs only once per plan finalization (avoid multi-iteration duplicate events).
- Consider adding idempotency guard in PlanService (e.g. finalize only if status=COLLECTING).

### 27.3 RagIndexingSystem uses `"llm.response"` (string key) and indexes potentially intermediate responses
- It reads `context.getAttribute("llm.response")`.
- It runs before ResponseRoutingSystem, after MemoryPersist.

Finding:
- Same problem as MemoryPersist: in a tool loop, `llm.response` may represent an intermediate tool-call step, not the final assistant answer.

**Decision:**
- Gate indexing to final answers only (same `FINAL_RESPONSE_READY` / `LOOP_DECISION.final=true` mechanism).
- Switch to canonical `ContextAttributes.LLM_RESPONSE` (or `FINAL_ASSISTANT_TEXT`).

### 27.4 RagIndexingSystem is fire-and-forget (good), but document format should avoid leaking tool-call noise
- It indexes `User:` + `Assistant:` text.

**Decision:**
- Once ToolLoopSystem is in place, ensure that the indexed assistant text is the **final user-visible answer**, not tool-call scaffolding.
- Optionally include high-level metadata (skill, planId) but exclude provider-specific tool IDs.

## 28) Code Review Notes — SkillPipelineSystem (auto-transition as loop continuation)

### 28.1 Finding: uses string keys and “tools.executed hack” to continue
- Reads `context.getAttribute("llm.response")` and checks `response.hasToolCalls()`.
- Sets `skill.transition.target` to trigger ContextBuildingSystem.
- Forces continuation by setting `tools.executed=true` and clearing `llm.response`/`llm.toolCalls`.

Implications:
- Confirms `tools.executed` is used as generic “continue loop” flag.
- Clearing `llm.response` is a destructive workaround to prevent ResponseRouting and other downstream systems from acting.

**Decision:**
- Under ToolLoopSystem, skill pipeline transitions must become an explicit loop action, not a side-effect:
  - `LoopAction.TRANSITION_SKILL(nextSkill)`
  - ToolLoopSystem performs the transition and decides whether to call LLM again.
- Remove dependence on `tools.executed` and clearing response fields.
- Canonicalize keys (no `"llm.response"` literal).

### 28.2 Finding: persists intermediate assistant message directly to session
- It writes the response text as an assistant message to session to preserve intermediate output.

Risks:
- This can cause double-persistence or ordering issues once ToolLoopSystem centralizes history writing.

**Decision:**
- Centralize all session writes in a single place (ToolLoopSystem / HistoryWriter).
- SkillPipelineSystem should return a structured transition decision instead of mutating session.

### 28.3 Finding: pipeline depth tracked in context only
- Uses `skill.pipeline.depth` context attribute and does not persist in session metadata.

Implications:
- On restarts or async message processing, depth may reset unexpectedly.

**Decision:**
- Move pipeline depth tracking into session metadata (persisted) or into ToolLoopSystem loop state persisted per-turn.

### 28.4 Alignment with ToolLoopSystem design
Skill transitions are conceptually similar to tool execution as “step outcomes” that may require another LLM call.

**Decision:**
- Unify continuation decisions for tools + skill pipeline under a single loop state machine in ToolLoopSystem.

## 29) Implementation roadmap (checkboxes) — migrate to ToolLoopSystem with Raw History + request-time views

### 29.1 Phase 0 — invariants, terminology, and attribute key cleanup

**Status update (done by design):**
- We **do not** store tool calls in a separate context attribute (`llm.toolCalls`). Tool calls live inside `ContextAttributes.LLM_RESPONSE` as `LlmResponse.toolCalls`.
- We **do not** use `tools.executed` as a loop continuation signal anymore.

- [ ] Introduce canonical `ContextAttributes` keys for:
  - [ ] `LLM_RESPONSE` (and remove `"llm.response"` usage everywhere)
  - [ ] `FINAL_ANSWER_READY` (already exists) — the canonical finality flag for a single pipeline pass
  - [ ] `LOOP_DECISION` (optional; structured) or `NEEDS_ANOTHER_LLM_PASS` (boolean) — only if we still need multi-pass orchestration outside ToolLoop
- [ ] Remove remaining legacy string-literal usages of `"llm.toolCalls"` and `"tools.executed"` from docs/tests (code usage already removed).
- [ ] Add regression tests that prevent re-introduction of legacy string-literal keys.

### 29.2 Phase 1 — data model support for provider-specific tool-call metadata (Variant A)
- [ ] Extend `Message.ToolCall` to preserve provider-specific fields:
  - [ ] `Map<String,Object> providerSpecificFields`
  - [ ] ensure persisted via session storage (JSON)
  - [ ] ensure not lost through serialization/deserialization
- [ ] Ensure tool-result messages preserve linking:
  - [ ] `toolCallId` must round-trip reliably
- [ ] Add tests for OpenAI-style `index`, Anthropic ids, etc. to survive persistence.

### 29.3 Phase 2 — request-time view builder (no destructive flatten/truncate)
- [ ] Create `LlmRequestViewBuilder` (domain service) that:
  - [ ] takes raw history + selected provider/model
  - [ ] produces provider-safe message list
  - [ ] performs masking of incompatible tool-call structures on model switch
  - [ ] emits diagnostics for explainable truncation/masking
- [ ] Remove/disable destructive `flattenOnModelSwitch()` in `LlmExecutionSystem`.
- [ ] Move emergency truncation into view builder (keep current as temporary backstop).

### 29.4 Phase 3 — HistoryWriter (single place that mutates session history)
- [ ] Create `HistoryWriter` (domain service) responsible for:
  - [ ] appending assistant tool-call messages
  - [ ] appending tool result messages (including synthetic outcomes)
  - [ ] appending final assistant answer
- [ ] Refactor systems to stop writing to `session.addMessage(...)` directly:
  - [ ] `ToolExecutionSystem`
  - [ ] `PlanInterceptSystem`
  - [ ] `SkillPipelineSystem`
  - [ ] `ResponseRoutingSystem` (should only send, not persist)

### 29.5 Phase 4 — ToolExecutor extraction
- [ ] Extract `ToolExecutor` from `ToolExecutionSystem`:
  - [ ] `execute(toolCall) -> ToolExecutionOutcome`
  - [ ] include timeout, sanitization of tool name, attachment extraction
  - [ ] include structured failure reasons for synthetic tool outcomes
- [ ] Make ToolExecutionSystem either a thin wrapper over ToolExecutor or remove it.

### 29.6 Phase 5 — ToolLoopSystem introduction (new owner of iterative tool loop)
- [ ] Implement `ToolLoopSystem` (hexagonal domain system) that orchestrates within a single “turn”:
  - [ ] LLM call(s) via LlmPort
  - [ ] tool execution via ToolExecutor
  - [ ] synthetic tool results (denied/skipped/timeout/maxDepth/deadline)
  - [ ] dynamic tier upgrades between steps (via policy service)
  - [ ] loop limits: max depth, deadline, repeat-guard
- [ ] Replace `AgentLoop.shouldContinueLoop()` contract:
  - [ ] remove dependency on `tools.executed && response.hasToolCalls`
  - [ ] continue based on `LOOP_DECISION` / ToolLoopSystem output

### 29.7 Phase 6 — finality gating for side-effect systems
- [ ] Gate these systems to run only on final responses:
  - [ ] `MemoryPersistSystem`
  - [ ] `RagIndexingSystem`
  - [ ] `ResponseRoutingSystem` (or ensure it only sees final)
- [ ] Ensure plan-related side effects are idempotent:
  - [ ] `PlanFinalizationSystem` must not publish duplicate `PlanReadyEvent`

### 29.8 Phase 7 — remove legacy components
- [ ] Remove `CustomLlmAdapter` completely (code + docs + config + tests)
- [ ] Remove/replace `flattenToolMessages` flows used for model-switch compatibility
- [ ] Remove `tools.executed` hacks in SkillPipeline/PlanIntercept/ToolExecution

### 29.9 Phase 8 — documentation
- [ ] Create/update docs describing the ToolLoop:
  - [ ] state machine, limits, synthetic tool outcomes
  - [ ] raw history vs request views
  - [ ] model switch masking rationale
- [ ] Document new timeout flag: `bot.llm.request-timeout` (and how it relates to model/HTTP timeouts)

## 30) System-by-system migration map (Architecture diff)

### 30.1 AgentLoop
- **Today:** owns iterations + uses `tools.executed && llm.response.hasToolCalls` as continue condition.
- **Target:** simplified outer orchestrator. Loop continuation/finality is decided by ToolLoopSystem via explicit decision attribute.
- **Actions:**
  - [ ] Replace `shouldContinueLoop()` with `LOOP_DECISION` / `FINAL_RESPONSE_READY` gating.
  - [ ] Remove ad-hoc attribute resets between iterations; ToolLoopSystem manages loop state.

### 30.2 ContextBuildingSystem
- **Today:** assembles system prompt + tool definitions; also clears `skill.transition.target`.
- **Target:** pure context assembler, no implicit loop-state mutation.
- **Actions:**
  - [ ] Stop clearing transition flags; let ToolLoopSystem clear them.
  - [ ] Move MCP tool lifecycle management out (see §30.6).

### 30.3 LlmExecutionSystem
- **Today:** builds request, selects model, calls LLM with hardcoded 120s timeout, writes `"llm.response"` + `"llm.toolCalls"`, does destructive `flattenOnModelSwitch()` and emergency truncation.
- **Target:** thin execution wrapper. No destructive history changes.
- **Actions:**
  - [ ] Canonicalize to `ContextAttributes.LLM_RESPONSE`.
  - [ ] Add `bot.llm.request-timeout` and wire to future.get(...) timeout.
  - [ ] Remove/disable `flattenOnModelSwitch()`; replace with request-time view builder.
  - [ ] Move truncation/masking to view builder; keep current emergency truncation as temporary backstop.

### 30.4 ToolExecutionSystem
- **Today:** executes tools + confirmation + writes assistant/tool messages + truncates + attachments + sets `tools.executed`.
- **Target:** extract executor; no session/history mutations.
- **Actions:**
  - [ ] Create `ToolExecutor` + `ToolExecutionOutcome`.
  - [ ] Move confirmation gating and synthetic outcomes to ToolLoopSystem (policy), executor only executes.
  - [ ] Centralize history writes in HistoryWriter.

### 30.5 PlanInterceptSystem / PlanFinalizationSystem
- **Today:** intercept uses synthetic tool messages; finalization mutates response and publishes event; relies on `"llm.toolCalls"`.
- **Target:** plan mode becomes a ToolLoopSystem sub-mode/policy.
- **Actions:**
  - [ ] Replace reliance on shared context keys with explicit ToolLoop step state.
  - [ ] Ensure idempotent finalization + event publishing.

### 30.6 MCP tools lifecycle
- **Today:** ContextBuildingSystem registers MCP tools into ToolExecutionSystem registry (global mutable map).
- **Target:** scoped registry/lifecycle owned by ToolLoopSystem (or dedicated ToolRegistry service).
- **Actions:**
  - [ ] Implement a `ToolRegistry` abstraction with explicit register/unregister per skill/session.
  - [ ] Ensure `availableTools` == executable registry for the request.

### 30.7 DynamicTierSystem
- **Today:** separate system runs on iteration>0, scans raw messages for tool usage signals.
- **Target:** tier policy callable between ToolLoop steps.
- **Actions:**
  - [ ] Extract logic into `TierUpgradePolicy` service.
  - [ ] ToolLoopSystem calls it after each tool outcome before next LLM call.

### 30.8 SkillPipelineSystem
- **Today:** auto-transition uses `tools.executed` hack and clears `llm.response` to avoid routing.
- **Target:** explicit loop action managed by ToolLoopSystem.
- **Actions:**
  - [ ] Refactor to emit a structured `LoopAction.TRANSITION_SKILL` decision.
  - [ ] Persist pipeline depth in session metadata.

### 30.9 ResponseRoutingSystem
- **Today:** tries to infer finality by checking `TOOLS_EXECUTED` + `response.hasToolCalls`; also writes assistant message to session.
- **Target:** final-only router; sending only; persistence elsewhere.
- **Actions:**
  - [ ] Gate on `FINAL_RESPONSE_READY`.
  - [ ] Remove session mutation responsibilities (delegate to HistoryWriter).

### 30.10 MemoryPersistSystem / RagIndexingSystem
- **Today:** use `"llm.response"` and can persist/index intermediate responses.
- **Target:** final-only side effects.
- **Actions:**
  - [ ] Gate on `FINAL_RESPONSE_READY`.
  - [ ] Switch to canonical `ContextAttributes.LLM_RESPONSE` or `FINAL_ASSISTANT_TEXT`.

### 30.11 AutoCompactionSystem
- **Today:** destructive session compaction (GC boundary).
- **Target:** keep as GC boundary; integrate with ToolLoop invariants.
- **Actions:**
  - [ ] Use selected model from ToolLoopSystem (avoid duplicating router logic).

### 30.12 InputSanitizationSystem
- **Today:** mutates last user message content in place; stores threats in context attrs.
- **Target:** keep sanitization; add auditability.
- **Actions:**
  - [ ] Store sanitization metadata on `Message.metadata` (original/sanitized/threats).

### 30.13 Legacy removal
- **Actions:**
  - [ ] Remove `CustomLlmAdapter` (code/docs/tests/config).

---

## 31) Testing strategy (TDD + BDD) for ToolLoopSystem (edge cases first)

### 31.1 Guiding principles
- **TDD:** implement new behavior only behind failing tests; keep each change small.
- **BDD:** describe loop behavior as scenarios; prefer readable GIVEN/WHEN/THEN style, but still run as unit/integration tests.
- **Test pyramid:**
  - many fast unit tests for policies/view-building/loop decisions
  - fewer integration tests for full pipeline turns
  - minimal end-to-end (channel ports can be mocked)

### 31.2 Test harness components to build
- [ ] `FakeLlmPort` that can be scripted as a sequence of responses:
  - tool-call response(s)
  - final answer response
  - errors/empty response
- [ ] `FakeToolExecutor` or tool registry with deterministic tool outputs:
  - success
  - timeout
  - exception
  - large output
- [ ] `InMemorySessionPort` for session persistence assertions.
- [ ] `TestHistoryWriter` (or real HistoryWriter) to assert raw-history events.

### 31.3 Core BDD scenarios (must-have)
Tool loop basics:
- [ ] GIVEN tool calls, WHEN tools succeed, THEN tool result messages are appended and another LLM pass occurs.
- [ ] GIVEN tool calls, WHEN confirmation is denied, THEN a **synthetic tool result** is appended and loop continues or finalizes deterministically.
- [ ] GIVEN tool calls, WHEN tool execution times out, THEN synthetic timeout result is appended and loop continues.

Finality + side effects:
- [ ] GIVEN intermediate tool-call step, THEN ResponseRouting/MemoryPersist/RagIndexing do **not** run.
- [ ] GIVEN final answer, THEN final-only systems run exactly once.

Provider-specific metadata preservation:
- [ ] GIVEN OpenAI tool calls with `index` and `id`, THEN these fields survive persistence and are available to view builder.
- [ ] GIVEN model switch across providers, THEN view builder masks incompatible tool call structures without mutating raw history.

Explainable truncation/masking:
- [ ] GIVEN oversized context, THEN request view is truncated with diagnostics and raw history unchanged.

Skill pipeline transitions:
- [ ] GIVEN active skill has nextSkill, WHEN final response is produced, THEN ToolLoop triggers transition decision and produces next LLM call without using `tools.executed` hacks.
- [ ] GIVEN pipeline depth exceeded, THEN loop stops with explicit reason.

Plan mode:
- [ ] GIVEN plan mode active and tool calls produced, THEN tools are not executed; synthetic planned results are appended; steps recorded.
- [ ] GIVEN plan mode and final text response, THEN plan is finalized once; event published once; response contains plan card.

### 31.4 Regression tests against old bugs
- [ ] Ensure no system persists/indexes/sends intermediate responses.
- [ ] Ensure no destructive flattening on model switch.
- [ ] Ensure attribute key canonicalization (no `"llm.response"` etc.) via static/grep-based test.

### 31.5 Suggested tech choices (Java)
- Unit tests: JUnit 5 + AssertJ.
- BDD style:
  - Option A: JUnit 5 + nested tests with GIVEN/WHEN/THEN naming
  - Option B: Cucumber (heavier; only if you want executable specs outside code)
- Property-based tests (optional but powerful for edge cases): jqwik.

### 31.6 TDD execution order (recommended)
- [ ] Start with `LlmRequestViewBuilder` tests (masking + truncation diagnostics).
- [ ] Add ToolExecutor + synthetic outcomes tests.
- [ ] Implement ToolLoopSystem core loop with scripted FakeLlmPort.
- [ ] Gate final-only systems and add integration tests for pipeline ordering.
