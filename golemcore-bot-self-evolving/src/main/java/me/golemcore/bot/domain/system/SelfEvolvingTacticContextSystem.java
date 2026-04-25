package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.selfevolving.tactic.TacticTurnContextService;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import org.springframework.stereotype.Component;

@Component
public class SelfEvolvingTacticContextSystem implements AgentSystem {

    private final SelfEvolvingRuntimeConfigPort runtimeConfigPort;
    private final TacticTurnContextService tacticTurnContextService;

    public SelfEvolvingTacticContextSystem(
            SelfEvolvingRuntimeConfigPort runtimeConfigPort,
            TacticTurnContextService tacticTurnContextService) {
        this.runtimeConfigPort = runtimeConfigPort;
        this.tacticTurnContextService = tacticTurnContextService;
    }

    @Override
    public String getName() {
        return "SelfEvolvingTacticContextSystem";
    }

    @Override
    public int getOrder() {
        return 22;
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigPort != null && runtimeConfigPort.isSelfEvolvingEnabled();
    }

    @Override
    public AgentContext process(AgentContext context) {
        if (context == null || tacticTurnContextService == null) {
            return context;
        }
        tacticTurnContextService.attach(context);
        return context;
    }
}
