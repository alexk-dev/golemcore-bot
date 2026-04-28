package me.golemcore.bot.domain.loop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.domain.system.ResponseRoutingAgentSystem;

@Slf4j
final class AgentPipelinePlanFactory {

    AgentPipelinePlan create(List<AgentSystem> systems) {
        List<AgentSystem> orderedSystems = new ArrayList<>(systems != null ? systems : List.of());
        orderedSystems.sort(Comparator.comparingInt(AgentSystem::getOrder));
        log.info("[AgentPipelinePlanFactory] systems in pipeline: {}",
                orderedSystems.stream().map(AgentSystem::getName).toList());
        return new AgentPipelinePlan(orderedSystems,
                orderedSystems.stream().filter(ResponseRoutingAgentSystem.class::isInstance)
                        .map(ResponseRoutingAgentSystem.class::cast).findFirst());
    }
}
