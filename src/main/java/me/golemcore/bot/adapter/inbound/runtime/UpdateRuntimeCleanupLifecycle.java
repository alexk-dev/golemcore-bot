package me.golemcore.bot.adapter.inbound.runtime;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.port.outbound.UpdateVersionPort;
import me.golemcore.bot.domain.service.UpdateRuntimeCleanupService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UpdateRuntimeCleanupLifecycle {

    private final UpdateRuntimeCleanupService updateRuntimeCleanupService;
    private final UpdateVersionPort updateVersionPort;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        updateRuntimeCleanupService.cleanupAfterSuccessfulStartup(updateVersionPort.currentVersion());
    }
}
