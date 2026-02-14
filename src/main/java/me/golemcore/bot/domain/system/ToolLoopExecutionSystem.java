package me.golemcore.bot.domain.system;

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.system.toolloop.ToolLoopSystem;
import org.springframework.stereotype.Component;

/**
 * Pipeline system that runs ToolLoopSystem (single-turn internal loop).
 *
 * <p>
 * Intended to replace the legacy pair: {@link LlmExecutionSystem} +
 * {@link ToolExecutionSystem} by producing a final
 * {@link ContextAttributes#LLM_RESPONSE} in a single pipeline pass.
 *
 * <p>
 * Order:
 * <ul>
 * <li>After ContextBuildingSystem (20) and DynamicTierSystem (25)
 * <li>Before ResponseRoutingSystem (60)
 * </ul>
 */
@Component
@Slf4j
public class ToolLoopExecutionSystem implements AgentSystem {

    private final ToolLoopSystem toolLoopSystem;

    public ToolLoopExecutionSystem(ToolLoopSystem toolLoopSystem) {
        this.toolLoopSystem = toolLoopSystem;
    }

    @Override
    public String getName() {
        return "ToolLoopExecutionSystem";
    }

    @Override
    public int getOrder() {
        return 30;
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        // If another system already produced an LLM error, let ResponseRouting handle
        // it.
        if (context.getAttribute(ContextAttributes.LLM_ERROR) != null) {
            return false;
        }

        return true;
    }

    @Override
    public AgentContext process(AgentContext context) {
        log.debug("[ToolLoop] Executing tool loop (single-turn)");

        toolLoopSystem.processTurn(context);
        return context;
    }
}
