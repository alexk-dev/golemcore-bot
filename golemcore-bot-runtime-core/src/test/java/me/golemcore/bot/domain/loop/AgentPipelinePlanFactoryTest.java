package me.golemcore.bot.domain.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.domain.system.ResponseRoutingAgentSystem;
import org.junit.jupiter.api.Test;

class AgentPipelinePlanFactoryTest {

    @Test
    void shouldBuildImmutableOrderedPlanAndDetectRoutingSystem() {
        AgentSystem late = system("late", 20);
        ResponseRoutingAgentSystem routing = routingSystem("routing", 10);

        AgentPipelinePlan plan = new AgentPipelinePlanFactory().create(List.of(late, routing));

        assertEquals(List.of(routing, late), plan.orderedSystems());
        assertEquals(Optional.of(routing), plan.routingSystem());
        List<AgentSystem> orderedSystems = plan.orderedSystems();
        assertThrows(UnsupportedOperationException.class, () -> orderedSystems.add(late));
    }

    @Test
    void shouldBuildEmptyPlanForNullSystems() {
        AgentPipelinePlan plan = new AgentPipelinePlanFactory().create(null);

        assertTrue(plan.orderedSystems().isEmpty());
        assertTrue(plan.routingSystem().isEmpty());
    }

    @Test
    void shouldRejectNullRoutingOptional() {
        List<AgentSystem> orderedSystems = List.of();
        assertThrows(NullPointerException.class, () -> new AgentPipelinePlan(orderedSystems, null));
    }

    private static AgentSystem system(String name, int order) {
        return new AgentSystem() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getOrder() {
                return order;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                return context;
            }
        };
    }

    private static ResponseRoutingAgentSystem routingSystem(String name, int order) {
        return new ResponseRoutingAgentSystem() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getOrder() {
                return order;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                return context;
            }
        };
    }
}
