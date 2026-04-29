# Repeat Tool Use Guard Plan

## Goal

Make long-running autonomous work resilient to repeated identical tool calls while preserving legitimate retry,
polling and re-check behavior after state changes.

## TDD Checklist

- [x] Create a `feat/` branch from current `origin/main`.
- [x] Capture baseline focused tool-loop behavior before production changes.
- [x] Add RED fingerprint tests for canonical JSON arguments, path normalization, shell working directory, secret redaction, volatile fields and category classification.
- [x] Implement `ToolUseFingerprintService`, `ToolUseFingerprint` and `ToolUseCategory`.
- [x] Add RED ledger tests for records, environment version and blocked-repeat counters.
- [x] Implement in-turn `ToolUseLedger` and `ToolUseRecord`.
- [x] Add RED guard policy tests for observe, poll, mutation, unknown execution, control tools and stop thresholds.
- [x] Implement `ToolRepeatGuard`, `ToolRepeatDecision` and `ToolRepeatGuardSettings`.
- [x] Add RED `TurnState` tests proving a ledger exists per turn.
- [x] Wire `ToolUseLedger` into `TurnState`.
- [x] Add RED execution-phase tests proving repeated calls are blocked before `ToolExecutorPort.execute(...)`.
- [x] Wire repeat guard into `ToolExecutionPhase` with synthetic `REPEATED_TOOL_USE_BLOCKED` tool results.
- [x] Add RED failure-policy tests proving repeat blocks route to recovery hints before generic stop-on-failure.
- [x] Add `ToolFailureKind.REPEATED_TOOL_USE_BLOCKED` and recovery-hint handling.
- [x] Add RED full tool-loop tests proving repeated successful reads are bounded and the model receives a recovery hint.
- [x] Add runtime config defaults and clamping tests for repeat guard settings.
- [x] Wire repeat guard through `ToolLoopAutoConfiguration`.
- [x] Add RED auto work-key tests for task, goal, manual turn and schedule-id changes.
- [x] Implement `AutonomyWorkKey`.
- [x] Add RED durable ledger store tests for save/load, atomic writes, TTL pruning and safe persistence.
- [x] Implement the `ToolUseLedgerStore` domain port and JSON infrastructure adapter.
- [x] Add RED auto-mode integration test proving a prior task ledger blocks repeated observations across scheduled runs.
- [x] Load and flush durable ledgers around the tool loop for autonomous goal/task work.
- [x] Add RED review-hardening tests for unknown TTL, read-only shell, poll backoff and synthetic denial poisoning.
- [x] Make `EXECUTE_UNKNOWN` TTL-bound and prevent unverified unknown execution from resetting observation or poll windows.
- [x] Count only successful actual executions for repeat blockers while keeping synthetic failures available as diagnostics.
- [x] Cap durable ledger records per autonomous work item.
- [x] Keep repeat warning hints batch-safe so user/internal hints are never interleaved between tool results.
- [x] Allow exact shell repeats after verified local filesystem mutations.
- [x] Normalize shell `cwd`/`workdir`/`workingDirectory` values in fingerprints.
- [x] Treat missing shell workdir as workspace root in fingerprints.
- [x] Classify documented read-only memory and filesystem operations as observations.
- [x] Add domain-scoped state invalidation so memory, diary, Hive and scheduling mutations do not globally reset workspace repeats.
- [x] Add explicit first-party semantics for `goal_management`, `schedule_session_action` and Hive tools.
- [x] Treat changed output digests as progress only for explicit deterministic observation semantics.
- [x] Preserve conflicting shell workdir aliases in the fingerprint instead of collapsing them.
- [x] Emit repeat-guard decision telemetry on `TOOL_FINISHED` without raw arguments.
- [x] Carry stable repeat fingerprints in synthetic blocked results and recovery telemetry.
- [x] Log durable ledger load diagnostics while still failing open to an empty in-turn ledger.
- [x] Bound readable durable-ledger path segment lengths while retaining hash identity.
- [x] Keep pre-execution fingerprinting fail-open for invalid model-generated paths and shell workdirs.
- [x] Classify official plugin observation tools (`browse`, `firecrawl_scrape`, `perplexity_ask`, `weather`, read-only mail) explicitly.
- [x] Suppress stale batch-level warning hints after same-domain mutations.
- [x] Allow a different recovery/checkpoint tool after the blocked-repeat threshold; stop only on another would-block repeat.
- [x] Expire repeat-guard stop-turn synthetic records by durable ledger TTL and write schema version `3`.
- [x] Add explicit first-party semantics for plan, skill, session-control and voice tools.
- [x] Prefer semantic output digests from structured tool result data when available.
- [x] Include a redacted argument hash in fail-open fingerprint fallback keys.
- [x] Treat disabled repeat guard as a hard kill switch with no ledger learning.
- [x] Emit a dedicated `repeat_guard_stop` runtime reason for repeat-guard stop turns.
- [x] Use hash-suffixed durable ledger paths to avoid sanitized work-key collisions.
- [x] Document repeat guard defaults, storage layout and troubleshooting.
- [x] Run full local verification.
- [x] Push the branch and open a `feat:` PR.
- [ ] Monitor GitHub checks and fix failures until green.

## Definition Of Done

- [x] Identical observe calls are allowed once, warned once, and blocked on the third same-state call.
- [x] Blocked repeats produce protocol-correct synthetic tool results.
- [x] The model receives an explicit recovery hint.
- [x] Plan-mode and tool policy denial still run before repeat guard.
- [x] Exact repeated shell commands are bounded while changed recovery commands remain allowed.
- [x] Unrelated mutations do not reset repeat windows for workspace reads or local shell commands.
- [x] Auto-mode task/goal ledgers survive across scheduled runs.
- [x] Ledger persistence stores only safe fingerprints and output digests, not raw secrets or large outputs.
- [x] Full Maven verification passes locally.
- [ ] Full Maven verification passes in GitHub Actions.
