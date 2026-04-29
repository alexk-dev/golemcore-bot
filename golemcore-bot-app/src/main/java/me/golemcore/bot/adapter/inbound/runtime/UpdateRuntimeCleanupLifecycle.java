package me.golemcore.bot.adapter.inbound.runtime;

import me.golemcore.bot.domain.update.UpdateRuntimeCleanupService;
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
public class UpdateRuntimeCleanupLifecycle {

    private final UpdateRuntimeCleanupService updateRuntimeCleanupService;
    private final UpdateVersionPort updateVersionPort;

    public UpdateRuntimeCleanupLifecycle(
            UpdateRuntimeCleanupService updateRuntimeCleanupService,
            UpdateVersionPort updateVersionPort) {
        this.updateRuntimeCleanupService = updateRuntimeCleanupService;
        this.updateVersionPort = updateVersionPort;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        updateRuntimeCleanupService.cleanupAfterSuccessfulStartup(updateVersionPort.currentVersion());
    }
}
