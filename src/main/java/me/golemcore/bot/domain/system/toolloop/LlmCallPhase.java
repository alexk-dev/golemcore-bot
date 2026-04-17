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
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.FailureKind;
import me.golemcore.bot.domain.model.FailureSource;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.TurnLimitReason;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.service.MdcSupport;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.RuntimeEventService;
import me.golemcore.bot.domain.service.TraceMdcSupport;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.TurnProgressService;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import me.golemcore.bot.domain.system.toolloop.resilience.LlmResilienceOrchestrator;
import me.golemcore.bot.domain.system.toolloop.view.ConversationView;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.port.outbound.LlmPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/**
 * Handles a single LLM invocation within the tool loop, including error
 * classification, transient retry with exponential backoff, and context
 * overflow recovery via compaction.
 *
 * <p>
 * This phase is stateless - all mutable state lives in {@link TurnState}.
 *
 * @see DefaultToolLoopSystem
 * @see TurnState
 */
class LlmCallPhase {

    private static final Logger log = LoggerFactory.getLogger(LlmCallPhase.class);
    private static final int EMPTY_FINAL_RESPONSE_MAX_RETRIES = 2;

    private final LlmPort llmPort;
    private final ConversationViewBuilder viewBuilder;
    private final ModelSelectionService modelSelectionService;
    private final RuntimeConfigService runtimeConfigService;
    private final LlmRequestPreflightPhase preflightPhase;
    private final ContextCompactionCoordinator compactionCoordinator;
    private final RuntimeEventService runtimeEventService;
    private final TurnProgressService turnProgressService;
    private final TraceService traceService;
    private final LlmResilienceOrchestrator resilienceOrchestrator;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    LlmCallPhase(LlmPort llmPort, ConversationViewBuilder viewBuilder, ModelSelectionService modelSelectionService,
            RuntimeConfigService runtimeConfigService,
            LlmRequestPreflightPhase preflightPhase,
            ContextCompactionCoordinator compactionCoordinator,
            RuntimeEventService runtimeEventService, TurnProgressService turnProgressService,
            TraceService traceService, LlmResilienceOrchestrator resilienceOrchestrator, Clock clock) {
        this.llmPort = llmPort;
        this.viewBuilder = viewBuilder;
        this.modelSelectionService = modelSelectionService;
        this.runtimeConfigService = runtimeConfigService;
        this.preflightPhase = Objects.requireNonNull(preflightPhase, "preflightPhase");
        this.compactionCoordinator = Objects.requireNonNull(compactionCoordinator, "compactionCoordinator");
        this.runtimeEventService = runtimeEventService;
        this.turnProgressService = turnProgressService;
        this.traceService = traceService;
        this.resilienceOrchestrator = resilienceOrchestrator;
        this.clock = clock;
    }

    /**
     * Outcome of an LLM call attempt.
     */
    sealed

    interface LlmCallOutcome {

        /** LLM returned a valid response (may or may not have tool calls). */
        record Success(LlmResponse response) implements LlmCallOutcome {
        }

        /** A transient error was recovered via retry - caller should continue the loop. */
        record RetryScheduled() implements LlmCallOutcome {
        }

        /** The call failed permanently - return this result to the caller. */
        record Failed(ToolLoopTurnResult result) implements LlmCallOutcome {
        }

        /** User interrupt was detected during the LLM call. */
        record Interrupted(ToolLoopTurnResult result) implements LlmCallOutcome {
        }
    }

    /**
     * Executes a single LLM call with full error handling, retry, and tracing.
     *
     * @param turnState the current turn state
     * @param historyWriter history writer for stop-turn bookkeeping
     * @return the outcome of the call attempt
     */
    LlmCallOutcome execute(TurnState turnState, HistoryWriter historyWriter) {
        AgentContext context = turnState.getContext();

        if (isInterruptRequested(context)) {
            clearInterruptFlag(context);
            applyAttachments(context, turnState.getAccumulatedAttachments());
            emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                    eventPayload("reason", "user_interrupt", "llmCalls", turnState.getLlmCalls(),
                            "toolExecutions", turnState.getToolExecutions()));
            return new LlmCallOutcome.Interrupted(stopTurn(context,
                    context.getAttribute(ContextAttributes.LLM_RESPONSE), null,
                    "interrupted by user", turnState.getLlmCalls(), turnState.getToolExecutions(),
                    historyWriter));
        }

        int attempt = turnState.incrementLlmCalls();
        emitRuntimeEvent(context, RuntimeEventType.LLM_STARTED, eventPayload("attempt", attempt));

        LlmResponse response;
        try {
            response = executeLlmCall(context, attempt, turnState.getTracingConfig());
        } catch (PreCallResilienceException e) {
            return handlePreCallResilience(turnState, e);
        } catch (InterruptedException e) {
            return handleInterrupt(turnState, e, historyWriter);
        } catch (ExecutionException e) {
            return handleExecutionException(turnState, toRuntimeException(e), historyWriter);
        } catch (RuntimeException e) {
            return handleRuntimeException(turnState, e, historyWriter);
        }

