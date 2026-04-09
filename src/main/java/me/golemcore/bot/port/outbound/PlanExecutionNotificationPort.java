package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.PlanExecutionCompletedEvent;

/**
 * Publishes plan execution completion notifications to interested adapters.
 */
public interface PlanExecutionNotificationPort {

    void publish(PlanExecutionCompletedEvent event);
}
