# Model Routing Guide

How the bot selects the right LLM model for each request through tier-based routing.

> **See also:** [Configuration Guide](CONFIGURATION.md#model-configuration) for environment variables, [Quick Start](QUICKSTART.md#enable-advanced-features) for basic setup, [Deployment Guide](DEPLOYMENT.md) for production configuration.

---

## Overview

The bot uses a **4-tier model selection** strategy that picks the most appropriate model based on task complexity. The tier is determined from multiple sources with clear priority:

1. **User preference** — set via `/tier` command or `set_tier` tool
2. **Skill override** — `model_tier` field in skill YAML frontmatter
3. **Dynamic upgrade** — `DynamicTierSystem` promotes to `coding` when code activity is detected mid-conversation
4. **Fallback** — `"balanced"` when no tier is explicitly set
```
User Message
    |
    v
[ContextBuildingSystem]  --- Resolves tier from user prefs / active skill
    |                        Priority: force+user > skill > user pref > balanced    v
[DynamicTierSystem]      --- May upgrade to "coding" if code activity detected
    |                        (only on iteration > 0, never downgrades)
    v
[ToolLoopExecutionSystem] --- Selects model + reasoning level based on modelTier
    |                         (via DefaultToolLoopSystem internal loop)
    v
LLM API Call
```

---

## Model Tiers

Four tiers map task complexity to model capabilities:

| Tier | Reasoning | Typical Use Cases | Default Model |
|------|-----------|-------------------|---------------|
| **balanced** | `medium` | Greetings, general questions, summarization (default/fallback) | `openai/gpt-5.1` |
| **smart** | `high` | Complex analysis, architecture decisions, multi-step planning | `openai/gpt-5.1` |
| **coding** | `medium` | Code generation, debugging, refactoring, code review | `openai/gpt-5.2` |
| **deep** | `xhigh` | PhD-level reasoning: proofs, scientific analysis, deep calculations | `openai/gpt-5.2` |

Each tier is independently configurable — you can assign any model from any supported provider to any tier. See [Multi-Provider Setup](#multi-provider-setup) below.

### Configuration

```bash
# Balanced tier (default/fallback)
BOT_ROUTER_BALANCED_MODEL=openai/gpt-5.1
BOT_ROUTER_BALANCED_MODEL_REASONING=medium

# Smart tier
BOT_ROUTER_SMART_MODEL=openai/gpt-5.1
BOT_ROUTER_SMART_MODEL_REASONING=high

# Coding tier
BOT_ROUTER_CODING_MODEL=openai/gpt-5.2
BOT_ROUTER_CODING_MODEL_REASONING=medium

# Deep tier (PhD-level reasoning)
BOT_ROUTER_DEEP_MODEL=openai/gpt-5.2
BOT_ROUTER_DEEP_MODEL_REASONING=xhigh

# Temperature (used only by models that support it — see models.json)
BOT_ROUTER_TEMPERATURE=0.7
```

> **Note:** Reasoning models (e.g., `gpt-5.1`, `o3`) ignore the temperature parameter. The `reasoningRequired` and `supportsTemperature` flags in `models.json` control this behavior. See [models.json Reference](#modelsjson-reference).

---

## How Tier Assignment Works

### Tier Priority

The tier is resolved in `ContextBuildingSystem` (order=20) on iteration 0 with the following priority:

| Priority | Source | Condition |
|----------|--------|-----------|
| 1 (highest) | **User preference + force** | `tierForce=true` and `modelTier` set in user preferences |
| 2 | **Skill `model_tier`** | Active skill has `model_tier` in YAML frontmatter |
| 3 | **User preference** | `modelTier` set in user preferences (without force) |
| 4 (lowest) | **Fallback** | `"balanced"` — when no tier is explicitly set |

**Force mode** locks the tier, preventing both skill overrides and DynamicTierSystem upgrades. This is useful when you want a specific model regardless of context.

### Setting Tier via `/tier` Command
```
/tier                    # Show current tier and force status
/tier coding             # Set tier to coding, clears force
/tier smart force        # Lock tier to smart (ignores skill overrides + dynamic upgrades)
```

**Key behavior:**
- `/tier <tier>` always clears the force flag (even if it was previously on)
- `/tier <tier> force` sets both the tier and locks it
- The setting persists across conversations (stored in user preferences)

### Setting Tier via `set_tier` Tool

The LLM can switch tiers mid-conversation using the `set_tier` tool:

```json
{
  "tier": "coding"
}
```
- If the user has `tierForce=true`, the tool returns an error: "Tier is locked by user"
- Otherwise, the tier is applied immediately for the current conversation
- The tool does NOT persist the change to user preferences (session-only)

### Skill `model_tier` Override

Skills can declare a preferred model tier in their YAML frontmatter:

```yaml
---
name: code-review
description: Review code changes
model_tier: coding
---
```

When a skill with `model_tier` is active:
- If the user has `tierForce=true`, the skill's tier is ignored
- Otherwise, the skill's tier takes precedence over the user's default preference
- A system prompt instruction informs the LLM about the tier switch

### Dynamic Tier Upgrade (Iteration > 0)

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
- Skips if current tier is already `coding` or `deep`
- Skips if user has `tierForce=true`
- Only runs when `bot.router.dynamic-tier-enabled=true` (default)

> **Source:** `DynamicTierSystem.java`

---

## Model Selection in ToolLoopExecutionSystem

`ToolLoopExecutionSystem` (order=30) delegates to `DefaultToolLoopSystem`, which translates the `modelTier` string into an actual model name and reasoning effort:

```java
switch (tier != null ? tier : "balanced") {
    case "coding" -> (codingModel, codingModelReasoning)
    case "smart"  -> (smartModel, smartModelReasoning)
    case "deep"   -> (deepModel, deepModelReasoning)
    default       -> (balancedModel, balancedModelReasoning)   // "balanced" or null
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

> **Note:** The tier name `"balanced"` maps to the balanced model config. If no tier is set (null), the balanced tier is used.

---

## Multi-Provider Setup

You can mix different LLM providers across tiers for cost optimization or capability access:

```bash
# Use balanced tier for standard tasks (default/fallback)
BOT_ROUTER_BALANCED_MODEL=openai/gpt-5.1
BOT_ROUTER_BALANCED_MODEL_REASONING=medium

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
- `ToolLoopExecutionSystem` — emergency truncation limits each message to 25% of context window (minimum 10K characters)

> **See:** [Configuration Guide — Advanced: models.json](CONFIGURATION.md#advanced-modelsjson) for adding custom models.

---

## Routing Configuration

Model routing is primarily configured by choosing models for each tier and enabling optional dynamic upgrades.

```bash
# Tier models
BOT_ROUTER_DEFAULT_MODEL=openai/gpt-5.1
BOT_ROUTER_SMART_MODEL=openai/gpt-5.1
BOT_ROUTER_CODING_MODEL=openai/gpt-5.2
BOT_ROUTER_DEEP_MODEL=openai/gpt-5.2

# Optional: upgrade to coding tier when coding activity is detected mid-run
BOT_ROUTER_DYNAMIC_TIER_ENABLED=true
```

---

## Tool Call ID Remapping

LLM providers have strict requirements for tool call identifiers and function names. When models switch mid-conversation (e.g., from a non-OpenAI provider to OpenAI due to tier change), stored tool call IDs and names from the previous provider may be incompatible with the new one. `Langchain4jAdapter` handles this transparently.

> **Source:** `Langchain4jAdapter.java`

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
- Summarize old messages via LLM (balanced model, low reasoning) using `CompactionService`
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

`DefaultToolLoopSystem` truncates individual tool results that exceed `maxToolResultChars` **before** they are added to conversation history.

> **Source:** `DefaultToolLoopSystem.java`

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

`ToolLoopExecutionSystem` (order=30) catches context overflow errors from the LLM provider and applies emergency per-message truncation as a last resort.

> **Source:** `DefaultToolLoopSystem.java`

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
              ToolLoopExecutionSystem (order=30)
              ┌─────────────────────────────────┐
              │  LLM call → tool execution loop │
              │                                 │
              │  Layer 2: Tool Result Truncation │
              │  Per-result. Truncates > 100K.  │
              │  Hint: "try a more specific     │
              │  query"                         │
              │                                 │
              │  Layer 3: Emergency Truncation   │
              │  On context_length_exceeded:    │
              │  per-message limit = 25% of     │
              │  context window. Truncates +    │
              │  retries once. Persists in      │
              │  session history.               │
              └─────────────────────────────────┘
```

---

## Architecture: Key Classes

| Class | Package | Order | Purpose |
|-------|---------|-------|---------|
| `ContextBuildingSystem` | `domain.system` | 20 | Resolves tier from user prefs / skill, builds system prompt |
| `DynamicTierSystem` | `domain.system` | 25 | Mid-conversation upgrade to coding tier |
| `ToolLoopExecutionSystem` | `domain.system` | 30 | LLM calls, tool execution loop, plan intercept, model selection, emergency truncation |
| `AutoCompactionSystem` | `domain.system` | 18 | Preventive context compaction before LLM call |
| `TierTool` | `tools` | — | LLM tool for switching tier mid-conversation |
| `CommandRouter` | `adapter.inbound.command` | — | `/tier` command handler || `LlmAdapterFactory` | `adapter.outbound.llm` | — | Provider adapter selection |
| `Langchain4jAdapter` | `adapter.outbound.llm` | — | OpenAI/Anthropic integration, ID remapping, name sanitization |
| `ModelConfigService` | `infrastructure.config` | — | Model capability lookups from models.json |
| `AgentContext` | `domain.model` | — | Runtime state: holds `modelTier`, `activeSkill` |

---

## Debugging Model Routing

### Log messages

The routing system produces detailed logs at INFO level:

```
[ContextBuilding] Resolved tier: coding (source: skill 'code-review')
[LLM] Model tier: coding, selected model: openai/gpt-5.2, reasoning: medium
```

On subsequent iterations with dynamic upgrade:

```
[DynamicTier] Detected coding activity, upgrading tier: balanced -> coding
[LLM] Model tier: coding, selected model: openai/gpt-5.2, reasoning: medium
```

User-initiated tier changes:

```
[TierTool] Tier changed to: smart
[LLM] Model tier: smart, selected model: openai/gpt-5.1, reasoning: high
```

### The `/status` command

Use `/status` in Telegram to check active configuration, including current model tier. See [Configuration Guide — Configuration Validation](CONFIGURATION.md#configuration-validation).

### The `/tier` command

Use `/tier` to check or change the current tier:

```
/tier              # Show current: "Tier: balanced, Force: off"
/tier coding       # Switch to coding tier
/tier smart force  # Lock to smart tier (ignores skill overrides + dynamic upgrades)
```

---

## Examples

### Greeting (balanced tier — default)

```
User: "Hi, how are you?"

ContextBuildingSystem:
  No user tier preference, no active skill
  Tier: null → balanced (fallback)

ToolLoopExecutionSystem:
  Tier: balanced → openai/gpt-5.1 (reasoning: medium)
```

### Code generation (coding tier from skill)

```
Skill "code-review" has model_tier: coding

User: "Review this PR"

ContextBuildingSystem:
  Active skill: code-review (model_tier: coding)
  No user force → use skill tier
  Tier: coding

ToolLoopExecutionSystem:
  Tier: coding → openai/gpt-5.2 (reasoning: medium)
```

### User-forced tier overrides skill

```
User ran: /tier smart force

Skill "code-review" has model_tier: coding

User: "Review this PR"

ContextBuildingSystem:
  User preference: smart (force=true)
  → Skill's coding tier is ignored
  Tier: smart

ToolLoopExecutionSystem:
  Tier: smart → openai/gpt-5.1 (reasoning: high)
```

### Dynamic upgrade (balanced -> coding)

```
User: "Help me with this project"

ContextBuildingSystem:
  No user tier, no skill tier → balanced

ToolLoopExecutionSystem (iteration 0):
  Tier: balanced → openai/gpt-5.1 (reasoning: medium)

--- LLM calls filesystem.write_file("app.py", ...) ---

DynamicTierSystem (iteration 1):
  Detected: filesystem write on .py file → upgrade to "coding"

ToolLoopExecutionSystem (iteration 1):
  Tier: coding → openai/gpt-5.2 (reasoning: medium)
```

### LLM switches tier via tool

```
User: "This needs deep analysis"

LLM calls set_tier({"tier": "deep"})
  → context.modelTier = "deep"

ToolLoopExecutionSystem (next iteration):
  Tier: deep → openai/gpt-5.2 (reasoning: xhigh)
```
