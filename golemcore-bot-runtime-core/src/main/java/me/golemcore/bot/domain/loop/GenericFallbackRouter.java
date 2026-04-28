package me.golemcore.bot.domain.loop;

import me.golemcore.bot.domain.model.AgentContext;

final class GenericFallbackRouter {

    private final AgentPipelineRunner pipelineRunner;

    GenericFallbackRouter(AgentPipelineRunner pipelineRunner) {
        this.pipelineRunner = pipelineRunner;
    }

    AgentContext route(AgentContext context, String message) {
        return pipelineRunner.routeSyntheticAssistantResponse(context, message);
    }

    AgentContext routeExisting(AgentContext context) {
        return pipelineRunner.routeResponse(context);
    }
}
