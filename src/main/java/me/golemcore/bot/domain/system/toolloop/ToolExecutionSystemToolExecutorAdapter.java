package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.ToolCallExecutionResult;
import me.golemcore.bot.domain.service.ToolCallExecutionService;

/**
 * Adapter that allows using the existing ToolExecutionSystem wiring through the
 * ToolExecutorPort, while keeping ToolLoopSystem independent from
 * ToolExecutionSystem.
 */
public class ToolExecutionSystemToolExecutorAdapter implements ToolExecutorPort {

    private final ToolCallExecutionService toolCallExecutionService;

    public ToolExecutionSystemToolExecutorAdapter(ToolCallExecutionService toolCallExecutionService) {
        this.toolCallExecutionService = toolCallExecutionService;
    }

    @Override
    public ToolExecutionOutcome execute(AgentContext context, Message.ToolCall toolCall) {
        ToolCallExecutionResult result = toolCallExecutionService.execute(context, toolCall);
        return new ToolExecutionOutcome(
                result.toolCallId(),
                result.toolName(),
                result.toolResult(),
                result.toolMessageContent(),
                false);
    }
}
