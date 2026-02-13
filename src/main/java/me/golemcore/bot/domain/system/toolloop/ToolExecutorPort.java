package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Message;

/**
 * Hexagonal outbound port for executing a single tool call.
 *
 * <p>
 * ToolLoopSystem is the owner of the loop; it invokes this port for each tool
 * call.
 */
public interface ToolExecutorPort {

    ToolExecutionOutcome execute(AgentContext context, Message.ToolCall toolCall);
}
