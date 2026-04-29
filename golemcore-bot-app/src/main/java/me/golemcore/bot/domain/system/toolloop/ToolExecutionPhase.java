package me.golemcore.bot.domain.system.toolloop;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.model.hive.HiveRuntimeContracts;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.tracing.MdcSupport;
import me.golemcore.bot.domain.tools.PlanModeToolRestrictionService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.domain.events.RuntimeEventService;
import me.golemcore.bot.domain.tracing.TraceMdcSupport;
import me.golemcore.bot.domain.tracing.TraceService;
import me.golemcore.bot.domain.progress.TurnProgressService;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolRepeatDecision;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolRepeatGuard;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolStateDomain;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseFingerprint;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Executes a batch of tool calls returned by the LLM and evaluates failure
 * policies after each execution.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Iterates through tool calls, checking for user interrupts before
 * each</li>
 * <li>Delegates real tool execution to {@link ToolExecutorPort} with
 * tracing</li>
 * <li>Accumulates file changes and attachments on {@link TurnState}</li>
 * <li>Evaluates {@link ToolFailurePolicy} after each failed tool and applies
 * the verdict (stop turn, inject recovery hint, or continue)</li>
 * <li>Writes synthetic failure results for remaining tool calls when the batch
 * is interrupted mid-way (recovery hint or stop)</li>
 * </ul>
 *
 * <p>
 * This phase is stateless — all mutable state lives in {@link TurnState}.
 *
 * @see DefaultToolLoopSystem
 * @see ToolFailurePolicy
 * @see TurnState
 */
class ToolExecutionPhase {

    private final ToolExecutorPort toolExecutor;
    private final ToolFailurePolicy failurePolicy;
    private final RuntimeEventService runtimeEventService;
    private final TurnProgressService turnProgressService;
    private final TraceService traceService;
    private final RuntimeConfigService runtimeConfigService;
    private final PlanModeToolRestrictionService planModeToolRestrictionService;
    private final ToolRepeatGuard repeatGuard;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * @param toolExecutor
     *            port for executing individual tool calls
     * @param failurePolicy
     *            unified failure policy for stop/recovery decisions
     * @param runtimeEventService
     *            event emission service (may be null)
     * @param turnProgressService
     *            progress tracking service (may be null)
     * @param traceService
     *            distributed tracing service (may be null)
     * @param runtimeConfigService
     *            runtime configuration service (may be null)
     * @param clock
     *            clock for timing tool executions
     */
    ToolExecutionPhase(ToolExecutorPort toolExecutor, ToolFailurePolicy failurePolicy,
            RuntimeEventService runtimeEventService, TurnProgressService turnProgressService,
            TraceService traceService, RuntimeConfigService runtimeConfigService, Clock clock) {
        this(toolExecutor, failurePolicy, runtimeEventService, turnProgressService, traceService,
                runtimeConfigService, clock, null, ToolRepeatGuard.noop());
    }

    ToolExecutionPhase(ToolExecutorPort toolExecutor, ToolFailurePolicy failurePolicy,
            RuntimeEventService runtimeEventService, TurnProgressService turnProgressService,
            TraceService traceService, RuntimeConfigService runtimeConfigService, Clock clock,
            PlanModeToolRestrictionService planModeToolRestrictionService) {
        this(toolExecutor, failurePolicy, runtimeEventService, turnProgressService, traceService,
                runtimeConfigService, clock, planModeToolRestrictionService, ToolRepeatGuard.noop());
    }

    ToolExecutionPhase(ToolExecutorPort toolExecutor, ToolFailurePolicy failurePolicy,
            RuntimeEventService runtimeEventService, TurnProgressService turnProgressService,
            TraceService traceService, RuntimeConfigService runtimeConfigService, Clock clock,
            PlanModeToolRestrictionService planModeToolRestrictionService, ToolRepeatGuard repeatGuard) {
        this.toolExecutor = toolExecutor;
        this.failurePolicy = failurePolicy;
        this.runtimeEventService = runtimeEventService;
        this.turnProgressService = turnProgressService;
        this.traceService = traceService;
        this.runtimeConfigService = runtimeConfigService;
        this.clock = clock;
        this.planModeToolRestrictionService = planModeToolRestrictionService;
        this.repeatGuard = repeatGuard != null ? repeatGuard : ToolRepeatGuard.noop();
    }

