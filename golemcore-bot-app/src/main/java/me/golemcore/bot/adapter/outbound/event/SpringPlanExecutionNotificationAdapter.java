package me.golemcore.bot.adapter.outbound.event;

import me.golemcore.bot.domain.model.PlanExecutionCompletedEvent;
import me.golemcore.bot.infrastructure.event.SpringEventBus;
import me.golemcore.bot.port.outbound.PlanExecutionNotificationPort;
import org.springframework.stereotype.Component;

@Component
public class SpringPlanExecutionNotificationAdapter implements PlanExecutionNotificationPort {

    private final SpringEventBus springEventBus;

    public SpringPlanExecutionNotificationAdapter(SpringEventBus springEventBus) {
        this.springEventBus = springEventBus;
    }

    @Override
    public void publish(PlanExecutionCompletedEvent event) {
        springEventBus.publish(event);
    }
}
