package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.LlmPort;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool loop orchestrator (single-turn internal loop).
 *
 * <p>
 * Scenario A contract: 1) LLM returns tool calls, 2) tools executed, 3) LLM
 * returns final answer — all inside a single {@link #processTurn} call.
 */
public class DefaultToolLoopSystem implements ToolLoopSystem {

    public static final String FINAL_ANSWER_READY = "llm.final.ready";

    private final LlmPort llmPort;
    private final ToolExecutorPort toolExecutor;
    private final HistoryWriter historyWriter;

    public DefaultToolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutor, HistoryWriter historyWriter) {
        this.llmPort = llmPort;
        this.toolExecutor = toolExecutor;
        this.historyWriter = historyWriter;
    }

    @Override
    public ToolLoopTurnResult processTurn(AgentContext context) {
        ensureMessageLists(context);

        int llmCalls = 0;
        int toolExecutions = 0;
        int maxLlmCalls = 6; // TODO: make configurable (tool-loop specific)

        while (llmCalls < maxLlmCalls) {
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
                context.setAttribute(FINAL_ANSWER_READY, true);
                return new ToolLoopTurnResult(context, true, llmCalls, toolExecutions);
            }

            // 3) Append assistant message with tool calls
            historyWriter.appendAssistantToolCalls(context, response, response.getToolCalls());

            // 4) Execute tools and append results
            for (Message.ToolCall tc : response.getToolCalls()) {
                ToolExecutionOutcome outcome = toolExecutor.execute(context, tc);
                toolExecutions++;

                if (outcome != null && outcome.toolResult() != null) {
                    context.addToolResult(outcome.toolCallId(), outcome.toolResult());
                }
                historyWriter.appendToolResult(context, outcome);
            }
        }

        // Stop: max LLM calls reached (temporary behavior).
        context.setAttribute(ContextAttributes.LOOP_COMPLETE, true);
        context.setAttribute(FINAL_ANSWER_READY, true);
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

    private LlmRequest buildRequest(AgentContext context) {
        List<Message> view = new ArrayList<>(context.getMessages());
        return LlmRequest.builder()
                .model(null)
                .systemPrompt(context.getSystemPrompt())
                .messages(view)
                .tools(context.getAvailableTools())
                .toolResults(context.getToolResults())
                .sessionId(context.getSession() != null ? context.getSession().getId() : null)
                .build();
    }
}
