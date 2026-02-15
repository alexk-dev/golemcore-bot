package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.ToolCallExecutionResult;
import me.golemcore.bot.domain.service.ToolCallExecutionService;

/**
 * Adapter that executes tool calls via {@link ToolCallExecutionService}.
 *
 * <p>
 * This is the primary production adapter after removing the legacy
 * ToolExecutionSystem.
 */
public class ToolCallExecutionServiceToolExecutorAdapter implements ToolExecutorPort {

    private final ToolCallExecutionService toolCallExecutionService;

    public ToolCallExecutionServiceToolExecutorAdapter(ToolCallExecutionService toolCallExecutionService) {
        this.toolCallExecutionService = toolCallExecutionService;
    }

    @Override
    public ToolExecutionOutcome execute(AgentContext context, Message.ToolCall toolCall) {
        ToolCallExecutionResult result = toolCallExecutionService.execute(context, toolCall);
        ToolResult toolResult = result.toolResult();
        return new ToolExecutionOutcome(toolCall.getId(), toolCall.getName(), toolResult, result.toolMessageContent(),
                false, result.extractedAttachment());
    }
}
