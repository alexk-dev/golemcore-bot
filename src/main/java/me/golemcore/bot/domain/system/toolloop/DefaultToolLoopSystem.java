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

        // 1) LLM call
        LlmResponse first = llmPort.chat(buildRequest(context)).join();
        llmCalls++;
        context.setAttribute(ContextAttributes.LLM_RESPONSE, first);

        if (first != null && first.hasToolCalls()) {
            // 2) Append assistant message with tool calls
            historyWriter.appendAssistantToolCalls(context, first, first.getToolCalls());

            // 3) Execute tools and append results
            for (Message.ToolCall tc : first.getToolCalls()) {
                ToolExecutionOutcome outcome = toolExecutor.execute(context, tc);
                toolExecutions++;

                if (outcome != null && outcome.toolResult() != null) {
                    context.addToolResult(outcome.toolCallId(), outcome.toolResult());
                }
                historyWriter.appendToolResult(context, outcome);
            }

            // 4) Second LLM call
            LlmResponse second = llmPort.chat(buildRequest(context)).join();
            llmCalls++;
            context.setAttribute(ContextAttributes.LLM_RESPONSE, second);

            // 5) Append final answer
            historyWriter.appendFinalAssistantAnswer(context, second, second != null ? second.getContent() : null);
        } else {
            // No tool calls -> treat as final. (History append is owned by
            // ResponseRoutingSystem today.)
        }

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
