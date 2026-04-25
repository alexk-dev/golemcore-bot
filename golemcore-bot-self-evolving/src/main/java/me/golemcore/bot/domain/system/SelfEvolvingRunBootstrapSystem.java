package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.selfevolving.run.SelfEvolvingRunService;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import org.springframework.stereotype.Component;

@Component
public class SelfEvolvingRunBootstrapSystem implements AgentSystem {

    private final SelfEvolvingRuntimeConfigPort runtimeConfigPort;
    private final SelfEvolvingRunService selfEvolvingRunService;

    public SelfEvolvingRunBootstrapSystem(
            SelfEvolvingRuntimeConfigPort runtimeConfigPort,
            SelfEvolvingRunService selfEvolvingRunService) {
        this.runtimeConfigPort = runtimeConfigPort;
        this.selfEvolvingRunService = selfEvolvingRunService;
    }

    @Override
    public String getName() {
        return "SelfEvolvingRunBootstrapSystem";
    }

    @Override
    public int getOrder() {
        return 21;
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigPort != null && runtimeConfigPort.isSelfEvolvingEnabled();
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        return isEnabled()
                && context != null
                && context.getSession() != null
                && context.getAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID) == null;
    }

    @Override
    public AgentContext process(AgentContext context) {
        if (!shouldProcess(context)) {
            return context;
        }
        RunRecord run = selfEvolvingRunService.startRun(context);
        context.setAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID, run.getId());
        context.setAttribute(ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID, run.getArtifactBundleId());
        context.setAttribute(ContextAttributes.SELF_EVOLVING_ANALYSIS_COMPLETED, false);
        return context;
    }
}
