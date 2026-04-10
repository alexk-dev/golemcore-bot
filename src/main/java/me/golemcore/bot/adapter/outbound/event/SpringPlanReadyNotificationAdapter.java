package me.golemcore.bot.adapter.outbound.event;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.PlanReadyEvent;
import me.golemcore.bot.infrastructure.event.SpringEventBus;
import me.golemcore.bot.port.outbound.PlanReadyNotificationPort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpringPlanReadyNotificationAdapter implements PlanReadyNotificationPort {

    private final SpringEventBus springEventBus;

    @Override
    public void publish(PlanReadyEvent event) {
        springEventBus.publish(event);
    }
}
