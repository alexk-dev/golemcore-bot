package me.golemcore.bot.adapter.inbound.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.DelayedJobReadyEvent;
import me.golemcore.bot.domain.service.DelayedSessionActionService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DelayedJobReadyEventListener {

    private final DelayedSessionActionService delayedSessionActionService;

    @EventListener
    public void onDelayedJobReady(DelayedJobReadyEvent event) {
        try {
            delayedSessionActionService.scheduleJobReadyNotification(event);
        } catch (RuntimeException exception) {
            log.warn("[DelayedActions] Failed to schedule job-ready event: {}", exception.getMessage());
        }
    }
}
