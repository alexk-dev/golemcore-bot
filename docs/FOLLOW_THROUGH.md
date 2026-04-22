# Follow-Through Resilience

How the bot nudges weak models when they commit to an action but fail to actually trigger it.

> **See also:** [Configuration Guide](CONFIGURATION.md), [Model Routing](MODEL_ROUTING.md), [Auto Mode](AUTO_MODE.md).

## Overview

Small or cheap LLMs frequently produce responses like:

> I'll now gather the three files and summarise them.

…without ever invoking a single tool. The user sees a promise, the agent loop has nothing to do, and the turn ends stranded.

Follow-through is a resilience layer that:

1. Observes the assistant's final reply after the turn has been routed to the user.
2. Asks a cheap classifier LLM whether the reply contained an unfulfilled commitment.
3. If yes, dispatches an internal user-role "nudge" message that continues the turn without asking the real user to re-prompt.

The nudge is chain-capped so a model stuck in a loop cannot generate an infinite nudge storm.

```text
LLM reply -> ResponseRoutingSystem (60) -> user sees it
                      |
                      v
             FollowThroughSystem (61)
                      |
                      +-- hard guard tripped? -> skip
                      +-- guards pass         -> FollowThroughClassifier
                                                   |
                                                   v
                                        LlmPort (classifier tier)
                                                   |
                                                   v
                                        FollowThroughVerdictParser
                                                   |
                      +----------- unfulfilled commitment? ---+
                      |                                       |
                      |                                       v
                      |                      InternalTurnService.scheduleFollowThroughNudge
                      |                                       |
                      |                                       v
                      |                      InboundMessageDispatchPort.dispatch(Message)
                      |                                       |
                      |                                       v
                      |                            AgentLoop picks up next turn
                      v
               no-op (done)
```

## Components

| Component                    | Location                                                                 | Responsibility                                                                         |
|------------------------------|--------------------------------------------------------------------------|----------------------------------------------------------------------------------------|
| `FollowThroughSystem`        | `domain/system/FollowThroughSystem.java`                                 | Pipeline gate, hard guards, orchestrates the classifier call.                          |
| `FollowThroughClassifier`    | `domain/resilience/followthrough/FollowThroughClassifier.java`           | Builds the classifier `LlmRequest`, executes it, fails closed on errors/timeouts.      |
| `FollowThroughPromptBuilder` | `domain/resilience/followthrough/FollowThroughPromptBuilder.java`        | Static system prompt (intent taxonomy, JSON schema) + per-request user prompt.         |
| `FollowThroughVerdictParser` | `domain/resilience/followthrough/FollowThroughVerdictParser.java`        | Parses LLM JSON via `TraceSnapshotCodecPort`; fails closed on malformed responses.     |
| `InternalTurnService.scheduleFollowThroughNudge` | `domain/service/InternalTurnService.java`                  | Emits the synthetic user-role nudge message with correct metadata.                     |

## Pipeline Position

`FollowThroughSystem` runs at `@Order(61)`, immediately after `ResponseRoutingSystem` (`60`). The user sees the assistant reply first; the classifier runs afterward. If the verdict is positive, the nudge is dispatched through `InboundMessageDispatchPort` and the `AgentLoop` picks it up as a normal inbound turn.

The nudge message is tagged with:

- `message.internal = true`
- `message.internal.kind = follow_through_nudge`
- `turn.queue.kind = internal_retry`
- `resilience.follow_through.chain_depth = N+1`

These attributes are derived from [`ContextAttributes`](../src/main/java/me/golemcore/bot/domain/model/ContextAttributes.java).

## Hard Guards

The classifier LLM call is skipped (no API cost) when any of the following is true:

| Guard                                   | Reason                                                                   |
|-----------------------------------------|--------------------------------------------------------------------------|
| `resilience.follow_through.enabled=false` | Feature flag off.                                                      |
| `llm.error` present                     | A real failure already happened — upstream resilience owns recovery.     |
| `plan.mode.active` / `plan.approval.needed` | Plan mode wants the user in the loop, not an auto-nudge.            |
| Auto mode context                       | Auto mode has its own scheduler.                                         |
| Tools executed since last user message  | The agent already acted; the reply is not a stranded commitment.         |
| Empty assistant reply                   | Nothing to classify.                                                     |
| Empty last user message                 | No request to continue.                                                  |
| `chain_depth >= maxChainDepth`          | Anti-loop cap reached (see below).                                       |

## Anti-Loop Chain Cap

Each dispatched nudge increments `resilience.follow_through.chain_depth` by one. When a subsequent turn's inbound message already carries `chain_depth >= maxChainDepth`, the system refuses to classify and no further nudge is generated.

Default `maxChainDepth = 1`, which means: one nudge per stranded commitment, and if the model still fails to act on that nudge, the follow-through layer stands down and lets the user decide what to do next.

## Classifier Verdict

The classifier LLM is asked to emit strict JSON matching:

```json
{
  "intent_type": "commitment | options_offered | question | completion | acknowledgement",
  "has_unfulfilled_commitment": true,
  "commitment_text": "short paraphrase of what the agent committed to",
  "continuation_prompt": "one short user-style imperative that unblocks the agent",
  "reason": "one concise sentence explaining the classification"
}
```

Only `intent_type = commitment` with `has_unfulfilled_commitment = true` and a non-blank `continuation_prompt` triggers a nudge. Every other combination (`options_offered`, `question`, `completion`, `acknowledgement`) returns a non-actionable verdict and the system exits without dispatching.

The classifier is intentionally **fail-closed**: empty responses, malformed JSON, timeouts, and transport errors all collapse to `IntentType.UNKNOWN` with `has_unfulfilled_commitment = false`. A broken classifier can only under-trigger, never over-trigger.

## Configuration

Follow-through config lives under `resilience.followThrough` in `preferences/runtime-config.json`:

```json
{
  "resilience": {
    "enabled": true,
    "followThrough": {
      "enabled": true,
      "modelTier": "routing",
      "timeoutSeconds": 5,
      "maxChainDepth": 1
    }
  }
}
```

| Field            | Type     | Default     | Purpose                                                             |
|------------------|----------|-------------|---------------------------------------------------------------------|
| `enabled`        | boolean  | `true`      | Master switch. Also gated by `resilience.enabled`.                  |
| `modelTier`      | string   | `"routing"` | Explicit model tier used for the classifier call.                   |
| `timeoutSeconds` | integer  | `5`         | Per-call timeout. Classifier fails closed when exceeded.            |
| `maxChainDepth`  | integer  | `1`         | Maximum nudge chain length before the layer stands down.            |

`RuntimeConfigService#isFollowThroughEnabled()` returns `false` whenever `resilience.enabled` is `false`, so disabling the umbrella resilience flag also disables follow-through.

## Observability

- Every scheduled nudge logs `[FollowThrough] nudge scheduled (sessionId=..., nextDepth=...)`.
- Every dispatched internal message logs `[InternalTurn] scheduled follow-through nudge (sessionId=..., chainDepth=...)`.
- Nudge messages show up in session history with `senderId = "internal:follow-through"`.
- Trace span root name for a nudge turn is `resilience.follow_through.nudge`.

## Testing

- Unit tests — `FollowThroughClassifierTest`, `FollowThroughPromptBuilderTest`, `FollowThroughVerdictParserTest`, `FollowThroughSystemTest`, `InternalTurnServiceTest`.
- Integration test — `FollowThroughIntegrationTest` wires the real classifier, prompt builder, verdict parser (with real `JacksonTraceSnapshotCodecAdapter`), and `InternalTurnService`, mocking only `LlmPort` and `InboundMessageDispatchPort`.
