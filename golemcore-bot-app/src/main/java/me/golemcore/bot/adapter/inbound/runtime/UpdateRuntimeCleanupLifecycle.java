package me.golemcore.bot.adapter.inbound.runtime;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.service.UpdateRuntimeCleanupService;
import me.golemcore.bot.port.outbound.UpdateVersionPort;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Triggers runtime-update cleanup only after the application has started
 * successfully.
 *
 * <p>
 * The lifecycle passes the version of the runtime that actually booted so
 * cleanup can safely discard stale markers that still point to an older jar.
 * </p>
 */
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
