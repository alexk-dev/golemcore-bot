package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.PlanReadyEvent;

public interface PlanReadyNotificationPort {

    void publish(PlanReadyEvent event);
}
