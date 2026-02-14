package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.system.toolloop.view.ConversationView;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.port.outbound.LlmPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
    private final BotProperties.ToolLoopProperties settings;
    private final BotProperties.ModelRouterProperties router;
    private final Clock clock;

    public DefaultToolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutor, HistoryWriter historyWriter,
            ConversationViewBuilder viewBuilder, BotProperties.ToolLoopProperties settings,
            BotProperties.ModelRouterProperties router) {
        this(llmPort, toolExecutor, historyWriter, viewBuilder, settings, router, Clock.systemUTC());
    }

    // Visible for testing
    public DefaultToolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutor, HistoryWriter historyWriter,
            ConversationViewBuilder viewBuilder, BotProperties.ToolLoopProperties settings,
            BotProperties.ModelRouterProperties router, Clock clock) {
        this.llmPort = llmPort;
        this.toolExecutor = toolExecutor;
        this.historyWriter = historyWriter;
        this.viewBuilder = viewBuilder;
        this.settings = settings;
        this.router = router;
        this.clock = clock;
    }

    @Override
    public ToolLoopTurnResult processTurn(AgentContext context) {
        ensureMessageLists(context);

        int llmCalls = 0;
        int toolExecutions = 0;

        int maxLlmCalls = settings != null ? settings.getMaxLlmCalls() : 6;
        int maxToolExecutions = settings != null ? settings.getMaxToolExecutions() : 50;
        long deadlineMs = settings != null ? settings.getDeadlineMs() : 30000L;
        boolean stopOnToolFailure = settings != null && settings.isStopOnToolFailure();
        boolean stopOnConfirmationDenied = settings == null || settings.isStopOnConfirmationDenied();
        boolean stopOnToolPolicyDenied = settings != null && settings.isStopOnToolPolicyDenied();

        Instant deadline = clock.instant().plusMillis(deadlineMs);

        while (llmCalls < maxLlmCalls && toolExecutions < maxToolExecutions && clock.instant().isBefore(deadline)) {
            // 1) LLM call
            LlmResponse response = llmPort.chat(buildRequest(context)).join();
            llmCalls++;
            context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

            // 2) Final answer (no tool calls)
            if (response == null || !response.hasToolCalls()) {
                // History append of final answer is currently owned by ResponseRoutingSystem
                // in legacy flow. For ToolLoop flow we append here to preserve raw history.
                if (response != null) {
                    historyWriter.appendFinalAssistantAnswer(context, response, response.getContent());
                }

                context.setAttribute(ContextAttributes.LOOP_COMPLETE, true);
                context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, true);
                return new ToolLoopTurnResult(context, true, llmCalls, toolExecutions);
            }

            // 3) Append assistant message with tool calls
            historyWriter.appendAssistantToolCalls(context, response, response.getToolCalls());

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

                if (outcome != null && outcome.toolResult() != null) {
                    context.addToolResult(outcome.toolCallId(), outcome.toolResult());
                }
                if (outcome != null) {
                    historyWriter.appendToolResult(context, outcome);
                    if (outcome.toolResult() != null && !outcome.toolResult().isSuccess()) {
                        ToolFailureKind kind = outcome.toolResult().getFailureKind();

                        if (stopOnConfirmationDenied && kind == ToolFailureKind.CONFIRMATION_DENIED) {
                            return stopTurn(context, context.getAttribute(ContextAttributes.LLM_RESPONSE),
                                    response.getToolCalls(), "confirmation denied", llmCalls, toolExecutions);
                        }
                        if (stopOnToolPolicyDenied && kind == ToolFailureKind.POLICY_DENIED) {
                            return stopTurn(context, context.getAttribute(ContextAttributes.LLM_RESPONSE),
                                    response.getToolCalls(), "tool denied by policy", llmCalls, toolExecutions);
                        }
                    }
                    if (stopOnToolFailure && outcome.toolResult() != null && !outcome.toolResult().isSuccess()) {
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
        return stopTurn(context, last, pending, stopReason, llmCalls, toolExecutions);

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

        historyWriter.appendFinalAssistantAnswer(
                context,
                lastResponse,
                "Tool loop stopped: " + reason + ".");

        context.setAttribute(ContextAttributes.LOOP_COMPLETE, true);
        context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, true);
        return new ToolLoopTurnResult(context, true, llmCalls, toolExecutions);
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
        ModelSelection selection = selectModel(context.getModelTier());

        ConversationView view = viewBuilder.buildView(context, selection.model);
        if (!view.diagnostics().isEmpty()) {
            log.debug("[ToolLoop] conversation view diagnostics: {}", view.diagnostics());
        }

        // Track current model for next request (persisted in session metadata)
        storeSelectedModel(context, selection.model);

        return LlmRequest.builder()
                .model(selection.model)
                .reasoningEffort(selection.reasoning)
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
        java.util.Map<String, Object> metadata = context.getSession().getMetadata();
        if (metadata == null) {
            return;
        }
        metadata.put(ContextAttributes.LLM_MODEL, model);
    }

    private ModelSelection selectModel(String tier) {
        // Keep the same tier contract as legacy LlmExecutionSystem.
        // If router is missing (tests / minimal wiring), fall back to provider default.
        if (router == null) {
            return new ModelSelection(null, null);
        }

        return switch (tier != null ? tier : "balanced") {
        case "deep" -> new ModelSelection(router.getDeepModel(), router.getDeepModelReasoning());
        case "coding" -> new ModelSelection(router.getCodingModel(), router.getCodingModelReasoning());
        case "smart" -> new ModelSelection(router.getSmartModel(), router.getSmartModelReasoning());
        default -> new ModelSelection(router.getBalancedModel(), router.getBalancedModelReasoning());
        };
    }

    private record ModelSelection(String model, String reasoning) {
    }
}