        if (turnState.getRetryAttempt() > 0) {
            emitRuntimeEvent(context, RuntimeEventType.RETRY_FINISHED,
                    eventPayload("attempt", turnState.getRetryAttempt(), "success", true));
            logRetrySucceeded(context, attempt, turnState.getRetryAttempt(), turnState.getMaxRetries(),
                    turnState.getLastRetryCode());
            turnState.resetRetryState();
        }

        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        if (resilienceOrchestrator != null) {
            resilienceOrchestrator.recordSuccess(context);
        }
        maybePublishAttachmentFallback(context, response);
        boolean compatFlatteningUsed = response != null && response.isCompatibilityFlatteningApplied();
        context.setAttribute(ContextAttributes.LLM_COMPAT_FLATTEN_FALLBACK_USED, compatFlatteningUsed);
        if (compatFlatteningUsed) {
            log.info("[ToolLoop] Compatibility fallback applied: flattened tool history for LLM request");
        }
        emitRuntimeEvent(context, RuntimeEventType.LLM_FINISHED,
                eventPayload("attempt", attempt, "success", true,
                        "hasToolCalls", response != null && response.hasToolCalls()));
        turnState.setLastLlmResponse(response);
        return new LlmCallOutcome.Success(response);
    }

        /**
         * Outcome of checking whether the LLM response is an empty final response.
         */
        sealed

        interface EmptyResponseCheck {

        /** The response is not empty - proceed to finalize. */
        record NotEmpty() implements EmptyResponseCheck {
        }

        /** The response is empty but within retry budget - loop should continue. */
        record RetryScheduled() implements EmptyResponseCheck {
        }

        /** Retry budget exhausted - return this failure result. */
        record Failed(ToolLoopTurnResult result) implements EmptyResponseCheck {
        }
    }

    /**
     * Checks if the LLM response is an empty final response and handles retry.
     *
     * @param turnState     the current turn state
     * @param response      the LLM response to check
     * @param historyWriter history writer (unused, reserved for consistency)
     * @return the empty response check outcome
     */
    EmptyResponseCheck checkEmptyFinalResponse(TurnState turnState, LlmResponse response,
            HistoryWriter historyWriter) {
        AgentContext context = turnState.getContext();
        String emptyReasonCode = getEmptyFinalResponseCode(response, context);
        if (emptyReasonCode == null) {
            return new EmptyResponseCheck.NotEmpty();
        }
        if (turnState.getEmptyFinalResponseRetries() < EMPTY_FINAL_RESPONSE_MAX_RETRIES) {
            turnState.incrementEmptyFinalResponseRetries();
            logEmptyFinalResponseRetry(context, response, emptyReasonCode, turnState.getEmptyFinalResponseRetries());
            return new EmptyResponseCheck.RetryScheduled();
        }
        emitRuntimeEvent(context, RuntimeEventType.TURN_FAILED,
                eventPayload("reason", "empty_final_response", "code", emptyReasonCode));
        return new EmptyResponseCheck.Failed(failEmptyFinalResponse(context, response, emptyReasonCode,
                turnState.getLlmCalls(), turnState.getToolExecutions()));
    }

    /**
     * Finalizes the turn with a successful final answer.
     */
    ToolLoopTurnResult finalizeFinalAnswer(TurnState turnState, LlmResponse response, HistoryWriter historyWriter) {
        AgentContext context = turnState.getContext();
        if (response != null) {
            historyWriter.appendFinalAssistantAnswer(context, response, response.getContent());
        }
        context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, true);
        if (resilienceOrchestrator != null) {
            resilienceOrchestrator.recordTurnSuccess(context);
        }
        flushProgress(context, "final_answer");
        applyAttachments(context, turnState.getAccumulatedAttachments());
        emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                eventPayload("llmCalls", turnState.getLlmCalls(), "toolExecutions", turnState.getToolExecutions()));
        clearProgress(context);
        return new ToolLoopTurnResult(context, true, turnState.getLlmCalls(), turnState.getToolExecutions());
    }

    /**
     * Handles the loop limit being reached.
     */
    ToolLoopTurnResult handleLimitReached(TurnState turnState, HistoryWriter historyWriter) {
        AgentContext context = turnState.getContext();
        TurnLimitReason stopReason = buildStopReason(turnState);
        LlmResponse lastResponse = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        List<Message.ToolCall> pendingToolCalls = lastResponse != null ? lastResponse.getToolCalls() : null;
        applyAttachments(context, turnState.getAccumulatedAttachments());
        context.setAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REACHED, true);
        context.setAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REASON, stopReason);
        emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                eventPayload("reason", "limit", "limit", stopReason.name()));
        return stopTurn(context, lastResponse, pendingToolCalls,
                buildStopReasonMessage(stopReason, turnState.getMaxLlmCalls(), turnState.getMaxToolExecutions()),
                turnState.getLlmCalls(), turnState.getToolExecutions(), historyWriter);
    }

    /**
     * Stops the turn with a reason, writing synthetic results for pending tool
     * calls.
     */
    ToolLoopTurnResult stopTurn(AgentContext context, LlmResponse lastResponse,
            List<Message.ToolCall> pendingToolCalls, String reason, int llmCalls, int toolExecutions,
            HistoryWriter historyWriter) {
        flushProgress(context, "turn_stop");
        if (pendingToolCalls != null) {
            for (Message.ToolCall toolCall : pendingToolCalls) {
                if (context.getToolResults() != null && context.getToolResults().containsKey(toolCall.getId())) {
                    continue;
                }
                ToolExecutionOutcome synthetic = ToolExecutionOutcome.synthetic(toolCall,
                        me.golemcore.bot.domain.model.ToolFailureKind.EXECUTION_FAILED,
                        "Tool loop stopped: " + reason);
                context.addToolResult(synthetic.toolCallId(), synthetic.toolResult());
                historyWriter.appendToolResult(context, synthetic);
            }
        }

        String stopMessage = "Tool loop stopped: " + reason + ".";
        historyWriter.appendFinalAssistantAnswer(context, lastResponse, stopMessage);

        LlmResponse cleanResponse = LlmResponse.builder()
                .content(stopMessage)
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, cleanResponse);
        context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, true);
        clearProgress(context);
        return new ToolLoopTurnResult(context, true, llmCalls, toolExecutions);
    }

            // ==================== Error handling ====================

            private LlmCallOutcome handleInterrupt(TurnState turnState, InterruptedException e,
                    HistoryWriter historyWriter) {
                Thread.currentThread().interrupt();
                AgentContext context = turnState.getContext();
                try {
                    if (isInterruptRequested(context)) {
                        clearInterruptFlag(context);
                        applyAttachments(context, turnState.getAccumulatedAttachments());
                        emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                                eventPayload("reason", "user_interrupt", "llmCalls", turnState.getLlmCalls(),
                                        "toolExecutions", turnState.getToolExecutions()));
                        return new LlmCallOutcome.Interrupted(stopTurn(context,
                                context.getAttribute(ContextAttributes.LLM_RESPONSE), null,
                                "interrupted by user", turnState.getLlmCalls(), turnState.getToolExecutions(),
                                historyWriter));
                    }
                    emitRuntimeEvent(context, RuntimeEventType.LLM_FINISHED,
                            eventPayload("attempt", turnState.getLlmCalls(), "success", false, "code",
                                    "llm.interrupted"));
                    emitRuntimeEvent(context, RuntimeEventType.TURN_FAILED,
                            eventPayload("reason", "llm_error", "code", "llm.interrupted"));
                    return new LlmCallOutcome.Failed(
                            failLlmInvocation(context, new RuntimeException("LLM chat interrupted", e),
                                    turnState.getLlmCalls(), turnState.getToolExecutions()));
                } finally {
                    Thread.interrupted();
                }
            }

            private LlmCallOutcome handleExecutionException(TurnState turnState, RuntimeException llmFailure,
                    HistoryWriter historyWriter) {
                return handleLlmError(turnState, llmFailure, historyWriter);
            }

            private LlmCallOutcome handleRuntimeException(TurnState turnState, RuntimeException e,
                    HistoryWriter historyWriter) {
                return handleLlmError(turnState, e, historyWriter);
            }

            private LlmCallOutcome handlePreCallResilience(TurnState turnState, PreCallResilienceException exception) {
                return applyResilienceOutcome(turnState, exception.outcome(), exception.errorCode(), exception,
                        exception.traceBefore(), exception.traceAfter());
            }

            private LlmCallOutcome handleLlmError(TurnState turnState, RuntimeException error,
                    HistoryWriter historyWriter) {
                AgentContext context = turnState.getContext();
                String code = LlmErrorClassifier.classifyFromThrowable(error);

                if (LlmErrorClassifier.isContextOverflowCode(code)
                        && compactionCoordinator.recoverFromContextOverflow(context, turnState.getLlmCalls(),
                                turnState.getRetryAttempt())) {
                    turnState.incrementRetryAttempt();
                    turnState.setLastRetryCode(code);
                    return new LlmCallOutcome.RetryScheduled();
                }

                if (resilienceOrchestrator != null && runtimeConfigService.isResilienceEnabled()) {
                    return handleLlmErrorWithResilience(turnState, error, code, historyWriter);
                }

                if (turnState.isRetryEnabled() && LlmErrorClassifier.isTransientCode(code)
                        && turnState.getRetryAttempt() < turnState.getMaxRetries()) {
                    turnState.incrementRetryAttempt();
                    turnState.setLastRetryCode(code);
                    scheduleRetry(context, turnState.getLlmCalls(), turnState.getRetryAttempt(),
                            turnState.getMaxRetries(),
                            turnState.getRetryBaseDelayMs(), code);
                    return new LlmCallOutcome.RetryScheduled();
                }

                emitRuntimeEvent(context, RuntimeEventType.LLM_FINISHED,
                        eventPayload("attempt", turnState.getLlmCalls(), "success", false, "code", code));
                emitRuntimeEvent(context, RuntimeEventType.TURN_FAILED,
                        eventPayload("reason", "llm_error", "code", code));
                return new LlmCallOutcome.Failed(
                        failLlmInvocation(context, error, turnState.getLlmCalls(), turnState.getToolExecutions()));
            }

            private LlmCallOutcome handleLlmErrorWithResilience(TurnState turnState, RuntimeException error,
                    String code, HistoryWriter historyWriter) {
                AgentContext context = turnState.getContext();
                RuntimeConfig.ResilienceConfig config = runtimeConfigService.getResilienceConfig();
                ResilienceTraceSnapshot traceBefore = captureResilienceTraceSnapshot(context);
                LlmResilienceOrchestrator.ResilienceOutcome outcome = resilienceOrchestrator.handle(
                        context, error, code, turnState.getRetryAttempt(), config);
                ResilienceTraceSnapshot traceAfter = captureResilienceTraceSnapshot(context);
                return applyResilienceOutcome(turnState, outcome, code, error, traceBefore, traceAfter);
            }

            private LlmCallOutcome applyResilienceOutcome(TurnState turnState,
                    LlmResilienceOrchestrator.ResilienceOutcome outcome, String code, Throwable failure,
                    ResilienceTraceSnapshot traceBefore, ResilienceTraceSnapshot traceAfter) {
                AgentContext context = turnState.getContext();
                traceResilienceOutcome(context, outcome, code, turnState.getRetryAttempt(), traceBefore, traceAfter);
                publishResilienceProgress(context, outcome, code, turnState.getRetryAttempt(), traceBefore,
                        traceAfter);
                switch (outcome) {
                case LlmResilienceOrchestrator.ResilienceOutcome.RetryNow retryNow -> {
                    turnState.incrementRetryAttempt();
                    turnState.setLastRetryCode(code);
                    context.setAttribute(ContextAttributes.RESILIENCE_RECOVERY_LAYER, retryNow.layer());
                    emitRuntimeEvent(context, RuntimeEventType.RETRY_STARTED,
                            eventPayload("layer", retryNow.layer(), "detail", retryNow.detail(), "code", code));
                    return new LlmCallOutcome.RetryScheduled();
                }
                case LlmResilienceOrchestrator.ResilienceOutcome.Suspended suspended -> {
                    context.setAttribute(ContextAttributes.RESILIENCE_TURN_SUSPENDED, true);
                    context.setAttribute(ContextAttributes.LLM_ERROR_CODE, code);
                    emitRuntimeEvent(context, RuntimeEventType.TURN_FAILED,
                            eventPayload("reason", "resilience_suspended", "code", code));
                    return new LlmCallOutcome.Failed(
                            failLlmSuspended(context, suspended.userMessage(),
                                    turnState.getLlmCalls(), turnState.getToolExecutions()));
                }
                case LlmResilienceOrchestrator.ResilienceOutcome.Exhausted exhausted -> {
                    emitRuntimeEvent(context, RuntimeEventType.LLM_FINISHED,
                            eventPayload("attempt", turnState.getLlmCalls(), "success", false, "code", code));
                    emitRuntimeEvent(context, RuntimeEventType.TURN_FAILED,
                            eventPayload("reason", "llm_error", "code", code));
                    return new LlmCallOutcome.Failed(
                            failLlmInvocation(context, failure, turnState.getLlmCalls(),
                                    turnState.getToolExecutions()));
                }
                default -> throw new IllegalStateException("Unsupported resilience outcome: "
                        + outcome.getClass().getName());
                }
            }

            private void scheduleRetry(AgentContext context, int llmCalls, int attempt, int maxAttempts,
                    long baseDelayMs,
                    String code) {
                flushProgress(context, "retry");
                long delayMs = (long) Math.min(3000, baseDelayMs * Math.pow(2, Math.max(0, attempt - 1)));
                String model = context.getAttribute(ContextAttributes.LLM_MODEL);
                log.warn(
                        "[ToolLoop] Transient LLM failure, scheduling retry (code={}, retry={}/{}, delayMs={}, llmCall={}, model={})",
                        code, attempt, maxAttempts, delayMs, llmCalls, model != null ? model : "unknown");
                emitRuntimeEvent(context, RuntimeEventType.RETRY_STARTED,
                        eventPayload("attempt", attempt, "maxAttempts", maxAttempts, "delayMs", delayMs, "code", code));
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            private void logRetrySucceeded(AgentContext context, int llmCalls, int retryAttempt, int maxRetries,
                    String code) {
                String model = context.getAttribute(ContextAttributes.LLM_MODEL);
                log.info("[ToolLoop] LLM retry succeeded (code={}, retry={}/{}, llmCall={}, model={})",
                        code != null && !code.isBlank() ? code : "unknown", retryAttempt, maxRetries, llmCalls,
                        model != null ? model : "unknown");
            }

            // ==================== LLM call execution ====================

            private LlmResponse executeLlmCall(AgentContext context, int attempt,
                    RuntimeConfig.TracingConfig tracingConfig)
                    throws InterruptedException, ExecutionException {
                ModelSelectionService.ModelSelection selection = selectModel(context);
                applySelectedModel(context, selection);
                ResilienceTraceSnapshot preCallTraceBefore = captureResilienceTraceSnapshot(context);
                LlmResilienceOrchestrator.ResilienceOutcome preCallOutcome = preflightOpenCircuit(context);
                if (preCallOutcome != null) {
                    throw new PreCallResilienceException(preCallOutcome, preCallTraceBefore,
                            captureResilienceTraceSnapshot(context));
                }
                Map<String, Object> requestContextAttributes = buildRequestContextAttributes(context, selection,
                        attempt);
                TraceContext llmSpan = startChildSpan(context, "llm.chat", TraceSpanKind.LLM, requestContextAttributes);
                // Span finish must cover preflight/buildRequest too: if those throw,
                // the caller's RuntimeException handler will swallow the exception
                // without ever finishing this span, leaving an orphan in the trace.
                boolean succeeded = false;
                Throwable failureCause = null;
                try {
                    appendRequestContextEvent(context, llmSpan, requestContextAttributes);
                    LlmRequest request = buildRequestWithPreflight(context,
                            llmSpan != null ? llmSpan : context.getTraceContext(), selection, attempt);
                    captureLlmSnapshot(context, llmSpan, tracingConfig, "request", request);
                    try (MdcSupport.Scope ignored = MdcSupport.withContext(buildTraceMdcContext(llmSpan, context))) {
                        LlmResponse response = llmPort.chat(request).get();
                        captureLlmSnapshot(context, llmSpan, tracingConfig, "response", response);
                        succeeded = true;
                        return response;
                    }
                } catch (InterruptedException | ExecutionException | RuntimeException e) {
                    failureCause = e;
                    throw e;
                } finally {
                    if (succeeded) {
                        finishChildSpan(context, llmSpan, TraceStatusCode.OK, null);
                    } else {
                        finishChildSpan(context, llmSpan, TraceStatusCode.ERROR,
                                failureCause != null ? failureCause.getMessage() : null);
                    }
                }
            }

            private LlmRequest buildRequestWithPreflight(AgentContext context, TraceContext traceContext,
                    ModelSelectionService.ModelSelection selection, int llmCall) {
                return preflightPhase.preflight(context,
                        () -> buildRequest(context, traceContext, selection), llmCall);
            }

            // ==================== Empty response handling ====================

            private String getEmptyFinalResponseCode(LlmResponse response, AgentContext context) {
                if (context.isVoiceRequested()) {
                    return null;
                }
                String voiceText = context.getVoiceText();
                if (voiceText != null && !voiceText.isBlank()) {
                    return null;
                }
                String code = LlmErrorClassifier.classifyEmptyFinalResponse(response);
                if (LlmErrorClassifier.UNKNOWN.equals(code)) {
                    return null;
                }
                return code;
            }

            private void logEmptyFinalResponseRetry(AgentContext context, LlmResponse response, String reasonCode,
                    int retryAttempt) {
                String model = context.getAttribute(ContextAttributes.LLM_MODEL);
                String finishReason = response != null ? response.getFinishReason() : null;
                String content = response != null ? response.getContent() : null;
                int contentLength = content != null ? content.length() : 0;
                boolean hasToolCalls = response != null && response.hasToolCalls();
                log.warn("[ToolLoop] Empty final LLM response, scheduling retry "
                        + "(code={}, retry={}/{}, model={}, finishReason={}, contentLength={}, hasToolCalls={})",
                        reasonCode, retryAttempt, EMPTY_FINAL_RESPONSE_MAX_RETRIES,
                        model != null ? model : "unknown", finishReason != null ? finishReason : "unknown",
                        contentLength, hasToolCalls);
            }

            // ==================== Failure reporting ====================

            private ToolLoopTurnResult failEmptyFinalResponse(AgentContext context, LlmResponse response,
                    String reasonCode,
                    int llmCalls, int toolExecutions) {
                String model = context.getAttribute(ContextAttributes.LLM_MODEL);
                String finishReason = response != null ? response.getFinishReason() : null;
                String diagnostic = LlmErrorClassifier.withCode(reasonCode, String.format(
                        "LLM returned empty final response after %d attempt(s) (model=%s, finishReason=%s)",
                        llmCalls, model != null ? model : "unknown", finishReason != null ? finishReason : "unknown"));
                log.error("[ToolLoop] {}", diagnostic);
                context.setAttribute(ContextAttributes.LLM_ERROR, diagnostic);
                context.setAttribute(ContextAttributes.LLM_ERROR_CODE, reasonCode);
                context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, false);
                context.addFailure(new FailureEvent(FailureSource.LLM, "DefaultToolLoopSystem",
                        FailureKind.VALIDATION, diagnostic, clock.instant()));
                flushProgress(context, "llm_failure");
                clearProgress(context);
                return new ToolLoopTurnResult(context, false, llmCalls, toolExecutions);
            }

            private ToolLoopTurnResult failLlmSuspended(AgentContext context, String userMessage,
                    int llmCalls, int toolExecutions) {
                log.info("[ToolLoop] Turn suspended by resilience orchestrator: {}", userMessage);
                context.setAttribute(ContextAttributes.LLM_ERROR, userMessage);
                context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, false);
                context.setOutgoingResponse(OutgoingResponse.textOnly(userMessage));
                context.addFailure(new FailureEvent(FailureSource.LLM, "LlmResilienceOrchestrator",
                        FailureKind.EXCEPTION, userMessage, clock.instant()));
                flushProgress(context, "llm_suspended");
                clearProgress(context);
                return new ToolLoopTurnResult(context, false, llmCalls, toolExecutions);
            }

            private ToolLoopTurnResult failLlmInvocation(AgentContext context, Throwable throwable, int llmCalls,
                    int toolExecutions) {
                String reasonCode = LlmErrorClassifier.classifyFromThrowable(throwable);
                Throwable rootCause = findRootCause(throwable);
                String model = context.getAttribute(ContextAttributes.LLM_MODEL);
                String errorType = rootCause != null ? rootCause.getClass().getName() : "unknown";
                String errorMessage = rootCause != null && rootCause.getMessage() != null ? rootCause.getMessage()
                        : "n/a";
                String diagnostic = LlmErrorClassifier.withCode(reasonCode, String.format(
                        "LLM call failed after %d attempt(s) (model=%s, errorType=%s, message=%s)",
                        llmCalls, model != null ? model : "unknown", errorType, errorMessage));
                log.error("[ToolLoop] {}", diagnostic, throwable);
                context.setAttribute(ContextAttributes.LLM_ERROR, diagnostic);
                context.setAttribute(ContextAttributes.LLM_ERROR_CODE, reasonCode);
                context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, false);
                context.addFailure(new FailureEvent(FailureSource.LLM, "DefaultToolLoopSystem",
                        FailureKind.EXCEPTION, diagnostic, clock.instant()));
                flushProgress(context, "llm_failure");
                clearProgress(context);
                return new ToolLoopTurnResult(context, false, llmCalls, toolExecutions);
            }

            // ==================== Request building ====================

            private LlmRequest buildRequest(AgentContext context, TraceContext traceContext,
                    ModelSelectionService.ModelSelection selection) {
                ConversationView view = viewBuilder.buildView(context, selection.model());
                if (!view.diagnostics().isEmpty()) {
                    log.debug("[ToolLoop] conversation view diagnostics: {}", view.diagnostics());
                }
                storeSelectedModel(context, selection.model());
                applySelectedModel(context, selection);

                return LlmRequest.builder()
                        .model(selection.model())
                        .reasoningEffort(selection.reasoning())
                        .systemPrompt(context.getSystemPrompt())
                        .messages(view.messages())
                        .tools(context.getAvailableTools())
                        .toolResults(context.getToolResults())
                        .sessionId(context.getSession() != null ? context.getSession().getId() : null)
                        .traceId(traceContext != null ? traceContext.getTraceId() : null)
                        .traceSpanId(traceContext != null ? traceContext.getSpanId() : null)
                        .traceParentSpanId(traceContext != null ? traceContext.getParentSpanId() : null)
                        .traceRootKind(traceContext != null ? traceContext.getRootKind() : null)
                        .modelTier(normalizeTierForTrace(context.getModelTier()))
                        .build();
            }

            private Map<String, Object> buildRequestContextAttributes(AgentContext context,
                    ModelSelectionService.ModelSelection selection, int attempt) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("attempt", attempt);
                String skillName = null;
                if (context != null && context.getActiveSkill() != null && context.getActiveSkill().getName() != null
                        && !context.getActiveSkill().getName().isBlank()) {
                    skillName = context.getActiveSkill().getName();
                } else if (context != null) {
                    skillName = readContextAttribute(context, ContextAttributes.ACTIVE_SKILL_NAME);
                }
                if (skillName != null && !skillName.isBlank()) {
                    attributes.put("context.skill.name", skillName);
                }
                String tier = context != null ? normalizeTierForTrace(context.getModelTier()) : "balanced";
                attributes.put("context.model.tier", tier);
                if (selection != null && selection.model() != null && !selection.model().isBlank()) {
                    attributes.put("context.model.id", selection.model());
                }
                if (selection != null && selection.reasoning() != null && !selection.reasoning().isBlank()) {
                    attributes.put("context.model.reasoning", selection.reasoning());
                }
                if (context != null) {
                    String source = readContextAttribute(context, ContextAttributes.MODEL_TIER_SOURCE);
                    if (source != null && !source.isBlank()) {
                        attributes.put("context.model.source", source);
                    }
                    putIfPresent(attributes, ContextAttributes.SELF_EVOLVING_RUN_ID,
                            readContextAttribute(context, ContextAttributes.SELF_EVOLVING_RUN_ID));
                    putIfPresent(attributes, ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID,
                            readContextAttribute(context, ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID));
                }
                return attributes;
            }

            // ==================== Utility methods ====================

    void applyAttachments(AgentContext context, List<me.golemcore.bot.domain.model.Attachment> attachments) {
        if (attachments.isEmpty()) {
            return;
        }
        log.debug("[ToolLoop] Applying {} attachment(s) to OutgoingResponse", attachments.size());

        me.golemcore.bot.domain.model.OutgoingResponse existing = context.getOutgoingResponse();
        me.golemcore.bot.domain.model.OutgoingResponse.OutgoingResponseBuilder builder;
        if (existing != null) {
            builder = me.golemcore.bot.domain.model.OutgoingResponse.builder()
                    .text(existing.getText())
                    .voiceRequested(existing.isVoiceRequested())
                    .voiceText(existing.getVoiceText())
                    .skipAssistantHistory(existing.isSkipAssistantHistory());
            for (me.golemcore.bot.domain.model.Attachment attachment : existing.getAttachments()) {
                builder.attachment(attachment);
            }
        } else {
            builder = me.golemcore.bot.domain.model.OutgoingResponse.builder();
        }
        for (me.golemcore.bot.domain.model.Attachment attachment : attachments) {
            builder.attachment(attachment);
        }
        me.golemcore.bot.domain.model.OutgoingResponse updated = builder.build();
        context.setOutgoingResponse(updated);
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, updated);
    }

    boolean isInterruptRequested(AgentContext context) {
        Object contextFlag = context.getAttribute(ContextAttributes.TURN_INTERRUPT_REQUESTED);
        if (Boolean.TRUE.equals(contextFlag)) {
            return true;
        }
        Map<String, Object> metadata = context.getSession() != null ? context.getSession().getMetadata() : null;
        return metadata != null && Boolean.TRUE.equals(metadata.get(ContextAttributes.TURN_INTERRUPT_REQUESTED));
    }

    void clearInterruptFlag(AgentContext context) {
        context.setAttribute(ContextAttributes.TURN_INTERRUPT_REQUESTED, false);
        if (context.getSession() != null && context.getSession().getMetadata() != null) {
            context.getSession().getMetadata().put(ContextAttributes.TURN_INTERRUPT_REQUESTED, false);
        }
    }

            private void appendRequestContextEvent(AgentContext context, TraceContext llmSpan,
                    Map<String, Object> attributes) {
                if (traceService == null || context == null || context.getSession() == null || llmSpan == null) {
                    return;
                }
                Map<String, Object> eventAttributes = new LinkedHashMap<>();
                copyAttribute(attributes, eventAttributes, "context.skill.name", "skill");
                copyAttribute(attributes, eventAttributes, "context.model.tier", "tier");
                copyAttribute(attributes, eventAttributes, "context.model.id", "model_id");
                copyAttribute(attributes, eventAttributes, "context.model.reasoning", "reasoning");
                copyAttribute(attributes, eventAttributes, "context.model.source", "source");
                copyAttribute(attributes, eventAttributes, ContextAttributes.SELF_EVOLVING_RUN_ID, "run_id");
                copyAttribute(attributes, eventAttributes, ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID,
                        "artifact_bundle_id");
                traceService.appendEvent(context.getSession(), llmSpan, "request.context", clock.instant(),
                        eventAttributes);
            }

            private void copyAttribute(Map<String, Object> source, Map<String, Object> target, String sourceKey,
                    String targetKey) {
                if (source == null || target == null || sourceKey == null || targetKey == null) {
                    return;
                }
                Object value = source.get(sourceKey);
                if (value instanceof String stringValue && !stringValue.isBlank()) {
                    target.put(targetKey, stringValue);
                    return;
                }
                if (value != null) {
                    target.put(targetKey, value);
                }
            }

            private String normalizeTierForTrace(String tier) {
                if (tier == null || tier.isBlank() || "default".equalsIgnoreCase(tier)) {
                    return "balanced";
                }
                String normalized = me.golemcore.bot.domain.model.ModelTierCatalog.normalizeTierId(tier);
                return normalized != null ? normalized : tier;
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

            private ModelSelectionService.ModelSelection selectModel(AgentContext context) {
                ModelSelectionService.ModelSelection fallbackSelection = resolveRouterFallbackSelection(context);
                if (fallbackSelection != null) {
                    return fallbackSelection;
                }
                if (modelSelectionService == null) {
                    return new ModelSelectionService.ModelSelection(null, null);
                }
                return modelSelectionService.resolveForTier(context != null ? context.getModelTier() : null);
            }

            private LlmResilienceOrchestrator.ResilienceOutcome preflightOpenCircuit(AgentContext context) {
                if (resilienceOrchestrator == null || runtimeConfigService == null
                        || !runtimeConfigService.isResilienceEnabled()) {
                    return null;
                }
                return resilienceOrchestrator.preflightOpenCircuit(context, runtimeConfigService.getResilienceConfig());
            }

            private ModelSelectionService.ModelSelection resolveRouterFallbackSelection(AgentContext context) {
                String model = readContextAttribute(context, ContextAttributes.RESILIENCE_L2_FALLBACK_MODEL);
                if (model == null) {
                    return null;
                }
                String reasoning = readContextAttribute(context, ContextAttributes.RESILIENCE_L2_FALLBACK_REASONING);
                if (modelSelectionService != null) {
                    return modelSelectionService.resolveRouterFallbackSelection(
                            context.getModelTier(), model, reasoning);
                }
                return new ModelSelectionService.ModelSelection(model, reasoning);
            }

            private void applySelectedModel(AgentContext context, ModelSelectionService.ModelSelection selection) {
                if (context == null || selection == null) {
                    return;
                }
                context.setAttribute(ContextAttributes.LLM_MODEL, selection.model());
                context.setAttribute(ContextAttributes.LLM_REASONING, selection.reasoning());
            }

            private void storeSelectedModel(AgentContext context, String model) {
                if (context.getSession() == null) {
                    return;
                }
                Map<String, Object> metadata = context.getSession().getMetadata();
                if (metadata == null) {
                    return;
                }
                metadata.put(ContextAttributes.LLM_MODEL, model);
            }

            private TurnLimitReason buildStopReason(TurnState turnState) {
                if (turnState.getLlmCalls() >= turnState.getMaxLlmCalls()) {
                    return TurnLimitReason.MAX_LLM_CALLS;
                }
                if (turnState.getToolExecutions() >= turnState.getMaxToolExecutions()) {
                    return TurnLimitReason.MAX_TOOL_EXECUTIONS;
                }
                if (!clock.instant().isBefore(turnState.getDeadline())) {
                    return TurnLimitReason.DEADLINE;
                }
                return TurnLimitReason.UNKNOWN;
            }

            private String buildStopReasonMessage(TurnLimitReason reason, int maxLlmCalls, int maxToolExecutions) {
                return switch (reason) {
                case MAX_LLM_CALLS -> "reached max internal LLM calls (" + maxLlmCalls + ")";
                case MAX_TOOL_EXECUTIONS -> "reached max tool executions (" + maxToolExecutions + ")";
                case DEADLINE -> "deadline exceeded";
                case UNKNOWN -> "stopped by guard";
                };
            }

            private Throwable findRootCause(Throwable throwable) {
                if (throwable == null) {
                    return null;
                }
                Throwable current = throwable;
                int depth = 0;
                while (current.getCause() != null && !current.equals(current.getCause()) && depth < 32) {
                    current = current.getCause();
                    depth++;
                }
                return current;
            }

            private RuntimeException toRuntimeException(ExecutionException executionException) {
                Throwable cause = executionException.getCause() != null ? executionException.getCause()
                        : executionException;
                if (cause instanceof RuntimeException runtimeException) {
                    return runtimeException;
                }
                return new RuntimeException(cause.getMessage(), cause);
            }

            // ==================== Tracing ====================

            /**
             * Converts resilience-domain trace steps into regular child spans under the
             * active turn trace.
             *
             * <p>
             * The resilience layer intentionally stays storage-agnostic, so this phase is
             * the boundary that turns L1-L5 recovery decisions into visible tracing data.
             */
            private void traceResilienceOutcome(AgentContext context,
                    LlmResilienceOrchestrator.ResilienceOutcome outcome, String errorCode, int attempt,
                    ResilienceTraceSnapshot before, ResilienceTraceSnapshot after) {
                if (outcome == null) {
                    return;
                }
                TraceStatusCode outcomeStatusCode = outcome instanceof LlmResilienceOrchestrator.ResilienceOutcome.Exhausted
                        ? TraceStatusCode.ERROR
                        : TraceStatusCode.OK;
                List<LlmResilienceOrchestrator.ResilienceTraceStep> traceSteps = outcome.traceSteps() != null
                        ? outcome.traceSteps()
                        : List.of();
                for (LlmResilienceOrchestrator.ResilienceTraceStep step : traceSteps) {
                    traceResilienceStep(context, step, errorCode, attempt, before, after,
                            resilienceStepStatusCode(step, outcomeStatusCode));
                }
                if (hasModelSwitch(before, after)) {
                    traceModelSwitch(context, traceSteps, errorCode, attempt, before, after, outcomeStatusCode);
                }
            }

            /**
             * Publishes user-facing notices for each resilience step in the current turn.
             */
            private void publishResilienceProgress(AgentContext context,
                    LlmResilienceOrchestrator.ResilienceOutcome outcome, String errorCode, int attempt,
                    ResilienceTraceSnapshot before, ResilienceTraceSnapshot after) {
                if (turnProgressService == null) {
                    return;
                }
                for (LlmResilienceOrchestrator.ResilienceTraceStep step : outcome.traceSteps()) {
                    String notice = resilienceProgressNotice(step);
                    if (notice == null || notice.isBlank()) {
                        continue;
                    }
                    turnProgressService.publishSummary(context, notice,
                            buildResilienceProgressMetadata(step, errorCode, attempt, before, after));
                }
            }

            /**
             * Uses the same structured context as tracing, with nulls removed for
             * transport-safe progress payloads.
             */
            private Map<String, Object> buildResilienceProgressMetadata(
                    LlmResilienceOrchestrator.ResilienceTraceStep step, String errorCode, int attempt,
                    ResilienceTraceSnapshot before, ResilienceTraceSnapshot after) {
                Map<String, Object> metadata = buildResilienceTraceAttributes(step, errorCode, attempt, before, after);
                metadata.put("kind", "llm_resilience");
                metadata.values().removeIf(Objects::isNull);
                return metadata;
            }

            /**
             * Converts a resilience trace step into concise current-session copy.
             */
            private String resilienceProgressNotice(LlmResilienceOrchestrator.ResilienceTraceStep step) {
                if (step == null) {
                    return null;
                }
                String layer = baseLayer(step.layer());
                return switch (layer != null ? layer : "") {
                case "L1" -> "Retrying the same LLM model after a transient provider failure ("
                        + fallbackDetail(step) + ").";
                case "L2" -> "Switching to fallback model "
                        + displayAttribute(step, "fallback.model", "configured fallback")
                        + " after the current LLM model failed.";
                case "L3" -> circuitBreakerProgressNotice(step);
                case "L4" -> "Applying LLM degradation: " + fallbackStrategy(step) + " ("
                        + fallbackDetail(step) + ").";
                case "L5" -> l5ProgressNotice(step);
                default -> {
                    log.warn("[ToolLoop] Unknown resilience layer in progress notice: layer={}", step.layer());
                    yield null;
                }
                };
            }

            private String circuitBreakerProgressNotice(LlmResilienceOrchestrator.ResilienceTraceStep step) {
                if ("state_transition".equals(step.action())) {
                    return "Circuit breaker moved "
                            + displayAttribute(step, "circuit.state.before", "unknown")
                            + " -> " + displayAttribute(step, "circuit.state.after", "unknown")
                            + " for " + displayAttribute(step, "circuit.provider", "the LLM model") + ".";
                }
                return "Circuit breaker is open for "
                        + displayAttribute(step, "circuit.provider", "the LLM model")
                        + "; skipping local degradation and waiting before retry.";
            }

            private String l5ProgressNotice(LlmResilienceOrchestrator.ResilienceTraceStep step) {
                if ("suspend".equals(step.action())) {
                    return "Turn suspended: " + fallbackDetail(step);
                }
                return "LLM recovery is exhausted: " + fallbackDetail(step) + ".";
            }

            private String fallbackStrategy(LlmResilienceOrchestrator.ResilienceTraceStep step) {
                Object value = step.attributes() != null ? step.attributes().get("resilience.strategy") : null;
                if (value instanceof String strategy && !strategy.isBlank()) {
                    return strategy;
                }
                String strategy = strategyName(step.layer());
                return strategy != null ? strategy : "request simplification";
            }

            private String fallbackDetail(LlmResilienceOrchestrator.ResilienceTraceStep step) {
                return step.detail() != null && !step.detail().isBlank() ? step.detail() : "retrying";
            }

            private String displayAttribute(LlmResilienceOrchestrator.ResilienceTraceStep step, String key,
                    String fallback) {
                Object value = step.attributes() != null ? step.attributes().get(key) : null;
                return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : fallback;
            }

            /**
             * Emits one explicit {@code llm.resilience.*} span for a single recovery layer
             * action.
             */
            private void traceResilienceStep(AgentContext context,
                    LlmResilienceOrchestrator.ResilienceTraceStep step, String errorCode, int attempt,
                    ResilienceTraceSnapshot before, ResilienceTraceSnapshot after, TraceStatusCode statusCode) {
                if (step == null) {
                    return;
                }
                Map<String, Object> attributes = buildResilienceTraceAttributes(step, errorCode, attempt, before,
                        after);
                TraceContext span = startChildSpan(context, resilienceSpanName(step.layer()), TraceSpanKind.INTERNAL,
                        attributes);
                finishChildSpan(context, span, statusCode, statusCode == TraceStatusCode.ERROR ? step.detail() : null);
            }

            /**
             * Emits a dedicated model-switch span instead of overloading L2/L4 spans with
             * model-change semantics.
             *
             * <p>
             * This keeps circuit-breaker spans honest: L3 may appear in the same outcome as
             * L2, but L3 itself did not switch the model.
             */
            private void traceModelSwitch(AgentContext context,
                    List<LlmResilienceOrchestrator.ResilienceTraceStep> steps, String errorCode, int attempt,
                    ResilienceTraceSnapshot before, ResilienceTraceSnapshot after, TraceStatusCode statusCode) {
                LlmResilienceOrchestrator.ResilienceTraceStep primaryStep = primaryResilienceStep(steps);
                Map<String, Object> attributes = new LinkedHashMap<>();
                putTraceAttribute(attributes, "resilience.layer",
                        primaryStep != null ? baseLayer(primaryStep.layer()) : null);
                if (primaryStep != null && primaryStep.action() != null) {
                    attributes.put("resilience.action", primaryStep.action());
                }
                attributes.put("llm.error.code", errorCode);
                attributes.put("attempt", attempt);
                addSnapshotAttributes(attributes, before, after);
                TraceContext span = startChildSpan(context, "llm.model.switch", TraceSpanKind.INTERNAL, attributes);
                finishChildSpan(context, span, statusCode, statusCode == TraceStatusCode.ERROR
                        ? "model switch failed with resilience outcome"
                        : null);
            }

            /**
             * Chooses the span status for one resilience step instead of copying the
             * terminal outcome status to every intermediate action.
             *
             * <p>
             * For example, an exhausted cascade can contain a successful L3 state
             * transition followed by a terminal L5 exhaustion; only the latter should be
             * marked as {@code ERROR}.
             */
            private TraceStatusCode resilienceStepStatusCode(
                    LlmResilienceOrchestrator.ResilienceTraceStep step, TraceStatusCode outcomeStatusCode) {
                if (outcomeStatusCode != TraceStatusCode.ERROR) {
                    return outcomeStatusCode;
                }
                return isTerminalFailureStep(step) ? TraceStatusCode.ERROR : TraceStatusCode.OK;
            }

            /**
             * Identifies the trace step that represents the terminal fallback failure.
             */
            private boolean isTerminalFailureStep(LlmResilienceOrchestrator.ResilienceTraceStep step) {
                return step != null
                        && "L5".equals(baseLayer(step.layer()))
                        && "exhausted".equals(step.action());
            }

            /**
             * Builds common span attributes for one resilience layer.
             *
             * <p>
             * Only the layer that owns the model transition is allowed to set
             * {@code model.changed=true}; a separate {@code llm.model.switch} span always
             * records the actual before/after values.
             */
            private Map<String, Object> buildResilienceTraceAttributes(
                    LlmResilienceOrchestrator.ResilienceTraceStep step, String errorCode, int attempt,
                    ResilienceTraceSnapshot before, ResilienceTraceSnapshot after) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                if (step.attributes() != null) {
                    attributes.putAll(step.attributes());
                }
                attributes.put("resilience.layer", baseLayer(step.layer()));
                attributes.put("resilience.action", step.action());
                if (step.detail() != null && !step.detail().isBlank()) {
                    attributes.put("resilience.detail", step.detail());
                }
                String strategy = strategyName(step.layer());
                if (strategy != null && !attributes.containsKey("resilience.strategy")) {
                    attributes.put("resilience.strategy", strategy);
                }
                attributes.put("llm.error.code", errorCode);
                attributes.put("attempt", attempt);
                addSnapshotAttributes(attributes, before, after,
                        ownsModelSwitch(step) && hasModelSwitch(before, after));
                return attributes;
            }

            /**
             * Adds before/after model context and marks it as a real model switch.
             */
            private void addSnapshotAttributes(Map<String, Object> attributes, ResilienceTraceSnapshot before,
                    ResilienceTraceSnapshot after) {
                addSnapshotAttributes(attributes, before, after, hasModelSwitch(before, after));
            }

            /**
             * Adds before/after model context with caller-controlled switch semantics.
             *
             * <p>
             * L3 spans receive the same context values for diagnostics, but
             * {@code model.changed=false} so trace consumers do not attribute the switch to
             * the circuit breaker.
             */
            private void addSnapshotAttributes(Map<String, Object> attributes, ResilienceTraceSnapshot before,
                    ResilienceTraceSnapshot after, boolean modelChanged) {
                putTraceAttribute(attributes, "model.before", before != null ? before.model() : null);
                putTraceAttribute(attributes, "model.after", after != null ? after.model() : null);
                putTraceAttribute(attributes, "reasoning.before", before != null ? before.reasoning() : null);
                putTraceAttribute(attributes, "reasoning.after", after != null ? after.reasoning() : null);
                putTraceAttribute(attributes, "model.tier.before", before != null ? before.tier() : null);
                putTraceAttribute(attributes, "model.tier.after", after != null ? after.tier() : null);
                putTraceAttribute(attributes, "fallback.model", after != null ? after.fallbackModel() : null);
                putTraceAttribute(attributes, "fallback.reasoning", after != null ? after.fallbackReasoning() : null);
                putTraceAttribute(attributes, "fallback.mode", after != null ? after.fallbackMode() : null);
                attributes.put("model.changed", modelChanged);
                attributes.put("model.tier.changed", modelChanged && !Objects.equals(
                        before != null ? before.tier() : null,
                        after != null ? after.tier() : null));
            }

            /**
             * Adds a trace attribute only when it has a meaningful value.
             */
            private void putTraceAttribute(Map<String, Object> attributes, String key, Object value) {
                if (attributes == null || key == null || key.isBlank() || value == null) {
                    return;
                }
                if (value instanceof String stringValue && stringValue.isBlank()) {
                    return;
                }
                attributes.put(key, value);
            }

            /**
             * Detects model, reasoning, or tier changes across a resilience decision.
             */
            private boolean hasModelSwitch(ResilienceTraceSnapshot before, ResilienceTraceSnapshot after) {
                if (before == null || after == null) {
                    return false;
                }
                return !Objects.equals(before.model(), after.model())
                        || !Objects.equals(before.reasoning(), after.reasoning())
                        || !Objects.equals(before.tier(), after.tier());
            }

            /**
             * Returns true when the layer is allowed to own model-switch semantics.
             */
            private boolean ownsModelSwitch(LlmResilienceOrchestrator.ResilienceTraceStep step) {
                String layer = step != null ? baseLayer(step.layer()) : null;
                return "L2".equals(layer) || "L4".equals(layer);
            }

            /**
             * Selects the resilience step that should be associated with the separate
             * model-switch span.
             */
            private LlmResilienceOrchestrator.ResilienceTraceStep primaryResilienceStep(
                    List<LlmResilienceOrchestrator.ResilienceTraceStep> steps) {
                if (steps == null || steps.isEmpty()) {
                    return null;
                }
                return steps.stream()
                        .filter(step -> step != null && !"L3".equals(baseLayer(step.layer())))
                        .findFirst()
                        .orElse(steps.getFirst());
            }

            /**
             * Builds the canonical span name for a resilience layer.
             */
            private String resilienceSpanName(String layer) {
                String baseLayer = baseLayer(layer);
                return "llm.resilience." + (baseLayer != null ? baseLayer : "unknown");
            }

            /**
             * Normalizes strategy-specific layer labels such as {@code L4:model_downgrade}
             * to their top-level layer.
             */
            private String baseLayer(String layer) {
                if (layer == null || layer.isBlank()) {
                    return null;
                }
                int separator = layer.indexOf(':');
                return separator > 0 ? layer.substring(0, separator) : layer;
            }

            /**
             * Extracts the strategy suffix from a layer label such as
             * {@code L4:model_downgrade}.
             */
            private String strategyName(String layer) {
                if (layer == null || layer.isBlank()) {
                    return null;
                }
                int separator = layer.indexOf(':');
                if (separator < 0 || separator == layer.length() - 1) {
                    return null;
                }
                return layer.substring(separator + 1);
            }

            /**
             * Captures model-related context before and after a resilience decision.
             */
            private ResilienceTraceSnapshot captureResilienceTraceSnapshot(AgentContext context) {
                if (context == null) {
                    return new ResilienceTraceSnapshot(null, null, null, null, null, null);
                }
                return new ResilienceTraceSnapshot(
                        readContextAttribute(context, ContextAttributes.LLM_MODEL),
                        readContextAttribute(context, ContextAttributes.LLM_REASONING),
                        normalizeTierForTrace(context.getModelTier()),
                        readContextAttribute(context, ContextAttributes.RESILIENCE_L2_FALLBACK_MODEL),
                        readContextAttribute(context, ContextAttributes.RESILIENCE_L2_FALLBACK_REASONING),
                        readContextAttribute(context, ContextAttributes.RESILIENCE_L2_FALLBACK_MODE));
            }

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

            private void captureLlmSnapshot(AgentContext context, TraceContext spanContext,
                    RuntimeConfig.TracingConfig tracingConfig, String role, Object payload) {
                if (traceService == null || context == null || context.getSession() == null || spanContext == null
                        || tracingConfig == null || !Boolean.TRUE.equals(tracingConfig.getCaptureLlmPayloads())) {
                    return;
                }
                traceService.captureSnapshot(context.getSession(), spanContext, tracingConfig,
                        role, "application/json", serializeSnapshotPayload(payload));
            }

            private Map<String, String> buildTraceMdcContext(TraceContext spanContext, AgentContext context) {
                if (spanContext == null) {
                    return Map.of();
                }
                return TraceMdcSupport.buildMdcContext(spanContext,
                        context != null ? context.getAttributes() : Map.of());
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

            private static final class PreCallResilienceException extends RuntimeException {
                private static final long serialVersionUID = 1L;

                private final transient LlmResilienceOrchestrator.ResilienceOutcome resilienceOutcome;
                private final transient ResilienceTraceSnapshot beforeTraceSnapshot;
                private final transient ResilienceTraceSnapshot afterTraceSnapshot;

                private PreCallResilienceException(LlmResilienceOrchestrator.ResilienceOutcome outcome,
                        ResilienceTraceSnapshot traceBefore, ResilienceTraceSnapshot traceAfter) {
                    super(LlmErrorClassifier.withCode(LlmErrorClassifier.PROVIDER_CIRCUIT_OPEN,
                            "LLM provider skipped because its circuit breaker is open"));
                    this.resilienceOutcome = outcome;
                    this.beforeTraceSnapshot = traceBefore;
                    this.afterTraceSnapshot = traceAfter;
                }

                private LlmResilienceOrchestrator.ResilienceOutcome outcome() {
                    return resilienceOutcome;
                }

                private String errorCode() {
                    return LlmErrorClassifier.PROVIDER_CIRCUIT_OPEN;
                }

                private ResilienceTraceSnapshot traceBefore() {
                    return beforeTraceSnapshot;
                }

                private ResilienceTraceSnapshot traceAfter() {
                    return afterTraceSnapshot;
                }
            }

            /**
             * Immutable model-context snapshot used to compute tracing deltas.
             */
            private record ResilienceTraceSnapshot(String model, String reasoning, String tier, String fallbackModel,
                    String fallbackReasoning, String fallbackMode) {
            }

            // ==================== Events and progress ====================

    void emitRuntimeEvent(AgentContext context, RuntimeEventType type, Map<String, Object> payload) {
        if (runtimeEventService == null || context == null) {
            return;
        }
        runtimeEventService.emit(context, type, payload);
    }

            private void maybePublishAttachmentFallback(AgentContext context, LlmResponse response) {
                if (turnProgressService == null || context == null || response == null
                        || response.getProviderMetadata() == null) {
                    return;
                }
                Object applied = response.getProviderMetadata()
                        .get(me.golemcore.bot.domain.model.LlmProviderMetadataKeys.TOOL_ATTACHMENT_FALLBACK_APPLIED);
                if (!Boolean.TRUE.equals(applied)) {
                    return;
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("kind", "tool_attachment_fallback");
                Object reason = response.getProviderMetadata()
                        .get(me.golemcore.bot.domain.model.LlmProviderMetadataKeys.TOOL_ATTACHMENT_FALLBACK_REASON);
                if (reason instanceof String stringReason && !stringReason.isBlank()) {
                    metadata.put("reason", stringReason);
                }
                turnProgressService.publishSummary(context,
                        "Request was too large for inline tool images, so I retried without them.", metadata);
            }

    void flushProgress(AgentContext context, String reason) {
        if (turnProgressService == null || context == null) {
            return;
        }
        turnProgressService.flushBufferedTools(context, reason);
    }

    void clearProgress(AgentContext context) {
        if (turnProgressService == null || context == null) {
            return;
        }
        turnProgressService.clearProgress(context);
    }

    Map<String, Object> eventPayload(Object... entries) {
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
}
