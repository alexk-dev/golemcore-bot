package me.golemcore.bot.domain.system.toolloop;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.FailureKind;
import me.golemcore.bot.domain.model.FailureSource;
import me.golemcore.bot.domain.model.LlmProviderMetadataKeys;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.TurnLimitReason;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.service.CompactionOrchestrationService;
import me.golemcore.bot.domain.service.MdcSupport;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.RuntimeEventService;
import me.golemcore.bot.domain.service.TraceMdcSupport;
import me.golemcore.bot.domain.service.TraceRuntimeConfigSupport;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.TurnProgressService;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import me.golemcore.bot.domain.system.toolloop.view.ConversationView;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Tool loop orchestrator (single-turn internal loop).
 *
 * <p>
 * Scenario A contract: 1) LLM returns tool calls, 2) tools executed, 3) LLM
 * returns final answer — all inside a single {@link #processTurn} call.
 */
public class DefaultToolLoopSystem implements ToolLoopSystem {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolLoopSystem.class);
    private static final int EMPTY_FINAL_RESPONSE_MAX_RETRIES = 2;

    private final LlmPort llmPort;
    private final ToolExecutorPort toolExecutor;
    private final HistoryWriter historyWriter;
    private final ConversationViewBuilder viewBuilder;
    private final BotProperties.TurnProperties turnSettings;
    private final BotProperties.ToolLoopProperties settings;
    private final ModelSelectionService modelSelectionService;
    @SuppressWarnings("PMD.UnusedPrivateField")
    private final PlanService planService;
    private final RuntimeConfigService runtimeConfigService;
    private final CompactionOrchestrationService compactionOrchestrationService;
    private final RuntimeEventService runtimeEventService;
    private final TurnProgressService turnProgressService;
    private final TraceService traceService;
    private final ToolFailureRecoveryService toolFailureRecoveryService;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public DefaultToolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutor, HistoryWriter historyWriter,
            ConversationViewBuilder viewBuilder, BotProperties.ToolLoopProperties settings,
            ModelSelectionService modelSelectionService, PlanService planService) {
        this(llmPort, toolExecutor, historyWriter, viewBuilder, settings, modelSelectionService, planService,
                Clock.systemUTC());
    }

    // Visible for testing
    public DefaultToolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutor, HistoryWriter historyWriter,
            ConversationViewBuilder viewBuilder, BotProperties.ToolLoopProperties settings,
            ModelSelectionService modelSelectionService, PlanService planService, Clock clock) {
        this.llmPort = llmPort;
        this.toolExecutor = toolExecutor;
        this.historyWriter = historyWriter;
        this.viewBuilder = viewBuilder;
        this.settings = settings;
        this.turnSettings = null;
        this.modelSelectionService = modelSelectionService;
        this.planService = planService;
        this.runtimeConfigService = null;
        this.compactionOrchestrationService = null;
        this.runtimeEventService = null;
        this.turnProgressService = null;
        this.traceService = null;
        this.toolFailureRecoveryService = null;
        this.clock = clock;
    }

    public DefaultToolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutor, HistoryWriter historyWriter,
            ConversationViewBuilder viewBuilder, BotProperties.TurnProperties turnSettings,
            BotProperties.ToolLoopProperties settings, ModelSelectionService modelSelectionService,
            PlanService planService) {
        this(llmPort, toolExecutor, historyWriter, viewBuilder, turnSettings, settings, modelSelectionService,
                planService, Clock.systemUTC());
    }

    // Visible for testing
    public DefaultToolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutor, HistoryWriter historyWriter,
            ConversationViewBuilder viewBuilder, BotProperties.TurnProperties turnSettings,
            BotProperties.ToolLoopProperties settings, ModelSelectionService modelSelectionService,
            PlanService planService, Clock clock) {
        this.llmPort = llmPort;
        this.toolExecutor = toolExecutor;
        this.historyWriter = historyWriter;
        this.viewBuilder = viewBuilder;
        this.turnSettings = turnSettings;
        this.settings = settings;
        this.modelSelectionService = modelSelectionService;
        this.planService = planService;
        this.runtimeConfigService = null;
        this.compactionOrchestrationService = null;
        this.runtimeEventService = null;
        this.turnProgressService = null;
        this.traceService = null;
        this.toolFailureRecoveryService = null;
        this.clock = clock;
    }

    public DefaultToolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutor, HistoryWriter historyWriter,
            ConversationViewBuilder viewBuilder, BotProperties.TurnProperties turnSettings,
            BotProperties.ToolLoopProperties settings, ModelSelectionService modelSelectionService,
            PlanService planService, RuntimeConfigService runtimeConfigService, Clock clock) {
        this(llmPort, toolExecutor, historyWriter, viewBuilder, turnSettings, settings,
                modelSelectionService, planService, runtimeConfigService, null, null, null, null, null, clock);
    }

    public DefaultToolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutor, HistoryWriter historyWriter,
            ConversationViewBuilder viewBuilder, BotProperties.TurnProperties turnSettings,
            BotProperties.ToolLoopProperties settings, ModelSelectionService modelSelectionService,
            PlanService planService, RuntimeConfigService runtimeConfigService,
            CompactionOrchestrationService compactionOrchestrationService,
            RuntimeEventService runtimeEventService, Clock clock) {
        this(llmPort, toolExecutor, historyWriter, viewBuilder, turnSettings, settings, modelSelectionService,
                planService, runtimeConfigService, compactionOrchestrationService, runtimeEventService, null, null,
                null, clock);
    }

    public DefaultToolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutor, HistoryWriter historyWriter,
            ConversationViewBuilder viewBuilder, BotProperties.TurnProperties turnSettings,
            BotProperties.ToolLoopProperties settings, ModelSelectionService modelSelectionService,
            PlanService planService, RuntimeConfigService runtimeConfigService,
            CompactionOrchestrationService compactionOrchestrationService,
            RuntimeEventService runtimeEventService, TurnProgressService turnProgressService, Clock clock) {
        this(llmPort, toolExecutor, historyWriter, viewBuilder, turnSettings, settings, modelSelectionService,
                planService, runtimeConfigService, compactionOrchestrationService, runtimeEventService,
                turnProgressService, null, null, clock);
    }

    public DefaultToolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutor, HistoryWriter historyWriter,
            ConversationViewBuilder viewBuilder, BotProperties.TurnProperties turnSettings,
            BotProperties.ToolLoopProperties settings, ModelSelectionService modelSelectionService,
            PlanService planService, RuntimeConfigService runtimeConfigService,
            CompactionOrchestrationService compactionOrchestrationService,
            RuntimeEventService runtimeEventService, TurnProgressService turnProgressService, TraceService traceService,
            Clock clock) {
        this(llmPort, toolExecutor, historyWriter, viewBuilder, turnSettings, settings, modelSelectionService,
                planService, runtimeConfigService, compactionOrchestrationService, runtimeEventService,
                turnProgressService, traceService, null, clock);
    }

    public DefaultToolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutor, HistoryWriter historyWriter,
            ConversationViewBuilder viewBuilder, BotProperties.TurnProperties turnSettings,
            BotProperties.ToolLoopProperties settings, ModelSelectionService modelSelectionService,
            PlanService planService, RuntimeConfigService runtimeConfigService,
            CompactionOrchestrationService compactionOrchestrationService,
            RuntimeEventService runtimeEventService, TurnProgressService turnProgressService, TraceService traceService,
            ToolFailureRecoveryService toolFailureRecoveryService, Clock clock) {
        this.llmPort = llmPort;
        this.toolExecutor = toolExecutor;
        this.historyWriter = historyWriter;
        this.viewBuilder = viewBuilder;
        this.turnSettings = turnSettings;
        this.settings = settings;
        this.modelSelectionService = modelSelectionService;
        this.planService = planService;
        this.runtimeConfigService = runtimeConfigService;
        this.compactionOrchestrationService = compactionOrchestrationService;
        this.runtimeEventService = runtimeEventService;
        this.turnProgressService = turnProgressService;
        this.traceService = traceService;
        this.toolFailureRecoveryService = toolFailureRecoveryService;
        this.clock = clock;
    }

    @Override
    public ToolLoopTurnResult processTurn(AgentContext context) {
        ensureMessageLists(context);
        emitRuntimeEvent(context, RuntimeEventType.TURN_STARTED, eventPayload());
        RuntimeConfig.TracingConfig tracingConfig = TraceRuntimeConfigSupport.resolve(runtimeConfigService);

        int llmCalls = 0;
        int toolExecutions = 0;
        int emptyFinalResponseRetries = 0;
        int retryAttempt = 0;
        String lastRetryCode = "";
        int maxRetries = resolveAutoRetryMaxAttempts();
        long retryBaseDelayMs = resolveAutoRetryBaseDelayMs();
        boolean retryEnabled = isAutoRetryEnabled();
        List<Attachment> accumulatedAttachments = new ArrayList<>();
        List<Map<String, Object>> turnFileChanges = new ArrayList<>();
        Map<String, Integer> toolFailureCounts = new LinkedHashMap<>();
        Map<String, Integer> toolRecoveryCounts = new LinkedHashMap<>();

        int maxLlmCalls = resolveMaxLlmCalls();
        int maxToolExecutions = resolveMaxToolExecutions();
        java.time.Duration turnDeadline = resolveTurnDeadline();
        boolean stopOnToolFailure = settings != null && settings.isStopOnToolFailure();
        boolean stopOnConfirmationDenied = settings == null || settings.isStopOnConfirmationDenied();
        boolean stopOnToolPolicyDenied = settings != null && settings.isStopOnToolPolicyDenied();

        Instant deadline = clock.instant().plus(turnDeadline);

        while (llmCalls < maxLlmCalls && toolExecutions < maxToolExecutions && clock.instant().isBefore(deadline)) {
            llmCalls++;
            emitRuntimeEvent(context, RuntimeEventType.LLM_STARTED, eventPayload("attempt", llmCalls));
            LlmResponse response;
            try {
                response = executeLlmCall(context, llmCalls, tracingConfig);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                try {
                    if (isInterruptRequested(context)) {
                        clearInterruptFlag(context);
                        applyAttachments(context, accumulatedAttachments);
                        emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                                eventPayload("reason", "user_interrupt", "llmCalls", llmCalls,
                                        "toolExecutions", toolExecutions));
                        return stopTurn(context, context.getAttribute(ContextAttributes.LLM_RESPONSE), null,
                                "interrupted by user", llmCalls, toolExecutions);
                    }

                    emitRuntimeEvent(context, RuntimeEventType.LLM_FINISHED,
                            eventPayload("attempt", llmCalls, "success", false, "code", "llm.interrupted"));
                    emitRuntimeEvent(context, RuntimeEventType.TURN_FAILED,
                            eventPayload("reason", "llm_error", "code", "llm.interrupted"));
                    return failLlmInvocation(context, new RuntimeException("LLM chat interrupted", e), llmCalls,
                            toolExecutions);
                } finally {
                    clearThreadInterruptFlag();
                }
            } catch (ExecutionException e) {
                RuntimeException llmFailure = toRuntimeException(e);
                String code = LlmErrorClassifier.classifyFromThrowable(llmFailure);
                if (LlmErrorClassifier.isContextOverflowCode(code)
                        && tryRecoverFromContextOverflow(context, llmCalls, retryAttempt)) {
                    retryAttempt++;
                    continue;
                }

                if (retryEnabled && LlmErrorClassifier.isTransientCode(code) && retryAttempt < maxRetries) {
                    retryAttempt++;
                    lastRetryCode = code;
                    scheduleRetry(context, llmCalls, retryAttempt, maxRetries, retryBaseDelayMs, code);
                    continue;
                }

                emitRuntimeEvent(context, RuntimeEventType.LLM_FINISHED,
                        eventPayload("attempt", llmCalls, "success", false, "code", code));
                emitRuntimeEvent(context, RuntimeEventType.TURN_FAILED,
                        eventPayload("reason", "llm_error", "code", code));
                return failLlmInvocation(context, llmFailure, llmCalls, toolExecutions);
            } catch (RuntimeException e) {
                String code = LlmErrorClassifier.classifyFromThrowable(e);
                if (LlmErrorClassifier.isContextOverflowCode(code)
                        && tryRecoverFromContextOverflow(context, llmCalls, retryAttempt)) {
                    retryAttempt++;
                    lastRetryCode = code;
                    continue;
                }

                if (retryEnabled && LlmErrorClassifier.isTransientCode(code) && retryAttempt < maxRetries) {
                    retryAttempt++;
                    lastRetryCode = code;
                    scheduleRetry(context, llmCalls, retryAttempt, maxRetries, retryBaseDelayMs, code);
                    continue;
                }

                emitRuntimeEvent(context, RuntimeEventType.LLM_FINISHED,
                        eventPayload("attempt", llmCalls, "success", false, "code", code));
                emitRuntimeEvent(context, RuntimeEventType.TURN_FAILED,
                        eventPayload("reason", "llm_error", "code", code));
                return failLlmInvocation(context, e, llmCalls, toolExecutions);
            }

            if (retryAttempt > 0) {
                emitRuntimeEvent(context, RuntimeEventType.RETRY_FINISHED,
                        eventPayload("attempt", retryAttempt, "success", true));
                logRetrySucceeded(context, llmCalls, retryAttempt, maxRetries, lastRetryCode);
                retryAttempt = 0;
                lastRetryCode = "";
            }

            context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
            maybePublishAttachmentFallback(context, response);
            boolean compatFlatteningUsed = response != null && response.isCompatibilityFlatteningApplied();
            context.setAttribute(ContextAttributes.LLM_COMPAT_FLATTEN_FALLBACK_USED, compatFlatteningUsed);
            if (compatFlatteningUsed) {
                log.info("[ToolLoop] Compatibility fallback applied: flattened tool history for LLM request");
            }
            emitRuntimeEvent(context, RuntimeEventType.LLM_FINISHED,
                    eventPayload("attempt", llmCalls, "success", true,
                            "hasToolCalls", response != null && response.hasToolCalls()));

            boolean hasToolCalls = response != null && response.hasToolCalls();
            if (!hasToolCalls) {
                String emptyReasonCode = getEmptyFinalResponseCode(response, context);
                if (emptyReasonCode != null) {
                    if (emptyFinalResponseRetries < EMPTY_FINAL_RESPONSE_MAX_RETRIES) {
                        emptyFinalResponseRetries++;
                        logEmptyFinalResponseRetry(context, response, emptyReasonCode, emptyFinalResponseRetries);
                        continue;
                    }
                    emitRuntimeEvent(context, RuntimeEventType.TURN_FAILED,
                            eventPayload("reason", "empty_final_response", "code", emptyReasonCode));
                    return failEmptyFinalResponse(context, response, emptyReasonCode, llmCalls, toolExecutions);
                }

                if (response != null) {
                    historyWriter.appendFinalAssistantAnswer(context, response, response.getContent());
                }

                context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, true);
                flushProgress(context, "final_answer");
                applyAttachments(context, accumulatedAttachments);
                emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                        eventPayload("llmCalls", llmCalls, "toolExecutions", toolExecutions));
                clearProgress(context);
                return new ToolLoopTurnResult(context, true, llmCalls, toolExecutions);
            }

            maybePublishIntent(context, response);
            historyWriter.appendAssistantToolCalls(context, response, response.getToolCalls());

            for (Message.ToolCall toolCall : response.getToolCalls()) {
                if (isInterruptRequested(context)) {
                    clearInterruptFlag(context);
                    applyAttachments(context, accumulatedAttachments);
                    emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                            eventPayload("reason", "user_interrupt", "llmCalls", llmCalls,
                                    "toolExecutions", toolExecutions));
                    return stopTurn(context, context.getAttribute(ContextAttributes.LLM_RESPONSE),
                            response.getToolCalls(), "interrupted by user", llmCalls, toolExecutions);
                }

                if (me.golemcore.bot.tools.PlanSetContentTool.TOOL_NAME.equals(toolCall.getName())) {
                    context.setAttribute(ContextAttributes.PLAN_SET_CONTENT_REQUESTED, true);

                    String markdown = null;
                    if (toolCall.getArguments() != null
                            && toolCall.getArguments().get("plan_markdown") instanceof String) {
                        markdown = (String) toolCall.getArguments().get("plan_markdown");
                    }
                    if (markdown == null || markdown.isBlank()) {
                        markdown = "[Plan draft received]";
                    }

                    ToolExecutionOutcome synthetic = new ToolExecutionOutcome(
                            toolCall.getId(),
                            toolCall.getName(),
                            me.golemcore.bot.domain.model.ToolResult.success(markdown),
                            markdown,
                            true,
                            null);
                    historyWriter.appendToolResult(context, synthetic);
                    context.addToolResult(toolCall.getId(), me.golemcore.bot.domain.model.ToolResult.success(markdown));
                    recordToolProgress(context, toolCall, synthetic, 0L);
                    toolExecutions++;
                    continue;
                }

                emitRuntimeEvent(context, RuntimeEventType.TOOL_STARTED,
                        eventPayload("toolCallId", toolCall.getId(), "tool", toolCall.getName()));
                Instant toolStarted = clock.instant();
                ToolExecutionOutcome outcome = executeToolCall(context, toolCall, tracingConfig);
                toolExecutions++;
                long toolDuration = java.time.Duration.between(toolStarted, clock.instant()).toMillis();
                emitRuntimeEvent(context, RuntimeEventType.TOOL_FINISHED,
                        eventPayload("toolCallId", toolCall.getId(), "tool", toolCall.getName(),
                                "success", outcome != null && outcome.toolResult() != null
                                        && outcome.toolResult().isSuccess(),
                                "durationMs", toolDuration));
                captureFileChanges(toolCall, turnFileChanges);
                if (!turnFileChanges.isEmpty()) {
                    context.setAttribute(ContextAttributes.TURN_FILE_CHANGES, new ArrayList<>(turnFileChanges));
                }

                if (outcome != null && outcome.attachment() != null) {
                    accumulatedAttachments.add(outcome.attachment());
                }

                if (outcome != null && outcome.toolResult() != null) {
                    context.addToolResult(outcome.toolCallId(), outcome.toolResult());
                }
                if (outcome != null) {
                    recordToolProgress(context, toolCall, outcome, toolDuration);
                    historyWriter.appendToolResult(context, outcome);
                    if (outcome.toolResult() != null && !outcome.toolResult().isSuccess()) {
                        ToolFailureKind kind = outcome.toolResult().getFailureKind();

                        if (stopOnConfirmationDenied && kind == ToolFailureKind.CONFIRMATION_DENIED) {
                            applyAttachments(context, accumulatedAttachments);
                            emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                                    eventPayload("reason", "confirmation_denied"));
                            return stopTurn(context, context.getAttribute(ContextAttributes.LLM_RESPONSE),
                                    response.getToolCalls(), "confirmation denied", llmCalls, toolExecutions);
                        }
                        if (stopOnToolPolicyDenied && kind == ToolFailureKind.POLICY_DENIED) {
                            applyAttachments(context, accumulatedAttachments);
                            emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                                    eventPayload("reason", "tool_policy_denied"));
                            return stopTurn(context, context.getAttribute(ContextAttributes.LLM_RESPONSE),
                                    response.getToolCalls(), "tool denied by policy", llmCalls, toolExecutions);
                        }
                    }
                    if (outcome.toolResult() != null && !outcome.toolResult().isSuccess()) {
                        ToolFailureRecoveryDecision recoveryDecision = handleRepeatedToolFailure(
                                context,
                                toolCall,
                                outcome,
                                toolFailureCounts,
                                toolRecoveryCounts);
                        if (recoveryDecision != null && recoveryDecision.shouldInjectRecoveryHint()) {
                            flushProgress(context, "tool_recovery");
                            historyWriter.appendInternalRecoveryHint(context, recoveryDecision.recoveryHint());
                            emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                                    eventPayload("reason", "tool_recovery", "tool", outcome.toolName(),
                                            "recoverability", recoveryDecision.recoverability().name(),
                                            "fingerprint", recoveryDecision.fingerprint()));
                            break;
                        }
                        if (recoveryDecision != null && recoveryDecision.shouldStop()) {
                            applyAttachments(context, accumulatedAttachments);
                            emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                                    eventPayload("reason", "repeated_tool_failure", "tool", outcome.toolName()));
                            return stopTurn(context, context.getAttribute(ContextAttributes.LLM_RESPONSE),
                                    response.getToolCalls(),
                                    "repeated tool failure (" + outcome.toolName() + ")", llmCalls,
                                    toolExecutions);
                        }
                    }
                    if (stopOnToolFailure && outcome.toolResult() != null && !outcome.toolResult().isSuccess()) {
                        applyAttachments(context, accumulatedAttachments);
                        emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                                eventPayload("reason", "tool_failure", "tool", outcome.toolName()));
                        return stopTurn(context, context.getAttribute(ContextAttributes.LLM_RESPONSE),
                                response.getToolCalls(),
                                "tool failure (" + outcome.toolName() + ")", llmCalls, toolExecutions);
                    }
                }
            }
        }

        TurnLimitReason stopReason = buildStopReason(llmCalls, maxLlmCalls, toolExecutions, maxToolExecutions,
                deadline);

        LlmResponse lastResponse = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        List<Message.ToolCall> pendingToolCalls = lastResponse != null ? lastResponse.getToolCalls() : null;
        applyAttachments(context, accumulatedAttachments);
        context.setAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REACHED, true);
        context.setAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REASON, stopReason);
        emitRuntimeEvent(context, RuntimeEventType.TURN_FINISHED,
                eventPayload("reason", "limit", "limit", stopReason.name()));
        return stopTurn(context, lastResponse, pendingToolCalls,
                buildStopReasonMessage(stopReason, maxLlmCalls, maxToolExecutions), llmCalls, toolExecutions);
    }

    private boolean isInterruptRequested(AgentContext context) {
        Object contextFlag = context.getAttribute(ContextAttributes.TURN_INTERRUPT_REQUESTED);
        if (Boolean.TRUE.equals(contextFlag)) {
            return true;
        }
        Map<String, Object> metadata = context.getSession() != null ? context.getSession().getMetadata() : null;
        return metadata != null && Boolean.TRUE.equals(metadata.get(ContextAttributes.TURN_INTERRUPT_REQUESTED));
    }

    private void clearThreadInterruptFlag() {
        Thread.interrupted();
    }

    private void clearInterruptFlag(AgentContext context) {
        context.setAttribute(ContextAttributes.TURN_INTERRUPT_REQUESTED, false);
        if (context.getSession() != null && context.getSession().getMetadata() != null) {
            context.getSession().getMetadata().put(ContextAttributes.TURN_INTERRUPT_REQUESTED, false);
        }
    }

    private int resolveAutoRetryMaxAttempts() {
        if (runtimeConfigService == null) {
            return 0;
        }
        return Math.max(0, runtimeConfigService.getTurnAutoRetryMaxAttempts());
    }

    private long resolveAutoRetryBaseDelayMs() {
        if (runtimeConfigService == null) {
            return 500L;
        }
        return Math.max(1L, runtimeConfigService.getTurnAutoRetryBaseDelayMs());
    }

    private boolean isAutoRetryEnabled() {
        if (runtimeConfigService == null) {
            return false;
        }
        return runtimeConfigService.isTurnAutoRetryEnabled();
    }

    private void scheduleRetry(AgentContext context, int llmCalls, int attempt, int maxAttempts, long baseDelayMs,
            String code) {
        flushProgress(context, "retry");
        long delayMs = (long) Math.min(3000, baseDelayMs * Math.pow(2, Math.max(0, attempt - 1)));
        String model = context.getAttribute(ContextAttributes.LLM_MODEL);
        log.warn(
                "[ToolLoop] Transient LLM failure, scheduling retry (code={}, retry={}/{}, delayMs={}, llmCall={}, model={})",
                code,
                attempt,
                maxAttempts,
                delayMs,
                llmCalls,
                model != null ? model : "unknown");
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

    private void logRetrySucceeded(AgentContext context, int llmCalls, int retryAttempt, int maxRetries, String code) {
        String model = context.getAttribute(ContextAttributes.LLM_MODEL);
        log.info("[ToolLoop] LLM retry succeeded (code={}, retry={}/{}, llmCall={}, model={})",
                code != null && !code.isBlank() ? code : "unknown",
                retryAttempt,
                maxRetries,
                llmCalls,
                model != null ? model : "unknown");
    }

    private boolean tryRecoverFromContextOverflow(AgentContext context, int llmCalls, int retryAttempt) {
        if (compactionOrchestrationService == null || context.getSession() == null
                || context.getSession().getMessages() == null
                || context.getSession().getMessages().isEmpty()) {
            return false;
        }

        if (retryAttempt > 0) {
            return false;
        }

        int keepLast = runtimeConfigService != null ? runtimeConfigService.getCompactionKeepLastMessages() : 20;
        List<Message> sessionMessages = context.getSession().getMessages();
        int total = sessionMessages.size();
        if (total <= keepLast) {
            return false;
        }

        flushProgress(context, "compaction");
        emitRuntimeEvent(context, RuntimeEventType.COMPACTION_STARTED,
                eventPayload("llmCall", llmCalls,
                        "messages", total - keepLast,
                        "keepLast", keepLast,
                        "reason", CompactionReason.CONTEXT_OVERFLOW_RECOVERY.name(),
                        "rawCutIndex", Math.max(0, total - keepLast),
                        "adjustedCutIndex", Math.max(0, total - keepLast),
                        "splitTurnDetected", false,
                        "toCompactCount", Math.max(0, total - keepLast)));

        CompactionResult compactionResult = compactionOrchestrationService.compact(
                context.getSession().getId(),
                CompactionReason.CONTEXT_OVERFLOW_RECOVERY,
                keepLast);

        if (compactionResult.removed() <= 0 || !compactionResult.usedSummary()) {
            return false;
        }

        context.setMessages(new ArrayList<>(context.getSession().getMessages()));
        context.setAttribute(ContextAttributes.LLM_ERROR, null);
        context.setAttribute(ContextAttributes.LLM_ERROR_CODE, null);
        if (compactionResult.details() != null) {
            context.setAttribute(ContextAttributes.COMPACTION_LAST_DETAILS, compactionResult.details());
            context.setAttribute(ContextAttributes.TURN_FILE_CHANGES, compactionResult.details().fileChanges());
        }

        emitRuntimeEvent(context, RuntimeEventType.COMPACTION_FINISHED,
                eventPayload("summaryLength",
                        compactionResult.details() != null ? compactionResult.details().summaryLength() : 0,
                        "removed", compactionResult.removed(),
                        "kept", keepLast,
                        "splitTurnDetected", compactionResult.details() != null
                                && compactionResult.details().splitTurnDetected(),
                        "usedSummary", compactionResult.usedSummary(),
                        "reason", CompactionReason.CONTEXT_OVERFLOW_RECOVERY.name(),
                        "toolCount", compactionResult.details() != null ? compactionResult.details().toolCount() : 0,
                        "readFilesCount", compactionResult.details() != null
                                ? compactionResult.details().readFilesCount()
                                : 0,
                        "modifiedFilesCount", compactionResult.details() != null
                                ? compactionResult.details().modifiedFilesCount()
                                : 0,
                        "durationMs",
                        compactionResult.details() != null ? compactionResult.details().durationMs() : 0));
        return true;
    }

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
        if (!(operationObject instanceof String operation) || !(pathObject instanceof String path) || path.isBlank()) {
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

    private ToolFailureRecoveryDecision handleRepeatedToolFailure(AgentContext context, Message.ToolCall toolCall,
            ToolExecutionOutcome outcome, Map<String, Integer> toolFailureCounts,
            Map<String, Integer> toolRecoveryCounts) {
        if (outcome == null || outcome.toolResult() == null || outcome.toolResult().isSuccess()) {
            return null;
        }
        if (toolFailureRecoveryService == null) {
            String fallbackFingerprint = buildFallbackToolFailureFingerprint(outcome);
            int attempts = toolFailureCounts.merge(fallbackFingerprint, 1, Integer::sum);
            if (attempts < 2) {
                return null;
            }
            context.addFailure(new FailureEvent(
                    FailureSource.TOOL,
                    "DefaultToolLoopSystem",
                    FailureKind.EXCEPTION,
                    "Repeated tool failure: " + fallbackFingerprint,
                    clock.instant()));
            return new ToolFailureRecoveryDecision(true, false, null, fallbackFingerprint, null);
        }

        String fingerprint = toolFailureRecoveryService.buildFingerprint(toolCall, outcome);
        int attempts = toolFailureCounts.merge(fingerprint, 1, Integer::sum);
        if (attempts < 2) {
            return null;
        }

        ToolFailureRecoveryDecision decision = toolFailureRecoveryService.evaluate(toolCall, outcome,
                toolRecoveryCounts);
        context.addFailure(new FailureEvent(
                FailureSource.TOOL,
                "DefaultToolLoopSystem",
                decision.shouldInjectRecoveryHint() ? FailureKind.VALIDATION : FailureKind.EXCEPTION,
                (decision.shouldInjectRecoveryHint() ? "Tool recovery requested: " : "Repeated tool failure: ")
                        + fingerprint,
                clock.instant()));
        return decision;
    }

    private String buildFallbackToolFailureFingerprint(ToolExecutionOutcome outcome) {
        String toolName = outcome.toolName() != null ? outcome.toolName() : "unknown";
        String failureKind = outcome.toolResult().getFailureKind() != null
                ? outcome.toolResult().getFailureKind().name()
                : ToolFailureKind.EXECUTION_FAILED.name();
        String error = outcome.toolResult().getError() != null ? outcome.toolResult().getError()
                : outcome.messageContent();
        String normalized = error != null ? error.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
                : "unknown";
        return toolName + ":" + failureKind + ":" + normalized;
    }

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

    private ToolLoopTurnResult failEmptyFinalResponse(AgentContext context, LlmResponse response, String reasonCode,
            int llmCalls, int toolExecutions) {
        String model = context.getAttribute(ContextAttributes.LLM_MODEL);
        String finishReason = response != null ? response.getFinishReason() : null;
        String diagnostic = LlmErrorClassifier.withCode(reasonCode, String.format(
                "LLM returned empty final response after %d attempt(s) (model=%s, finishReason=%s)",
                llmCalls,
                model != null ? model : "unknown",
                finishReason != null ? finishReason : "unknown"));

        log.error("[ToolLoop] {}", diagnostic);
        context.setAttribute(ContextAttributes.LLM_ERROR, diagnostic);
        context.setAttribute(ContextAttributes.LLM_ERROR_CODE, reasonCode);
        context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, false);
        context.addFailure(new FailureEvent(
                FailureSource.LLM,
                "DefaultToolLoopSystem",
                FailureKind.VALIDATION,
                diagnostic,
                clock.instant()));
        flushProgress(context, "llm_failure");
        clearProgress(context);
        return new ToolLoopTurnResult(context, false, llmCalls, toolExecutions);
    }

    private void logEmptyFinalResponseRetry(AgentContext context, LlmResponse response, String reasonCode,
            int retryAttempt) {
        String model = context.getAttribute(ContextAttributes.LLM_MODEL);
        String finishReason = response != null ? response.getFinishReason() : null;
        String content = response != null ? response.getContent() : null;
        int contentLength = content != null ? content.length() : 0;
        boolean hasToolCalls = response != null && response.hasToolCalls();
        log.warn(
                "[ToolLoop] Empty final LLM response, scheduling retry "
                        + "(code={}, retry={}/{}, model={}, finishReason={}, contentLength={}, hasToolCalls={})",
                reasonCode,
                retryAttempt,
                EMPTY_FINAL_RESPONSE_MAX_RETRIES,
                model != null ? model : "unknown",
                finishReason != null ? finishReason : "unknown",
                contentLength,
                hasToolCalls);
    }

    private ToolLoopTurnResult failLlmInvocation(AgentContext context, Throwable throwable, int llmCalls,
            int toolExecutions) {
        String reasonCode = LlmErrorClassifier.classifyFromThrowable(throwable);
        Throwable rootCause = findRootCause(throwable);
        String model = context.getAttribute(ContextAttributes.LLM_MODEL);
        String errorType = rootCause != null
                ? rootCause.getClass().getName()
                : "unknown";
        String errorMessage = rootCause != null && rootCause.getMessage() != null
                ? rootCause.getMessage()
                : "n/a";
        String diagnostic = LlmErrorClassifier.withCode(reasonCode, String.format(
                "LLM call failed after %d attempt(s) (model=%s, errorType=%s, message=%s)",
                llmCalls,
                model != null ? model : "unknown",
                errorType,
                errorMessage));

        log.error("[ToolLoop] {}", diagnostic, throwable);
        context.setAttribute(ContextAttributes.LLM_ERROR, diagnostic);
        context.setAttribute(ContextAttributes.LLM_ERROR_CODE, reasonCode);
        context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, false);
        context.addFailure(new FailureEvent(
                FailureSource.LLM,
                "DefaultToolLoopSystem",
                FailureKind.EXCEPTION,
                diagnostic,
                clock.instant()));
        flushProgress(context, "llm_failure");
        clearProgress(context);
        return new ToolLoopTurnResult(context, false, llmCalls, toolExecutions);
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

    private int resolveMaxLlmCalls() {
        if (runtimeConfigService != null) {
            return runtimeConfigService.getTurnMaxLlmCalls();
        }
        return turnSettings != null ? turnSettings.getMaxLlmCalls() : 200;
    }

    private int resolveMaxToolExecutions() {
        if (runtimeConfigService != null) {
            return runtimeConfigService.getTurnMaxToolExecutions();
        }
        return turnSettings != null ? turnSettings.getMaxToolExecutions() : 500;
    }

    private java.time.Duration resolveTurnDeadline() {
        if (runtimeConfigService != null) {
            return runtimeConfigService.getTurnDeadline();
        }
        return turnSettings != null ? turnSettings.getDeadline() : java.time.Duration.ofHours(1);
    }

    private ToolLoopTurnResult stopTurn(AgentContext context, LlmResponse lastResponse,
            List<Message.ToolCall> pendingToolCalls, String reason, int llmCalls, int toolExecutions) {
        flushProgress(context, "turn_stop");
        if (pendingToolCalls != null) {
            for (Message.ToolCall toolCall : pendingToolCalls) {
                if (context.getToolResults() != null && context.getToolResults().containsKey(toolCall.getId())) {
                    continue;
                }
                ToolExecutionOutcome synthetic = ToolExecutionOutcome.synthetic(toolCall,
                        ToolFailureKind.EXECUTION_FAILED,
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

    private RuntimeException toRuntimeException(ExecutionException executionException) {
        Throwable cause = executionException.getCause() != null ? executionException.getCause() : executionException;
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(cause.getMessage(), cause);
    }

    private void applyAttachments(AgentContext context, List<Attachment> attachments) {
        if (attachments.isEmpty()) {
            return;
        }
        log.debug("[ToolLoop] Applying {} attachment(s) to OutgoingResponse", attachments.size());

        OutgoingResponse existing = context.getOutgoingResponse();
        OutgoingResponse.OutgoingResponseBuilder builder;
        if (existing != null) {
            builder = OutgoingResponse.builder()
                    .text(existing.getText())
                    .voiceRequested(existing.isVoiceRequested())
                    .voiceText(existing.getVoiceText())
                    .skipAssistantHistory(existing.isSkipAssistantHistory());
            for (Attachment attachment : existing.getAttachments()) {
                builder.attachment(attachment);
            }
        } else {
            builder = OutgoingResponse.builder();
        }

        for (Attachment attachment : attachments) {
            builder.attachment(attachment);
        }

        OutgoingResponse updated = builder.build();
        context.setOutgoingResponse(updated);
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, updated);
    }

    private void ensureMessageLists(AgentContext context) {
        if (context.getMessages() == null) {
            context.setMessages(new ArrayList<>());
        }
        if (context.getSession() != null && context.getSession().getMessages() == null) {
            context.getSession().setMessages(new ArrayList<>());
        }
        if (context.getSession() != null && context.getSession().getMetadata() == null) {
            context.getSession().setMetadata(new LinkedHashMap<>());
        }
    }

    private TurnLimitReason buildStopReason(int llmCalls, int maxLlmCalls, int toolExecutions, int maxToolExecutions,
            Instant deadline) {
        if (llmCalls >= maxLlmCalls) {
            return TurnLimitReason.MAX_LLM_CALLS;
        }
        if (toolExecutions >= maxToolExecutions) {
            return TurnLimitReason.MAX_TOOL_EXECUTIONS;
        }
        if (!clock.instant().isBefore(deadline)) {
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

    private LlmResponse executeLlmCall(AgentContext context, int attempt, RuntimeConfig.TracingConfig tracingConfig)
            throws InterruptedException, ExecutionException {
        ModelSelectionService.ModelSelection selection = selectModel(context.getModelTier());
        Map<String, Object> requestContextAttributes = buildRequestContextAttributes(context, selection, attempt);
        TraceContext llmSpan = startChildSpan(context, "llm.chat", TraceSpanKind.LLM, requestContextAttributes);
        appendRequestContextEvent(context, llmSpan, requestContextAttributes);
        LlmRequest request = buildRequest(context, llmSpan != null ? llmSpan : context.getTraceContext(), selection);
        captureLlmSnapshot(context, llmSpan, tracingConfig, "request", request);
        try (MdcSupport.Scope ignored = MdcSupport.withContext(buildTraceMdcContext(llmSpan, context))) {
            LlmResponse response = llmPort.chat(request).get();
            captureLlmSnapshot(context, llmSpan, tracingConfig, "response", response);
            finishChildSpan(context, llmSpan, TraceStatusCode.OK, null);
            return response;
        } catch (InterruptedException e) {
            finishChildSpan(context, llmSpan, TraceStatusCode.ERROR, e.getMessage());
            throw e;
        } catch (ExecutionException e) {
            finishChildSpan(context, llmSpan, TraceStatusCode.ERROR, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            finishChildSpan(context, llmSpan, TraceStatusCode.ERROR, e.getMessage());
            throw e;
        }
    }

    private ToolExecutionOutcome executeToolCall(AgentContext context, Message.ToolCall toolCall,
            RuntimeConfig.TracingConfig tracingConfig) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (toolCall.getName() != null) {
            attributes.put("tool.name", toolCall.getName());
        }
        if (toolCall.getId() != null) {
            attributes.put("tool.callId", toolCall.getId());
        }
        TraceContext toolSpan = startChildSpan(context, "tool." + toolCall.getName(), TraceSpanKind.TOOL, attributes);
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
        } catch (Exception e) {
            ToolExecutionOutcome synthetic = ToolExecutionOutcome.synthetic(toolCall, ToolFailureKind.EXECUTION_FAILED,
                    "Tool execution failed: " + e.getMessage());
            captureToolSnapshot(context, toolSpan, tracingConfig, "output", synthetic);
            finishChildSpan(context, toolSpan, TraceStatusCode.ERROR, e.getMessage());
            return synthetic;
        }
    }

    private LlmRequest buildRequest(AgentContext context, TraceContext traceContext,
            ModelSelectionService.ModelSelection selection) {
        ConversationView view = viewBuilder.buildView(context, selection.model());
        if (!view.diagnostics().isEmpty()) {
            log.debug("[ToolLoop] conversation view diagnostics: {}", view.diagnostics());
        }

        storeSelectedModel(context, selection.model());
        context.setAttribute(ContextAttributes.LLM_MODEL, selection.model());
        context.setAttribute(ContextAttributes.LLM_REASONING, selection.reasoning());

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
        }
        return attributes;
    }

    private void appendRequestContextEvent(AgentContext context, TraceContext llmSpan, Map<String, Object> attributes) {
        if (traceService == null || context == null || context.getSession() == null || llmSpan == null) {
            return;
        }
        Map<String, Object> eventAttributes = new LinkedHashMap<>();
        copyAttribute(attributes, eventAttributes, "context.skill.name", "skill");
        copyAttribute(attributes, eventAttributes, "context.model.tier", "tier");
        copyAttribute(attributes, eventAttributes, "context.model.id", "model_id");
        copyAttribute(attributes, eventAttributes, "context.model.reasoning", "reasoning");
        copyAttribute(attributes, eventAttributes, "context.model.source", "source");
        traceService.appendEvent(context.getSession(), llmSpan, "request.context", clock.instant(), eventAttributes);
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

    private TraceContext startChildSpan(AgentContext context, String spanName, TraceSpanKind spanKind,
            Map<String, Object> attributes) {
        if (traceService == null || context == null || context.getSession() == null || context.getTraceContext() == null
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

    private ModelSelectionService.ModelSelection selectModel(String tier) {
        if (modelSelectionService == null) {
            return new ModelSelectionService.ModelSelection(null, null);
        }
        return modelSelectionService.resolveForTier(tier);
    }

    private void emitRuntimeEvent(AgentContext context, RuntimeEventType type, Map<String, Object> payload) {
        if (runtimeEventService == null || context == null) {
            return;
        }
        runtimeEventService.emit(context, type, payload);
    }

    private void maybePublishIntent(AgentContext context, LlmResponse response) {
        if (turnProgressService == null || context == null || response == null) {
            return;
        }
        turnProgressService.maybePublishIntent(context, response);
    }

    private void maybePublishAttachmentFallback(AgentContext context, LlmResponse response) {
        if (turnProgressService == null || context == null || response == null
                || response.getProviderMetadata() == null) {
            return;
        }

        Object applied = response.getProviderMetadata().get(LlmProviderMetadataKeys.TOOL_ATTACHMENT_FALLBACK_APPLIED);
        if (!Boolean.TRUE.equals(applied)) {
            return;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "tool_attachment_fallback");
        Object reason = response.getProviderMetadata().get(LlmProviderMetadataKeys.TOOL_ATTACHMENT_FALLBACK_REASON);
        if (reason instanceof String stringReason && !stringReason.isBlank()) {
            metadata.put("reason", stringReason);
        }

        turnProgressService.publishSummary(
                context,
                "Request was too large for inline tool images, so I retried without them.",
                metadata);
    }

    private void recordToolProgress(AgentContext context, Message.ToolCall toolCall, ToolExecutionOutcome outcome,
            long durationMs) {
        if (turnProgressService == null || context == null || toolCall == null || outcome == null) {
            return;
        }
        turnProgressService.recordToolExecution(context, toolCall, outcome, durationMs);
    }

    private void flushProgress(AgentContext context, String reason) {
        if (turnProgressService == null || context == null) {
            return;
        }
        turnProgressService.flushBufferedTools(context, reason);
    }

    private void clearProgress(AgentContext context) {
        if (turnProgressService == null || context == null) {
            return;
        }
        turnProgressService.clearProgress(context);
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
}
