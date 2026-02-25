package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.FailureKind;
import me.golemcore.bot.domain.model.FailureSource;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.TurnLimitReason;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import me.golemcore.bot.domain.system.toolloop.view.ConversationView;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.port.outbound.LlmPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.golemcore.bot.infrastructure.config.BotProperties;

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
    private final Clock clock;

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
        this.clock = clock;

    }

    public DefaultToolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutor, HistoryWriter historyWriter,
            ConversationViewBuilder viewBuilder, BotProperties.TurnProperties turnSettings,
            BotProperties.ToolLoopProperties settings, ModelSelectionService modelSelectionService,
            PlanService planService, RuntimeConfigService runtimeConfigService, Clock clock) {
        this.llmPort = llmPort;
        this.toolExecutor = toolExecutor;
        this.historyWriter = historyWriter;
        this.viewBuilder = viewBuilder;
        this.turnSettings = turnSettings;
        this.settings = settings;
        this.modelSelectionService = modelSelectionService;
        this.planService = planService;
        this.runtimeConfigService = runtimeConfigService;
        this.clock = clock;
    }

    @Override
    public ToolLoopTurnResult processTurn(AgentContext context) {
        ensureMessageLists(context);

        int llmCalls = 0;
        int toolExecutions = 0;
        int emptyFinalResponseRetries = 0;
        List<Attachment> accumulatedAttachments = new ArrayList<>();

        int maxLlmCalls = resolveMaxLlmCalls();
        int maxToolExecutions = resolveMaxToolExecutions();
        java.time.Duration turnDeadline = resolveTurnDeadline();
        boolean stopOnToolFailure = settings != null && settings.isStopOnToolFailure();
        boolean stopOnConfirmationDenied = settings == null || settings.isStopOnConfirmationDenied();
        boolean stopOnToolPolicyDenied = settings != null && settings.isStopOnToolPolicyDenied();

        Instant deadline = clock.instant().plus(turnDeadline);

        while (llmCalls < maxLlmCalls && toolExecutions < maxToolExecutions && clock.instant().isBefore(deadline)) {
            // 1) LLM call
            llmCalls++;
            LlmResponse response;
            try {
                response = llmPort.chat(buildRequest(context)).join();
            } catch (RuntimeException e) {
                return failLlmInvocation(context, e, llmCalls, toolExecutions);
            }

            context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
            boolean compatFlatteningUsed = response != null && response.isCompatibilityFlatteningApplied();
            context.setAttribute(ContextAttributes.LLM_COMPAT_FLATTEN_FALLBACK_USED, compatFlatteningUsed);
            if (compatFlatteningUsed) {
                log.info("[ToolLoop] Compatibility fallback applied: flattened tool history for LLM request");
            }

            // 2) Final answer (no tool calls)
            boolean hasToolCalls = response != null && response.hasToolCalls();
            if (!hasToolCalls) {
                String emptyReasonCode = getEmptyFinalResponseCode(response, context);
                if (emptyReasonCode != null) {
                    if (emptyFinalResponseRetries < EMPTY_FINAL_RESPONSE_MAX_RETRIES) {
                        emptyFinalResponseRetries++;
                        log.warn("[ToolLoop] Empty final LLM response (code={}, retry {}/{}), retrying",
                                emptyReasonCode, emptyFinalResponseRetries, EMPTY_FINAL_RESPONSE_MAX_RETRIES);
                        continue;
                    }
                    return failEmptyFinalResponse(context, response, emptyReasonCode, llmCalls, toolExecutions);
                }

                // History append of final answer is currently owned by ResponseRoutingSystem
                // in legacy flow. For ToolLoop flow we append here to preserve raw history.
                if (response != null) {
                    historyWriter.appendFinalAssistantAnswer(context, response, response.getContent());
                }

                context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, true);
                applyAttachments(context, accumulatedAttachments);
                return new ToolLoopTurnResult(context, true, llmCalls, toolExecutions);
            }

            // 3) Append assistant message with tool calls
            historyWriter.appendAssistantToolCalls(context, response, response.getToolCalls());

            // 4) Execute tools and append results
            for (Message.ToolCall tc : response.getToolCalls()) {
                if (me.golemcore.bot.tools.PlanSetContentTool.TOOL_NAME.equals(tc.getName())) {
                    // Control tool: do not execute; let PlanFinalizationSystem handle it.
                    context.setAttribute(ContextAttributes.PLAN_SET_CONTENT_REQUESTED, true);

                    String md = null;
                    if (tc.getArguments() != null && tc.getArguments().get("plan_markdown") instanceof String) {
                        md = (String) tc.getArguments().get("plan_markdown");
                    }
                    if (md == null || md.isBlank()) {
                        md = "[Plan draft received]";
                    }

                    ToolExecutionOutcome synthetic = new ToolExecutionOutcome(
                            tc.getId(), tc.getName(),
                            me.golemcore.bot.domain.model.ToolResult.success(md),
                            md, true, null);
                    historyWriter.appendToolResult(context, synthetic);
                    context.addToolResult(tc.getId(), me.golemcore.bot.domain.model.ToolResult.success(md));
                    toolExecutions++;
                    continue;
                }

                ToolExecutionOutcome outcome;
                try {
                    outcome = toolExecutor.execute(context, tc);
                } catch (Exception e) {
                    outcome = ToolExecutionOutcome.synthetic(tc, ToolFailureKind.EXECUTION_FAILED,
                            "Tool execution failed: " + e.getMessage());
                }
                toolExecutions++;

                if (outcome != null && outcome.attachment() != null) {
                    accumulatedAttachments.add(outcome.attachment());
                }

                if (outcome != null && outcome.toolResult() != null) {
                    context.addToolResult(outcome.toolCallId(), outcome.toolResult());
                }
                if (outcome != null) {
                    historyWriter.appendToolResult(context, outcome);
                    if (outcome.toolResult() != null && !outcome.toolResult().isSuccess()) {
                        ToolFailureKind kind = outcome.toolResult().getFailureKind();

                        if (stopOnConfirmationDenied && kind == ToolFailureKind.CONFIRMATION_DENIED) {
                            applyAttachments(context, accumulatedAttachments);
                            return stopTurn(context, context.getAttribute(ContextAttributes.LLM_RESPONSE),
                                    response.getToolCalls(), "confirmation denied", llmCalls, toolExecutions);
                        }
                        if (stopOnToolPolicyDenied && kind == ToolFailureKind.POLICY_DENIED) {
                            applyAttachments(context, accumulatedAttachments);
                            return stopTurn(context, context.getAttribute(ContextAttributes.LLM_RESPONSE),
                                    response.getToolCalls(), "tool denied by policy", llmCalls, toolExecutions);
                        }
                    }
                    if (stopOnToolFailure && outcome.toolResult() != null && !outcome.toolResult().isSuccess()) {
                        applyAttachments(context, accumulatedAttachments);
                        return stopTurn(context, context.getAttribute(ContextAttributes.LLM_RESPONSE),
                                response.getToolCalls(),
                                "tool failure (" + outcome.toolName() + ")", llmCalls, toolExecutions);
                    }
                }
            }
        }

        TurnLimitReason stopReason = buildStopReason(llmCalls, maxLlmCalls, toolExecutions, maxToolExecutions,
                deadline);

        LlmResponse last = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        List<Message.ToolCall> pending = last != null ? last.getToolCalls() : null;
        applyAttachments(context, accumulatedAttachments);
        context.setAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REACHED, true);
        context.setAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REASON, stopReason);
        return stopTurn(context, last, pending,
                buildStopReasonMessage(stopReason, maxLlmCalls, maxToolExecutions), llmCalls, toolExecutions);

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
        return new ToolLoopTurnResult(context, false, llmCalls, toolExecutions);
    }

    private ToolLoopTurnResult failLlmInvocation(AgentContext context, Throwable throwable, int llmCalls,
            int toolExecutions) {
        String reasonCode = LlmErrorClassifier.classifyFromThrowable(throwable);
        Throwable rootCause = findRootCause(throwable);
        String model = context.getAttribute(ContextAttributes.LLM_MODEL);
        String errorType = rootCause != null && rootCause.getClass() != null
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
        return new ToolLoopTurnResult(context, false, llmCalls, toolExecutions);
    }

    private Throwable findRootCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        Throwable current = throwable;
        int depth = 0;
        while (current.getCause() != null && current.getCause() != current && depth < 32) {
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
        if (pendingToolCalls != null) {
            for (Message.ToolCall tc : pendingToolCalls) {
                // Avoid writing duplicate synthetic results for the same tool_call_id if a real
                // outcome was already recorded.
                if (context.getToolResults() != null && context.getToolResults().containsKey(tc.getId())) {
                    continue;
                }
                ToolExecutionOutcome synthetic = ToolExecutionOutcome.synthetic(tc, ToolFailureKind.EXECUTION_FAILED,
                        "Tool loop stopped: " + reason);
                context.addToolResult(synthetic.toolCallId(), synthetic.toolResult());
                historyWriter.appendToolResult(context, synthetic);
            }
        }

        String stopMessage = "Tool loop stopped: " + reason + ".";

        historyWriter.appendFinalAssistantAnswer(context, lastResponse, stopMessage);

        // Replace LLM_RESPONSE with a clean response (no tool calls) so that
        // OutgoingResponsePreparationSystem can produce an OutgoingResponse.
        LlmResponse cleanResponse = LlmResponse.builder()
                .content(stopMessage)
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, cleanResponse);

        context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, true);
        return new ToolLoopTurnResult(context, true, llmCalls, toolExecutions);
    }

    /**
     * Merge accumulated attachments into the {@link OutgoingResponse} on the
     * context. If an OutgoingResponse already exists, a new instance is built with
     * the attachments appended; otherwise a minimal response carrying only
     * attachments is created.
     */
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
            for (Attachment a : existing.getAttachments()) {
                builder.attachment(a);
            }
        } else {
            builder = OutgoingResponse.builder();
        }

        for (Attachment a : attachments) {
            builder.attachment(a);
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

    private LlmRequest buildRequest(AgentContext context) {
        ModelSelectionService.ModelSelection selection = selectModel(context.getModelTier());

        ConversationView view = viewBuilder.buildView(context, selection.model());
        if (!view.diagnostics().isEmpty()) {
            log.debug("[ToolLoop] conversation view diagnostics: {}", view.diagnostics());
        }

        // Track current model for next request (persisted in session metadata + context
        // attributes)
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
                .build();
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
}
