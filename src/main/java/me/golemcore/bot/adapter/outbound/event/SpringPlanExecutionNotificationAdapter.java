package me.golemcore.bot.adapter.outbound.event;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.PlanExecutionCompletedEvent;
import me.golemcore.bot.infrastructure.event.SpringEventBus;
import me.golemcore.bot.port.outbound.PlanExecutionNotificationPort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpringPlanExecutionNotificationAdapter implements PlanExecutionNotificationPort {

    private final SpringEventBus springEventBus;

    @Override
    public void publish(PlanExecutionCompletedEvent event) {
        springEventBus.publish(event);
    }
}
