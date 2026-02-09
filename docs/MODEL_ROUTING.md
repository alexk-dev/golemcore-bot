# Model Routing Guide

How the bot selects the right LLM model for each request through intelligent tier-based routing.

> **See also:** [Configuration Guide](CONFIGURATION.md#model-configuration) for environment variables, [Quick Start](QUICKSTART.md#enable-advanced-features) for basic setup, [Deployment Guide](DEPLOYMENT.md) for production configuration.

---

## Overview

The bot uses a **4-tier model selection** strategy that automatically picks the most appropriate model based on task complexity. The tier is determined in two phases:

1. **Initial assignment** (iteration 0) — `SkillRoutingSystem` uses hybrid semantic + LLM classification to set the tier
2. **Dynamic upgrade** (iteration > 0) — `DynamicTierSystem` can promote to the `coding` tier when code-related activity is detected mid-conversation

```
User Message
    |
    v
[SkillRoutingSystem]  ─── Sets initial modelTier (fast/default/smart/coding)
    |                      via semantic search + LLM classifier
    v
[ContextBuildingSystem]
    |
    v
[DynamicTierSystem]   ─── May upgrade to "coding" if code activity detected
    |                      (only on iteration > 0, never downgrades)
    v
[LlmExecutionSystem]  ─── Selects model + reasoning level based on modelTier
    |
    v
LLM API Call
```

---

## Model Tiers

Four tiers map task complexity to model capabilities:

| Tier | Reasoning | Typical Use Cases | Default Model |
|------|-----------|-------------------|---------------|
| **fast** | `low` | Greetings, simple Q&A, translations | `openai/gpt-5.1` |
| **default** | `medium` | General questions, summarization | `openai/gpt-5.1` |
| **smart** | `high` | Complex analysis, architecture decisions, multi-step planning | `openai/gpt-5.1` |
| **coding** | `medium` | Code generation, debugging, refactoring, code review | `openai/gpt-5.2` |

Each tier is independently configurable — you can assign any model from any supported provider to any tier. See [Multi-Provider Setup](#multi-provider-setup) below.

### Configuration

```bash
# Fast tier
BOT_ROUTER_FAST_MODEL=openai/gpt-5.1
BOT_ROUTER_FAST_MODEL_REASONING=low

# Default tier
BOT_ROUTER_DEFAULT_MODEL=openai/gpt-5.1
BOT_ROUTER_DEFAULT_MODEL_REASONING=medium

# Smart tier
BOT_ROUTER_SMART_MODEL=openai/gpt-5.1
BOT_ROUTER_SMART_MODEL_REASONING=high

# Coding tier
BOT_ROUTER_CODING_MODEL=openai/gpt-5.2
BOT_ROUTER_CODING_MODEL_REASONING=medium

# Temperature (used only by models that support it — see models.json)
BOT_ROUTER_TEMPERATURE=0.7
```

> **Note:** Reasoning models (e.g., `gpt-5.1`, `o3`) ignore the temperature parameter. The `reasoningRequired` and `supportsTemperature` flags in `models.json` control this behavior. See [models.json Reference](#modelsjson-reference).

---

## How Tier Assignment Works

### Phase 1: Skill Routing (Iteration 0)

On the first iteration of the agent loop, `SkillRoutingSystem` (order=15) determines both the active skill and the model tier. It runs **before** `ContextBuildingSystem` (order=20).

The routing follows a 3-stage hybrid approach:

```
User Message
    |
    v
[Stage 0: Fragmented Input Detection]
    |  MessageContextAggregator aggregates split messages
    |  using 6 signals: too_short, back_reference, time_window,
    |  continuation, lowercase_start, previous_incomplete
    |  Threshold: >= 2 signals = fragmented
    v
[Stage 1: Semantic Pre-filter]
    |  Query embedding via EmbeddingPort (text-embedding-3-small)
    |  Cosine similarity against indexed skill embeddings
    |  Returns top-K candidates (K=5, min_score=0.6)
    v
    +-- Score >= 0.95? --> Skip LLM, use semantic result (tier="balanced")
    |
    +-- Score < 0.95 and classifier enabled?
        |
        v
[Stage 2: LLM Classifier]
        |  Fast model (e.g., gpt-5-mini) receives:
        |  - Candidate skills with descriptions and scores
        |  - Last 3 conversation messages for context
        |  - User's (possibly aggregated) query
        |  Returns JSON: {"skill", "confidence", "model_tier", "reason"}
        v
    SkillMatchResult with selectedSkill + modelTier
```

The LLM classifier determines the model tier using these guidelines from its system prompt:

| Tier | Classifier Criteria |
|------|-------------------|
| `fast` | Simple tasks, greetings, quick answers, translations |
| `balanced` | Standard tasks, summarization, general questions |
| `coding` | Programming tasks: code generation, debugging, refactoring, code review, writing tests |
| `smart` | Complex reasoning, architecture decisions, security analysis, multi-step planning |

> **Source:** `LlmSkillClassifier.java:36-50`

**Fallback behavior:**
- If no semantic candidates found and classifier is disabled: tier defaults to `fast`
- If LLM classifier fails: falls back to semantic top candidate with tier `balanced`
- If skill matcher is entirely disabled: tier is `null`, which maps to `default` in `LlmExecutionSystem`

### Phase 2: Dynamic Tier Upgrade (Iteration > 0)

`DynamicTierSystem` (order=25) runs on subsequent iterations of the agent loop (after tool calls). It scans **only messages from the current loop run** (after the last user message) to detect coding activity.

**Key constraint:** It never scans old conversation history, only the current run's assistant messages and tool results. This prevents false positives from past coding discussions.

**Upgrade signals:**

| Signal Type | Detection Logic |
|-------------|-----------------|
| **File operations on code files** | `filesystem` / `file_system` tool calls with `write_file` or `read_file` on files ending in `.py`, `.js`, `.ts`, `.java`, `.go`, `.rs`, `.rb`, `.sh`, `.c`, `.cpp`, `.cs`, `.kt`, `.scala`, `.swift`, `.lua`, `.r`, `.pl`, `.php`, `.sql`, `.yaml`, `.yml`, `.toml`, `.gradle`, `.cmake`, `.makefile`, plus `Makefile` and `Dockerfile` |
| **Code-related shell commands** | `shell` tool calls starting with `python`, `node`, `npm`, `npx`, `pip`, `mvn`, `gradle`, `gcc`, `g++`, `cargo`, `go`, `rustc`, `pytest`, `make`, `cmake`, `javac`, `dotnet`, `ruby`, `tsc`, `webpack`, `esbuild`, `jest`, `mocha`, `yarn` |
| **Stack traces in tool results** | Tool result messages containing `Traceback`, `SyntaxError`, `TypeError`, `NullPointerException`, `at com.`, `at org.`, `panic:`, `error[E`, etc. |

**Rules:**
- Only **upgrades** to `coding` — never downgrades (prevents oscillation)
- Skips if current tier is already `coding`
- Only runs when `bot.router.dynamic-tier-enabled=true` (default)

> **Source:** `DynamicTierSystem.java:46-66`

---

## Model Selection in LlmExecutionSystem

`LlmExecutionSystem` (order=30) translates the `modelTier` string into an actual model name and reasoning effort:

```java
// LlmExecutionSystem.java:215-224
switch (tier != null ? tier : "balanced") {
    case "fast"   -> (fastModel, fastModelReasoning)
    case "coding" -> (codingModel, codingModelReasoning)
    case "smart"  -> (smartModel, smartModelReasoning)
    default       -> (defaultModel, defaultModelReasoning)   // "balanced" or null
}
```

The selected model and reasoning effort are passed to the LLM adapter via `LlmRequest`:

```
LlmRequest {
    model: "openai/gpt-5.2"
    reasoningEffort: "medium"
    systemPrompt: "..."
    messages: [...]
    tools: [...]
}
```

> **Note:** The tier name `"balanced"` from the classifier maps to `default` in the switch. If no tier is set (null), the default tier is used.

---

## Multi-Provider Setup

You can mix different LLM providers across tiers for cost optimization or capability access:

```bash
# Use fast/cheap model for simple tasks
BOT_ROUTER_FAST_MODEL=openai/gpt-5.1
BOT_ROUTER_FAST_MODEL_REASONING=low

# Use Anthropic for complex reasoning
BOT_ROUTER_SMART_MODEL=anthropic/claude-opus-4-6
BOT_ROUTER_SMART_MODEL_REASONING=high

# Use dedicated coding model
BOT_ROUTER_CODING_MODEL=openai/gpt-5.2
BOT_ROUTER_CODING_MODEL_REASONING=medium
```

Requires API keys for all providers used:

```bash
OPENAI_API_KEY=sk-proj-...
ANTHROPIC_API_KEY=sk-ant-...
```

The `Langchain4jAdapter` creates per-request model instances when the requested model differs from the default. Provider detection is based on the model name prefix (e.g., `anthropic/claude-opus-4-6` routes to the Anthropic adapter).

> **See:** [Configuration Guide — Multi-Provider Setup](CONFIGURATION.md#multi-provider-setup) for Docker Compose examples.

---

## models.json Reference

Model capabilities are defined in `models.json` at the project root. Each entry specifies:

| Field | Type | Description |
|-------|------|-------------|
| `provider` | string | Provider name: `openai`, `anthropic`, `zhipu`, `qwen`, `cerebras`, `deepinfra` |
| `reasoningRequired` | boolean | Whether the model requires a `reasoning` parameter instead of `temperature` |
| `supportsTemperature` | boolean | Whether to send the `temperature` parameter (reasoning models typically don't support it) |
| `maxInputTokens` | integer | Maximum input context window size in tokens (used for emergency truncation calculations) |

**Example entry:**

```json
{
  "models": {
    "gpt-5.1": {
      "provider": "openai",
      "reasoningRequired": true,
      "supportsTemperature": false,
      "maxInputTokens": 1000000
    },
    "claude-sonnet-4-20250514": {
      "provider": "anthropic",
      "reasoningRequired": false,
      "supportsTemperature": true,
      "maxInputTokens": 200000
    }
  },
  "defaults": {
    "provider": "openai",
    "reasoningRequired": false,
    "supportsTemperature": true,
    "maxInputTokens": 128000
  }
}
```

**Model name resolution** in `ModelConfigService`:

1. Exact match (e.g., `gpt-5.1`)
2. Strip provider prefix (e.g., `openai/gpt-5.1` becomes `gpt-5.1`)
3. Prefix match (e.g., `gpt-5.1-preview` matches `gpt-5.1`)
4. Fall back to `defaults` section

The `maxInputTokens` value is used by:
- `AutoCompactionSystem` — triggers compaction at 80% of context window
- `LlmExecutionSystem` — emergency truncation limits each message to 25% of context window (minimum 10K characters)

> **See:** [Configuration Guide — Advanced: models.json](CONFIGURATION.md#advanced-modelsjson) for adding custom models.

---

## Skill Matcher Configuration

The hybrid skill routing system is opt-in and requires an embedding provider (typically OpenAI):

```bash
# Enable skill routing
SKILL_MATCHER_ENABLED=true

# Semantic search (Stage 1)
BOT_ROUTER_SKILL_MATCHER_EMBEDDING_MODEL=text-embedding-3-small
BOT_ROUTER_SKILL_MATCHER_SEMANTIC_SEARCH_TOP_K=5
BOT_ROUTER_SKILL_MATCHER_SEMANTIC_SEARCH_MIN_SCORE=0.6

# LLM classifier (Stage 2)
BOT_ROUTER_SKILL_MATCHER_CLASSIFIER_ENABLED=true
BOT_ROUTER_SKILL_MATCHER_CLASSIFIER_MODEL=openai/gpt-5-mini
BOT_ROUTER_SKILL_MATCHER_SKIP_CLASSIFIER_THRESHOLD=0.95

# Cache
BOT_ROUTER_SKILL_MATCHER_CACHE_ENABLED=true
BOT_ROUTER_SKILL_MATCHER_CACHE_TTL_MINUTES=60
BOT_ROUTER_SKILL_MATCHER_CACHE_MAX_SIZE=1000

# Timeouts
BOT_ROUTER_SKILL_MATCHER_ROUTING_TIMEOUT_MS=15000
BOT_ROUTER_SKILL_MATCHER_CLASSIFIER_TIMEOUT_MS=10000
```

Cached results are near-instant. The classifier is skipped for high-confidence semantic matches (score > 0.95).

> **See:** [Configuration Guide — Skill Routing](CONFIGURATION.md#skill-routing) for a concise reference.

---

## Tool Call ID Remapping

LLM providers have strict requirements for tool call identifiers and function names. When models switch mid-conversation (e.g., from a non-OpenAI provider to OpenAI due to tier change), stored tool call IDs and names from the previous provider may be incompatible with the new one. `Langchain4jAdapter` handles this transparently.

> **Source:** `Langchain4jAdapter.java:345-422`

### Function Name Sanitization

OpenAI requires function names to match `^[a-zA-Z0-9_-]+$`. Non-OpenAI providers (e.g., DeepInfra) may return names containing dots or other characters that get stored in conversation history.

```
Original name:     "com.example.search.tool"
Sanitized name:    "com_example_search_tool"
```

`sanitizeFunctionName()` replaces any character outside `[a-zA-Z0-9_-]` with `_`. This is applied to both:
- Assistant messages: tool call names in `toolExecutionRequests`
- Tool result messages: the `toolName` field

If the name is null, it defaults to `"unknown"`.

### Tool Call ID Remapping

Provider-generated tool call IDs can violate two constraints:
- **Length:** IDs exceeding 40 characters (the `MAX_TOOL_CALL_ID_LENGTH` constant)
- **Characters:** IDs containing characters outside `[a-zA-Z0-9_-]` (e.g., dots from non-OpenAI providers)

When either condition is detected, the adapter builds a **consistent ID remap table** before converting any messages:

```
Original ID:       "chatcmpl-abc123.tool.call.very-long-identifier-from-provider"
Remapped ID:       "call_a1b2c3d4e5f6a1b2c3d4e5f6"  (UUID-based, 29 chars)
```

The remap is computed in a **first pass** over all messages, then applied consistently to both:
- Assistant messages: `toolCalls[].id` field
- Tool result messages: `toolCallId` field

This ensures the assistant's tool call IDs always match the corresponding tool result IDs, even after remapping.

**Why this matters:** Without remapping, switching from a model that generated long/non-standard IDs to OpenAI would cause `400 Bad Request` errors because the tool result IDs wouldn't match what OpenAI expects.

### Conversion Flow

```
LlmRequest.messages
    |
    v
[Pass 1: Build ID remap table]
    |  Scan all messages for tool calls with:
    |  - ID length > 40 chars, or
    |  - ID contains chars outside [a-zA-Z0-9_-]
    |  Generate: originalId -> "call_" + UUID(24 chars)
    v
[Pass 2: Convert messages]
    |  For each message:
    |  - assistant + toolCalls: remap IDs, sanitize names
    |  - tool results: remap toolCallId, sanitize toolName
    |  - user/system: pass through
    v
List<ChatMessage> (langchain4j format)
```

---

## Large Input Truncation

The bot employs a 3-layer defense to prevent context window overflow. Each layer operates at a different stage of the pipeline and catches progressively more severe cases.

### Layer 1: Auto-Compaction (Preventive)

`AutoCompactionSystem` (order=18) runs **before** the LLM call to proactively shrink the conversation history.

> **Source:** `AutoCompactionSystem.java`

**Token estimation:**
```
estimatedTokens = sum(message.content.length) / charsPerToken + systemPromptOverheadTokens
```

Where `charsPerToken` defaults to 3.5 and `systemPromptOverheadTokens` defaults to 8000.

**Threshold resolution:**
1. Look up the current model's `maxInputTokens` from `models.json` (via `ModelConfigService`)
2. Apply 80% safety margin: `modelMax * 0.8`
3. Cap by config: `min(modelThreshold, bot.auto-compact.max-context-tokens)`
4. If model lookup fails, fall back to `bot.auto-compact.max-context-tokens` (default 128K)

**Compaction strategy:**
- Summarize old messages via LLM (fast model, low reasoning) using `CompactionService`
- Replace old messages with a `[Conversation summary]` system message + last N messages (default N=10)
- If LLM unavailable, fall back to simple truncation (drop oldest, keep last N)

**Configuration:**
```bash
BOT_AUTO_COMPACT_ENABLED=true
BOT_AUTO_COMPACT_MAX_CONTEXT_TOKENS=128000
BOT_AUTO_COMPACT_KEEP_LAST_MESSAGES=10
BOT_AUTO_COMPACT_SYSTEM_PROMPT_OVERHEAD_TOKENS=8000
BOT_AUTO_COMPACT_CHARS_PER_TOKEN=3.5
```

> **See:** [Configuration Guide — Auto-Compaction](CONFIGURATION.md#auto-compaction)

### Layer 2: Tool Result Truncation (Per-Result)

`ToolExecutionSystem` (order=40) truncates individual tool results that exceed `maxToolResultChars` **before** they are added to conversation history.

> **Source:** `ToolExecutionSystem.java:229-241`

When a tool result's content exceeds the limit (default 100,000 characters):

```
[first maxChars characters of content...]

[OUTPUT TRUNCATED: 500000 chars total, showing first 100000 chars.
The full result is too large for the context window.
Try a more specific query, use filtering/pagination,
or process the data in smaller chunks.]
```

The suffix length is subtracted from the cut point so the final output stays within the limit. This hint enables the LLM to self-correct by retrying with a more specific query.

**Configuration:**
```bash
BOT_AUTO_COMPACT_MAX_TOOL_RESULT_CHARS=100000  # default: 100K chars
```

### Layer 3: Emergency Truncation (Error Recovery)

`LlmExecutionSystem` (order=30) catches context overflow errors from the LLM provider and applies emergency per-message truncation as a last resort.

> **Source:** `LlmExecutionSystem.java:231-315`

**Error detection** — matches these patterns in the exception message chain:
- `exceeds maximum input length`
- `context_length_exceeded`
- `maximum context length`
- `too many tokens`
- `request too large`

**Per-message limit calculation:**
```
maxInputTokens = ModelConfigService.getMaxInputTokens(selectedModel)
maxMessageChars = maxInputTokens * 3.5 * 0.25    // 25% of context window per message
maxMessageChars = max(maxMessageChars, 10000)      // floor: 10K chars
```

For example, with `gpt-5.1` (1,000,000 input tokens):
```
maxMessageChars = 1,000,000 * 3.5 * 0.25 = 875,000 chars per message
```

With `gpt-4o` (128,000 input tokens):
```
maxMessageChars = 128,000 * 3.5 * 0.25 = 112,000 chars per message
```

**Truncation format:**
```
[first cutPoint characters of message...]

[EMERGENCY TRUNCATED: 500000 chars total. Try a more specific query to get smaller results.]
```

**Recovery flow:**
1. Catch `context_length_exceeded` from LLM provider
2. Scan all messages, truncate any exceeding per-message limit
3. Also truncate in session history (so truncation persists across requests)
4. Rebuild LlmRequest and retry once
5. If retry also fails, set `llm.error` for `ResponseRoutingSystem`

### Summary: Defense Layers

```
                     Conversation History
                            |
                            v
Layer 1: AutoCompactionSystem (order=18)
         Preventive. Estimates tokens, compacts if > 80% of model max.
         Strategy: LLM summary + keep last N messages.
                            |
                            v
                     LLM Execution (order=30)
                            |
                            v
              Tool Execution (order=40) ──> Loop back to LLM
                   |
Layer 2: Tool Result Truncation
         Per-result. Truncates results > 100K chars.
         Hint: "try a more specific query"
                   |
                   v
              LLM Execution (retry)
                   |
         (if context_length_exceeded)
                   |
                   v
Layer 3: Emergency Truncation
         Error recovery. Per-message limit = 25% of context window.
         Truncates + retries once. Persists in session history.
```

---

## Architecture: Key Classes

| Class | Package | Order | Purpose |
|-------|---------|-------|---------|
| `SkillRoutingSystem` | `domain.system` | 15 | Initial tier + skill assignment via hybrid matcher |
| `AutoCompactionSystem` | `domain.system` | 18 | Preventive context compaction before LLM call |
| `DynamicTierSystem` | `domain.system` | 25 | Mid-conversation upgrade to coding tier |
| `LlmExecutionSystem` | `domain.system` | 30 | Model selection by tier, LLM API call, emergency truncation |
| `ToolExecutionSystem` | `domain.system` | 40 | Tool execution, result truncation, attachment extraction |
| `HybridSkillMatcher` | `routing` | — | Two-stage semantic + LLM matching with cache |
| `LlmSkillClassifier` | `routing` | — | LLM-based skill/tier classification |
| `SkillEmbeddingStore` | `routing` | — | In-memory skill embedding index |
| `MessageContextAggregator` | `routing` | — | Fragmented input detection and aggregation |
| `LlmAdapterFactory` | `adapter.outbound.llm` | — | Provider adapter selection |
| `Langchain4jAdapter` | `adapter.outbound.llm` | — | OpenAI/Anthropic integration, ID remapping, name sanitization |
| `ModelConfigService` | `infrastructure.config` | — | Model capability lookups from models.json |
| `AgentContext` | `domain.model` | — | Runtime state: holds `modelTier`, `activeSkill` |

---

## Debugging Model Routing

### Log messages

The routing system produces detailed logs at INFO level:

```
[SkillRouting] Routing query: 'write a Python function for sorting'
[SkillRouting] Fragmentation analysis: fragmented=false, signals=[]
[SkillMatcher] Stage 1: Running semantic search...
[SkillMatcher] Semantic search returned 3 candidates
[SkillMatcher] Top candidate: coding-assistant (score: 0.870), threshold: 0.95
[SkillMatcher] Stage 2: Running LLM classifier...
[Classifier] Sending request to LLM...
[Classifier] LLM responded
[Classifier] Parsed result: skill=coding-assistant, confidence=0.92, tier=coding
[SkillRouting] MATCHED: skill=coding-assistant, confidence=0.92, tier=coding, cached=false, llmUsed=true
[LLM] Model tier: coding, selected model: openai/gpt-5.2, reasoning: medium
```

On subsequent iterations with dynamic upgrade:

```
[DynamicTier] Detected coding activity, upgrading tier: balanced -> coding
[LLM] Model tier: coding, selected model: openai/gpt-5.2, reasoning: medium
```

### The `/status` command

Use `/status` in Telegram to check active configuration, including current model tier and routing state. See [Configuration Guide — Configuration Validation](CONFIGURATION.md#configuration-validation).

---

## Examples

### Greeting (fast tier)

```
User: "Hi, how are you?"

SkillRoutingSystem:
  Semantic search: no candidates above min_score 0.6
  LLM classifier: {"skill": "none", "model_tier": "fast", "reason": "Simple greeting"}

LlmExecutionSystem:
  Tier: fast → openai/gpt-5.1 (reasoning: low)
```

### Code generation (coding tier from classifier)

```
User: "Write a Python function to parse CSV files"

SkillRoutingSystem:
  Semantic: coding-assistant (0.87), general (0.62)
  LLM classifier: {"skill": "coding-assistant", "model_tier": "coding", "reason": "Code generation task"}

LlmExecutionSystem:
  Tier: coding → openai/gpt-5.2 (reasoning: medium)
```

### Dynamic upgrade (default -> coding)

```
User: "Help me with this project"

SkillRoutingSystem:
  LLM classifier: {"skill": "general", "model_tier": "balanced", "reason": "General help request"}

LlmExecutionSystem (iteration 0):
  Tier: balanced → openai/gpt-5.1 (reasoning: medium)

--- LLM calls filesystem.write_file("app.py", ...) ---

DynamicTierSystem (iteration 1):
  Detected: filesystem write on .py file → upgrade to "coding"

LlmExecutionSystem (iteration 1):
  Tier: coding → openai/gpt-5.2 (reasoning: medium)
```

### Fragmented input aggregation

```
User: "check the code"        (t=0s)
User: "in main.py"            (t=3s)
User: "find bugs"             (t=7s)

MessageContextAggregator:
  Signals: [too_short, within_time_window, starts_lowercase]
  → FRAGMENTED (3 signals >= 2)
  Aggregated query: "check the code in main.py find bugs"

SkillRoutingSystem:
  Uses aggregated query for routing → skill=debug, tier=coding
```
