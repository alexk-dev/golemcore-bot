package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
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

    private final LlmPort llmPort;
    private final ToolExecutorPort toolExecutor;
    private final HistoryWriter historyWriter;
    private final ConversationViewBuilder viewBuilder;
    private final BotProperties.TurnProperties turnSettings;
    private final BotProperties.ToolLoopProperties settings;
    private final ModelSelectionService modelSelectionService;
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
            LlmResponse response = llmPort.chat(buildRequest(context)).join();
            llmCalls++;
            context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
            boolean compatFlatteningUsed = response != null && response.isCompatibilityFlatteningApplied();
            context.setAttribute(ContextAttributes.LLM_COMPAT_FLATTEN_FALLBACK_USED, compatFlatteningUsed);
            if (compatFlatteningUsed) {
                log.info("[ToolLoop] Compatibility fallback applied: flattened tool history for LLM request");
            }

            // 2) Final answer (no tool calls)
            if (response == null || !response.hasToolCalls()) {
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

            // 3.5) Plan mode: record tool calls as plan steps, write synthetic results
            if (planService != null && planService.isPlanModeActive()) {
                String planId = planService.getActivePlanId();
                for (Message.ToolCall tc : response.getToolCalls()) {
                    Map<String, Object> args = tc.getArguments() != null ? tc.getArguments() : Map.of();
                    String description = tc.getName() + "(" + args + ")";
                    planService.addStep(planId, tc.getName(), args, description);

                    ToolExecutionOutcome synthetic = new ToolExecutionOutcome(
                            tc.getId(), tc.getName(),
                            me.golemcore.bot.domain.model.ToolResult.success("[Planned]"),
                            "[Planned]", true, null);
                    historyWriter.appendToolResult(context, synthetic);
                    toolExecutions++;
                }
                continue;
            }

            // 4) Execute tools and append results
            for (Message.ToolCall tc : response.getToolCalls()) {
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

        String stopReason = buildStopReason(llmCalls, maxLlmCalls, toolExecutions, maxToolExecutions, deadline);

        LlmResponse last = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        List<Message.ToolCall> pending = last != null ? last.getToolCalls() : null;
        applyAttachments(context, accumulatedAttachments);
        context.setAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REACHED, true);
        return stopTurn(context, last, pending, stopReason, llmCalls, toolExecutions);

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

    private String buildStopReason(int llmCalls, int maxLlmCalls, int toolExecutions, int maxToolExecutions,
            Instant deadline) {
        if (llmCalls >= maxLlmCalls) {
            return "reached max internal LLM calls (" + maxLlmCalls + ")";
        }
        if (toolExecutions >= maxToolExecutions) {
            return "reached max tool executions (" + maxToolExecutions + ")";
        }
        if (!clock.instant().isBefore(deadline)) {
            return "deadline exceeded";
        }
        return "stopped by guard";
    }

    private LlmRequest buildRequest(AgentContext context) {
        ModelSelectionService.ModelSelection selection = selectModel(context.getModelTier());

        ConversationView view = viewBuilder.buildView(context, selection.model());
        if (!view.diagnostics().isEmpty()) {
            log.debug("[ToolLoop] conversation view diagnostics: {}", view.diagnostics());
        }

        // Track current model for next request (persisted in session metadata)
        storeSelectedModel(context, selection.model());

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