    /**
     * Outcome of executing a tool call batch.
     */
    // @formatter:off
    sealed interface ToolBatchOutcome {

        /** All tool calls executed successfully — continue the main loop. */
        record Continue() implements ToolBatchOutcome {
        }

        /** A recovery hint was injected — break out of the batch and re-enter the LLM. */
        record RecoveryHintInjected() implements ToolBatchOutcome {
        }

        /** The turn should stop immediately with the given result. */
        record StopTurn(ToolLoopTurnResult result) implements ToolBatchOutcome {
        }

        /** A user interrupt was detected during tool execution. */
        record Interrupted(ToolLoopTurnResult result) implements ToolBatchOutcome {
        }
    }
    // @formatter:on

    /**
     * Executes a batch of tool calls, applying failure policy after each.
     *
     * <p>
     * When a tool failure triggers a stop or recovery hint mid-batch, synthetic
     * failure results are written for all remaining (unexecuted) tool calls to
     * maintain conversation history consistency.
     *
     * @param turnState     the current turn state
     * @param response      the LLM response containing tool calls
     * @param historyWriter history writer for recording tool results
     * @param llmCallPhase  LLM call phase for stop-turn bookkeeping
     * @return the batch outcome
     */
    ToolBatchOutcome execute(TurnState turnState, LlmResponse response, HistoryWriter historyWriter,
            LlmCallPhase llmCallPhase) {
        AgentContext context = turnState.getContext();
        List<Message.ToolCall> toolCalls = response.getToolCalls();
        boolean planModeActiveAtBatchStart = isPlanModeActive(context);

        maybePublishIntent(context, response);
        historyWriter.appendAssistantToolCalls(context, response, toolCalls);
        List<PendingWarningHint> pendingWarningHints = new ArrayList<>();

        for (int index = 0; index < toolCalls.size(); index++) {
            Message.ToolCall toolCall = toolCalls.get(index);

            // --- Check for user interrupt ---
            if (llmCallPhase.isInterruptRequested(context)) {
                llmCallPhase.clearInterruptFlag(context);
                llmCallPhase.applyAttachments(context, turnState.getAccumulatedAttachments());
                llmCallPhase.emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                        llmCallPhase.eventPayload("reason", HiveRuntimeContracts.USER_INTERRUPT_REASON,
                                "llmCalls", turnState.getLlmCalls(),
                                "toolExecutions", turnState.getToolExecutions()));
                return new ToolBatchOutcome.Interrupted(llmCallPhase.stopTurn(context,
                        context.getAttribute(ContextAttributes.LLM_RESPONSE), toolCalls,
                        "interrupted by user", turnState.getLlmCalls(), turnState.getToolExecutions(),
                        historyWriter));
            }

            // --- Execute tool call ---
            ToolExecutionOutcome outcome = executeToolCall(context, toolCall, turnState, historyWriter,
                    pendingWarningHints);

            if (shouldStopAfterPlanExit(planModeActiveAtBatchStart, toolCall, outcome)) {
                llmCallPhase.applyAttachments(context, turnState.getAccumulatedAttachments());
                llmCallPhase.emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                        llmCallPhase.eventPayload("reason", "plan_exit", "tool", outcome.toolName()));
                return new ToolBatchOutcome.StopTurn(llmCallPhase.finishPlanModeTurn(context,
                        response, toolCalls, turnState.getLlmCalls(), turnState.getToolExecutions(),
                        historyWriter));
            }

