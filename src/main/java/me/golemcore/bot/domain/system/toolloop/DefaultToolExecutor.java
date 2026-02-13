package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.ToolCallExecutionResult;
import me.golemcore.bot.domain.service.ToolCallExecutionService;

/**
 * Default ToolExecutorPort implementation based on
 * {@link ToolCallExecutionService}.
 *
 * <p>
 * This adapter exists so ToolLoopSystem can depend on a stable port, while the
 * actual execution logic stays in a pure domain service.
 */
public class DefaultToolExecutor implements ToolExecutorPort {

    private final ToolCallExecutionService toolCallExecutionService;

    public DefaultToolExecutor(ToolCallExecutionService toolCallExecutionService) {
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
