package me.golemcore.bot.domain.loop;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.domain.system.ResponseRoutingAgentSystem;

record AgentPipelinePlan(List<AgentSystem> orderedSystems, Optional<ResponseRoutingAgentSystem> routingSystem) {

    AgentPipelinePlan {
        orderedSystems = orderedSystems != null ? List.copyOf(orderedSystems) : List.of();
        routingSystem = Objects.requireNonNull(routingSystem, "routingSystem must not be null");
    }
}