            // --- Evaluate failure policy ---
            if (outcome != null && outcome.toolResult() != null && !outcome.toolResult().isSuccess()) {
                ToolFailurePolicy.Verdict verdict = failurePolicy.evaluate(turnState, toolCall, outcome);

                if (verdict instanceof ToolFailurePolicy.Verdict.RecoveryHint hint) {
                    writeSyntheticResultsForRemaining(context, toolCalls, index + 1, "recovery hint injected",
                            historyWriter);
                    llmCallPhase.flushProgress(context, "tool_recovery");
                    historyWriter.appendInternalRecoveryHint(context, hint.hint());
                    llmCallPhase.emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                            llmCallPhase.eventPayload("reason", "tool_recovery", "tool", outcome.toolName(),
                                    "recoverability", hint.recoverabilityName(),
                                    "fingerprint", hint.fingerprint()));
                    return new ToolBatchOutcome.RecoveryHintInjected();
                }

                if (verdict instanceof ToolFailurePolicy.Verdict.StopTurn stop) {
                    llmCallPhase.applyAttachments(context, turnState.getAccumulatedAttachments());
                    llmCallPhase.emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                            llmCallPhase.eventPayload("reason", stopReasonKey(stop.reason()),
                                    "tool", outcome.toolName()));
                    return new ToolBatchOutcome.StopTurn(llmCallPhase.stopTurn(context,
                            context.getAttribute(ContextAttributes.LLM_RESPONSE), toolCalls,
                            stop.reason(), turnState.getLlmCalls(), turnState.getToolExecutions(),
                            historyWriter));
                }
            }
        }

        appendPendingWarningHints(turnState, context, historyWriter, pendingWarningHints);
        return new ToolBatchOutcome.Continue();
    }

        private boolean shouldStopAfterPlanExit(boolean planModeActiveAtBatchStart, Message.ToolCall toolCall,
                ToolExecutionOutcome outcome) {
            if (!planModeActiveAtBatchStart || toolCall == null || outcome == null || outcome.toolResult() == null
                    || !outcome.toolResult().isSuccess()) {
                return false;
            }
            return ToolNames.PLAN_EXIT.equals(normalizeToolName(toolCall.getName()));
        }

        private boolean isPlanModeActive(AgentContext context) {
            return planModeToolRestrictionService != null && planModeToolRestrictionService.isPlanModeActive(context);
        }

        // ==================== Tool execution ====================

        private ToolExecutionOutcome executeToolCall(AgentContext context, Message.ToolCall toolCall,
                TurnState turnState,
                HistoryWriter historyWriter,
                List<PendingWarningHint> pendingWarningHints) {
            emitRuntimeEvent(context, RuntimeEventType.TOOL_STARTED,
                    eventPayload("toolCallId", toolCall.getId(), "tool", toolCall.getName()));

            Instant toolStarted = clock.instant();
            RepeatGuardExecution execution = planModeToolRestrictionService != null
                    ? planModeToolRestrictionService.denialReason(context, toolCall)
                            .map(reason -> new RepeatGuardExecution(ToolExecutionOutcome.synthetic(toolCall,
                                    ToolFailureKind.POLICY_DENIED, reason), null, null))
                            .orElseGet(() -> executeAfterRepeatGuard(context, toolCall, turnState))
                    : executeAfterRepeatGuard(context, toolCall, turnState);
            ToolExecutionOutcome outcome = execution.outcome();
            turnState.incrementToolExecutions();
            long toolDuration = Duration.between(toolStarted, clock.instant()).toMillis();

            Map<String, Object> finishedPayload = eventPayload("toolCallId", toolCall.getId(), "tool",
                    toolCall.getName(),
                    "success", outcome != null && outcome.toolResult() != null && outcome.toolResult().isSuccess(),
                    "durationMs", toolDuration);
            addRepeatGuardPayload(finishedPayload, execution.decision());
            emitRuntimeEvent(context, RuntimeEventType.TOOL_FINISHED, finishedPayload);

            if (outcome != null && outcome.toolResult() != null && outcome.toolResult().isSuccess()) {
                captureFileChanges(toolCall, turnState.getTurnFileChanges());
            }
            if (!turnState.getTurnFileChanges().isEmpty()) {
                context.setAttribute(ContextAttributes.TURN_FILE_CHANGES,
                        new ArrayList<>(turnState.getTurnFileChanges()));
            }

            if (outcome != null && outcome.attachment() != null) {
                turnState.getAccumulatedAttachments().add(outcome.attachment());
            }
            if (outcome != null && outcome.toolResult() != null) {
                context.addToolResult(outcome.toolCallId(), outcome.toolResult());
            }
            if (outcome != null) {
                recordToolProgress(context, toolCall, outcome, toolDuration);
                historyWriter.appendToolResult(context, outcome);
                if (execution.warningHint() != null) {
                    pendingWarningHints.add(execution.warningHint());
                }
                repeatGuard.afterOutcome(turnState, toolCall, outcome);
            }

            return outcome;
        }

        private void appendPendingWarningHints(TurnState turnState, AgentContext context, HistoryWriter historyWriter,
                List<PendingWarningHint> pendingWarningHints) {
            if (pendingWarningHints == null || pendingWarningHints.isEmpty()) {
                return;
            }
            List<String> uniqueHints = new ArrayList<>();
            for (PendingWarningHint pendingWarning : pendingWarningHints) {
                if (pendingWarning != null
                        && pendingWarning.isStillCurrent(turnState)
                        && pendingWarning.hint() != null
                        && !pendingWarning.hint().isBlank()
                        && !uniqueHints.contains(pendingWarning.hint())) {
                    uniqueHints.add(pendingWarning.hint());
                }
            }
            if (!uniqueHints.isEmpty()) {
                historyWriter.appendInternalRecoveryHint(context, String.join("\n\n", uniqueHints));
            }
        }

        private RepeatGuardExecution executeAfterRepeatGuard(AgentContext context, Message.ToolCall toolCall,
                TurnState turnState) {
            ToolRepeatDecision decision = repeatGuard.beforeExecute(turnState, toolCall);
            if (decision instanceof ToolRepeatDecision.BlockAndHint block) {
                return new RepeatGuardExecution(repeatGuardSyntheticOutcome(toolCall,
                        ToolFailureKind.REPEATED_TOOL_USE_BLOCKED, block.hint(), block), null, decision);
            }
            if (decision instanceof ToolRepeatDecision.StopTurn stop) {
                return new RepeatGuardExecution(repeatGuardSyntheticOutcome(toolCall,
                        ToolFailureKind.REPEAT_GUARD_STOP_TURN, stop.reason(), stop), null, decision);
            }
            PendingWarningHint warningHint = decision instanceof ToolRepeatDecision.WarnAndAllow warn
                    ? new PendingWarningHint(
                            warn.hint(),
                            warn.fingerprint(),
                            turnState.getToolUseLedger().environmentSnapshotFor(warn.fingerprint()))
                    : null;
            return new RepeatGuardExecution(executeWithTracing(context, toolCall, turnState.getTracingConfig()),
                    warningHint, decision);
        }

        private ToolExecutionOutcome repeatGuardSyntheticOutcome(
                Message.ToolCall toolCall,
                ToolFailureKind failureKind,
                String message,
                ToolRepeatDecision decision) {
            me.golemcore.bot.domain.model.ToolResult toolResult = me.golemcore.bot.domain.model.ToolResult.builder()
                    .success(false)
                    .failureKind(failureKind)
                    .error(message)
                    .data(repeatGuardPayload(decision))
                    .build();
            return new ToolExecutionOutcome(
                    toolCall.getId(), toolCall.getName(), toolResult, message, true, null);
        }

        private void addRepeatGuardPayload(Map<String, Object> payload, ToolRepeatDecision decision) {
            if (payload == null || decision == null) {
                return;
            }
            Map<String, Object> guardPayload = repeatGuardPayload(decision);
            payload.put("repeatGuardDecision", decisionName(decision));
            guardPayload.forEach(payload::putIfAbsent);
        }

        private Map<String, Object> repeatGuardPayload(ToolRepeatDecision decision) {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (decision == null) {
                return payload;
            }
            ToolUseFingerprint fingerprint = decisionFingerprint(decision);
            payload.put("repeatGuardDecision", decisionName(decision));
            if (fingerprint != null) {
                payload.put("repeatFingerprint", fingerprint.stableKey());
                payload.put("repeatCategory", fingerprint.category().name());
                payload.put("repeatTool", fingerprint.toolName());
            }
            return payload;
        }

        private String decisionName(ToolRepeatDecision decision) {
            if (decision instanceof ToolRepeatDecision.WarnAndAllow warn && warn.wouldBlock()) {
                return "WOULD_BLOCK_SHADOW";
            }
            if (decision instanceof ToolRepeatDecision.Allow) {
                return "ALLOW";
            }
            if (decision instanceof ToolRepeatDecision.WarnAndAllow) {
                return "WARN_AND_ALLOW";
            }
            if (decision instanceof ToolRepeatDecision.BlockAndHint) {
                return "BLOCK_AND_HINT";
            }
            if (decision instanceof ToolRepeatDecision.StopTurn) {
                return "STOP_TURN";
            }
            return "UNKNOWN";
        }

        private ToolUseFingerprint decisionFingerprint(ToolRepeatDecision decision) {
            return switch (decision) {
            case ToolRepeatDecision.Allow allow -> allow.fingerprint();
            case ToolRepeatDecision.WarnAndAllow warn -> warn.fingerprint();
            case ToolRepeatDecision.BlockAndHint block -> block.fingerprint();
            case ToolRepeatDecision.StopTurn stop -> stop.fingerprint();
            };
        }

        private record RepeatGuardExecution(
                ToolExecutionOutcome outcome,
                PendingWarningHint warningHint,
                ToolRepeatDecision decision) {
        }

    private record PendingWarningHint(
            String hint,
            ToolUseFingerprint fingerprint,
            Map<ToolStateDomain, Integer> environmentSnapshot) {

        boolean isStillCurrent(TurnState turnState) {
            return turnState != null
                    && turnState.getToolUseLedger().isEnvironmentSnapshotCurrent(fingerprint, environmentSnapshot);
        }
    }

    private ToolExecutionOutcome executeWithTracing(AgentContext context, Message.ToolCall toolCall,
            RuntimeConfig.TracingConfig tracingConfig) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (toolCall.getName() != null) {
            attributes.put("tool.name", toolCall.getName());
        }
        if (toolCall.getId() != null) {
            attributes.put("tool.callId", toolCall.getId());
        }
        putIfPresent(attributes, ContextAttributes.SELF_EVOLVING_RUN_ID,
                readContextAttribute(context, ContextAttributes.SELF_EVOLVING_RUN_ID));
        putIfPresent(attributes, ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID,
                readContextAttribute(context, ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID));
        TraceContext toolSpan = startChildSpan(context, "tool." + toolCall.getName(), TraceSpanKind.TOOL,
                attributes);
        captureToolSnapshot(context, toolSpan, tracingConfig, "input", toolCall);
        try (MdcSupport.Scope ignored = MdcSupport.withContext(buildTraceMdcContext(toolSpan, context))) {
            ToolExecutionOutcome outcome = toolExecutor.execute(context, toolCall);
            captureToolSnapshot(context, toolSpan, tracingConfig, "output", outcome);
            TraceStatusCode statusCode = outcome != null && outcome.toolResult() != null
                    && outcome.toolResult().isSuccess()
                            ? TraceStatusCode.OK
                            : TraceStatusCode.ERROR;
            finishChildSpan(context, toolSpan, statusCode,
                    outcome != null && outcome.toolResult() != null ? outcome.toolResult().getError() : null);
            return outcome;
        } catch (Exception e) { // NOSONAR - tool execution must not break the loop
            ToolExecutionOutcome synthetic = ToolExecutionOutcome.synthetic(toolCall,
                    ToolFailureKind.EXECUTION_FAILED,
                    "Tool execution failed: " + e.getMessage());
            captureToolSnapshot(context, toolSpan, tracingConfig, "output", synthetic);
            finishChildSpan(context, toolSpan, TraceStatusCode.ERROR, e.getMessage());
            return synthetic;
        }
    }

    // ==================== Synthetic results for remaining tool calls
    // ====================

    /**
     * Writes synthetic failure results for tool calls that were not executed
     * because the batch was interrupted mid-way.
     *
     * <p>
     * This ensures conversation history remains consistent: every tool call in the
     * assistant message gets a corresponding tool result message, even if some
     * tools were skipped due to a recovery hint or stop decision.
     *
     * @param context
     *            the agent context
     * @param toolCalls
     *            full list of tool calls in the batch
     * @param startIndex
     *            index of the first unexecuted tool call
     * @param reason
     *            reason for skipping (used in the synthetic result message)
     * @param historyWriter
     *            history writer for recording synthetic results
     */
    private void writeSyntheticResultsForRemaining(AgentContext context, List<Message.ToolCall> toolCalls,
            int startIndex, String reason, HistoryWriter historyWriter) {
        for (int index = startIndex; index < toolCalls.size(); index++) {
            Message.ToolCall remainingCall = toolCalls.get(index);
            if (context.getToolResults() != null && context.getToolResults().containsKey(remainingCall.getId())) {
                continue;
            }
            ToolExecutionOutcome synthetic = ToolExecutionOutcome.synthetic(remainingCall,
                    ToolFailureKind.EXECUTION_FAILED, "Tool skipped: " + reason);
            context.addToolResult(synthetic.toolCallId(), synthetic.toolResult());
            historyWriter.appendToolResult(context, synthetic);
        }
    }

    // ==================== File change tracking ====================

    private void captureFileChanges(Message.ToolCall toolCall, List<Map<String, Object>> turnFileChanges) {
        if (toolCall == null || turnFileChanges == null) {
            return;
        }
        if (!"filesystem".equals(toolCall.getName())) {
            return;
        }
        if (toolCall.getArguments() == null || toolCall.getArguments().isEmpty()) {
            return;
        }

        Object operationObject = toolCall.getArguments().get("operation");
        Object pathObject = toolCall.getArguments().get("path");
        if (!(operationObject instanceof String operation) || !(pathObject instanceof String path)
                || path.isBlank()) {
            return;
        }

        boolean edited = "write_file".equals(operation)
                || "append".equals(operation)
                || "delete".equals(operation)
                || "create_directory".equals(operation);
        if (!edited) {
            return;
        }

        int addedLines = 0;
        int removedLines = 0;
        boolean deleted = false;
        if ("write_file".equals(operation) || "append".equals(operation)) {
            Object contentObject = toolCall.getArguments().get("content");
            if (contentObject instanceof String content && !content.isBlank()) {
                addedLines = content.split("\\R", -1).length;
            }
        }
        if ("delete".equals(operation)) {
            deleted = true;
            removedLines = 1;
        }

        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("path", path);
        stat.put("addedLines", addedLines);
        stat.put("removedLines", removedLines);
        stat.put("deleted", deleted);

        for (Map<String, Object> existing : turnFileChanges) {
            Object existingPath = existing.get("path");
            if (existingPath instanceof String && path.equals(existingPath)) {
                int existingAdded = readInt(existing.get("addedLines"));
                int existingRemoved = readInt(existing.get("removedLines"));
                existing.put("addedLines", existingAdded + addedLines);
                existing.put("removedLines", existingRemoved + removedLines);
                existing.put("deleted", Boolean.TRUE.equals(existing.get("deleted")) || deleted);
                return;
            }
        }

        turnFileChanges.add(stat);
    }

    private int readInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    // ==================== Utility ====================

    private String stopReasonKey(String reason) {
        if (reason == null) {
            return "tool_stop";
        }
        if (reason.contains("repeat guard") || reason.contains("repeated blocked tool calls")) {
            return "repeat_guard_stop";
        }
        if (reason.contains("confirmation denied")) {
            return "confirmation_denied";
        }
        if (reason.contains("tool denied by policy")) {
            return "tool_policy_denied";
        }
        if (reason.contains("repeated tool failure")) {
            return "repeated_tool_failure";
        }
        if (reason.contains("tool failure")) {
            return "tool_failure";
        }
        return "tool_stop";
    }

    private String normalizeToolName(String toolName) {
        return toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
    }

    private void maybePublishIntent(AgentContext context, LlmResponse response) {
        if (turnProgressService == null || context == null || response == null) {
            return;
        }
        turnProgressService.maybePublishIntent(context, response);
    }

    private void recordToolProgress(AgentContext context, Message.ToolCall toolCall, ToolExecutionOutcome outcome,
            long durationMs) {
        if (turnProgressService == null || context == null || toolCall == null || outcome == null) {
            return;
        }
        turnProgressService.recordToolExecution(context, toolCall, outcome, durationMs);
    }

    // ==================== Events ====================

    private void emitRuntimeEvent(AgentContext context, RuntimeEventType type, Map<String, Object> payload) {
        if (runtimeEventService == null || context == null) {
            return;
        }
        runtimeEventService.emit(context, type, payload);
    }

    private Map<String, Object> eventPayload(Object... entries) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (entries == null || entries.length == 0) {
            return payload;
        }
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("Runtime event payload entries must be key/value pairs");
        }
        for (int index = 0; index < entries.length; index += 2) {
            Object keyObject = entries[index];
            if (!(keyObject instanceof String key) || key.isBlank()) {
                throw new IllegalArgumentException("Runtime event payload keys must be non-blank strings");
            }
            payload.put(key, entries[index + 1]);
        }
        return payload;
    }

    // ==================== Tracing ====================

    private TraceContext startChildSpan(AgentContext context, String spanName, TraceSpanKind spanKind,
            Map<String, Object> attributes) {
        if (traceService == null || context == null || context.getSession() == null
                || context.getTraceContext() == null
                || runtimeConfigService == null || !runtimeConfigService.isTracingEnabled()) {
            return null;
        }
        return traceService.startSpan(context.getSession(), context.getTraceContext(), spanName, spanKind,
                clock.instant(), attributes);
    }

    private void finishChildSpan(AgentContext context, TraceContext spanContext, TraceStatusCode statusCode,
            String statusMessage) {
        if (traceService == null || context == null || context.getSession() == null || spanContext == null) {
            return;
        }
        traceService.finishSpan(context.getSession(), spanContext, statusCode, statusMessage, clock.instant());
    }

    private void captureToolSnapshot(AgentContext context, TraceContext spanContext,
            RuntimeConfig.TracingConfig tracingConfig, String role, Object payload) {
        if (traceService == null || context == null || context.getSession() == null || spanContext == null
                || tracingConfig == null || !Boolean.TRUE.equals(tracingConfig.getCaptureToolPayloads())) {
            return;
        }
        traceService.captureSnapshot(context.getSession(), spanContext, tracingConfig,
                role, "application/json", serializeSnapshotPayload(payload));
    }

    private Map<String, String> buildTraceMdcContext(TraceContext spanContext, AgentContext context) {
        if (spanContext == null) {
            return Map.of();
        }
        return TraceMdcSupport.buildMdcContext(spanContext, context != null ? context.getAttributes() : Map.of());
    }

    private byte[] serializeSnapshotPayload(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) { // NOSONAR - tracing must not break tool loop
            return String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
        }
    }

    private String readContextAttribute(AgentContext context, String key) {
        if (context == null || context.getAttributes() == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = context.getAttributes().get(key);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : null;
    }

    private void putIfPresent(Map<String, Object> attributes, String key, String value) {
        if (attributes == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        attributes.put(key, value);
    }
}
