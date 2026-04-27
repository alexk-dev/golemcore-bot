package me.golemcore.bot.infrastructure.config;

import me.golemcore.bot.domain.service.AutoModeMigrationService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Starts legacy auto-mode migration before users hit session-scoped auto APIs.
 */
@Component
public class AutoModeMigrationLifecycle {

    private final AutoModeMigrationService autoModeMigrationService;

    public AutoModeMigrationLifecycle(AutoModeMigrationService autoModeMigrationService) {
        this.autoModeMigrationService = autoModeMigrationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateOnApplicationReady() {
        autoModeMigrationService.migrateIfNeeded();
    }
}
