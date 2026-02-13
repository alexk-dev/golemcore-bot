# Tool Loop (ToolLoopSystem)

This document describes how **ToolLoopSystem** executes a single agent turn as a
self-contained internal loop:

```
LLM -> (tool_calls?) -> tools -> LLM -> ... -> final answer
```

The key design principle is:

> **Raw conversation history is immutable.**
> Any provider-specific normalization (flattening/masking) is applied only to a
> **request-time view**, right before calling the LLM.

---

## 1) Lifecycle of a single turn

A **turn** is processed by `DefaultToolLoopSystem.processTurn(context)` and may
contain multiple internal LLM calls.

### High-level steps

1. **Select target model**
   - Model is selected from the configured tier routing (based on
     `context.modelTier`).

2. **Build request-time conversation view**
   - A safe projection of messages is built for the *target model*.
   - If the model/provider changes, tool-call artifacts are masked to avoid
     sending provider-specific fields that the target model may not support.

3. **Call LLM**
   - `LlmPort.chat(LlmRequest)` is invoked.

4. **Handle the LLM response**
   - If the response contains **tool calls**, execute tools and append tool
     result messages.
   - If the response contains **no tool calls**, it is treated as the final
     assistant answer and the turn ends.

5. **Stop conditions**
   - The loop may stop because of limits/policy. When that happens,
     ToolLoopSystem produces a “safe stop” output and ensures the conversation
     is not left with dangling tool calls.

---

## 2) Stop conditions

ToolLoopSystem will stop the internal loop when one of these conditions is met:

- **Max LLM calls reached** (`bot.tool-loop.max-llm-calls`)
- **Max tool executions reached** (`bot.tool-loop.max-tool-executions`)
- **Deadline exceeded** (`bot.tool-loop.deadline-ms`)
- **Stop-on-tool-failure** policy (if enabled)
- **Stop-on-confirmation-denied** policy (if enabled)
- **Stop-on-policy-denied** policy (if enabled)

All stop paths are unified: they go through the same internal stop handling
(`stopTurn(...)`) to ensure consistent behavior.

---

## 3) Synthetic tool results

A common failure mode in tool-using agents is leaving the conversation in a
broken state:

- The assistant produced `tool_calls`.
- But the corresponding `tool` messages never arrive (because we stopped, hit
  a deadline, or the tool was denied).

To avoid “dangling tool calls”, ToolLoopSystem appends **synthetic tool result**
messages when stopping.

### When synthetic tool results are written

Synthetic tool results are produced when ToolLoopSystem stops the loop while
there are pending tool calls that did not get real tool results.

Examples:

- Deadline exceeded while tool calls are pending
- Max LLM calls reached while tool calls are pending
- Tool execution throws an exception
- Tool execution returns a failure outcome and policy says to stop
- Confirmation denied / policy denied and policy says to stop

### Failure kinds

Synthetic tool results may be marked with a machine-readable failure kind
(e.g. confirmation denied vs policy denied), so stop policies do not rely on
parsing human-readable error strings.

---

## 4) Model switch masking (request-time view; raw history immutable)

Some providers/models enforce strict message schemas.
A request built for provider A may be rejected by provider B if it contains
provider-specific tool-call fields.

### Rule

- Tool calls and tool results are stored in raw history as-is.
- If ToolLoopSystem detects a **model switch**, it builds a request-time view
  that **masks tool messages**.

### Implementation building blocks

- `ConversationViewBuilder` builds `ConversationView(messages, diagnostics)`.
- `ToolMessageMasker` applies masking rules.
- Current masking strategy: `FlatteningToolMessageMasker`.

### Diagnostics (why it was flattened)

When masking happens, the view builder records diagnostics.
They are stored as a transient context attribute:

- `toolloop.view.diagnostics`

This is for debugging/observability and is not persisted.

---

## 5) What ToolLoopSystem does *not* do

- It does **not** permanently rewrite (“flatten”) the persisted/raw message
  history.
- It does **not** rely on legacy inter-system attributes like
  `llm.toolCalls` / `tools.executed`.

---

## 6) Related code

- Domain loop:
  - `src/main/java/me/golemcore/bot/domain/system/toolloop/DefaultToolLoopSystem.java`
- Request-time view:
  - `src/main/java/me/golemcore/bot/domain/system/toolloop/view/*`
- Plan / migration checklist:
  - `TOOL_LOOP_SYSTEM_PLAN.md`
