package me.golemcore.bot.adapter.inbound.runtime;

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.DelayedJobReadyEvent;
import me.golemcore.bot.domain.scheduling.DelayedSessionActionService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DelayedJobReadyEventListener {

    private final DelayedSessionActionService delayedSessionActionService;

    public DelayedJobReadyEventListener(DelayedSessionActionService delayedSessionActionService) {
        this.delayedSessionActionService = delayedSessionActionService;
    }

    @EventListener
    public void onDelayedJobReady(DelayedJobReadyEvent event) {
        try {
            delayedSessionActionService.scheduleJobReadyNotification(event);
        } catch (RuntimeException exception) {
            log.warn("[DelayedActions] Failed to schedule job-ready event: {}", exception.getMessage());
        }
    }
}
