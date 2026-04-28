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
- [x] Document repeat guard defaults, storage layout and troubleshooting.
- [x] Run full local verification.
- [ ] Push the branch and open a `feat:` PR.
- [ ] Monitor GitHub checks and fix failures until green.

## Definition Of Done

- [x] Identical observe calls are allowed once, warned once, and blocked on the third same-state call.
- [x] Blocked repeats produce protocol-correct synthetic tool results.
- [x] The model receives an explicit recovery hint.
- [x] Plan-mode and tool policy denial still run before repeat guard.
- [x] Exact repeated shell commands are bounded while changed recovery commands remain allowed.
- [x] Auto-mode task/goal ledgers survive across scheduled runs.
- [x] Ledger persistence stores only safe fingerprints and output digests, not raw secrets or large outputs.
- [x] Full Maven verification passes locally.
- [ ] Full Maven verification passes in GitHub Actions.
