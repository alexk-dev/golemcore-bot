package me.golemcore.bot.infrastructure.config;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.service.AutoModeMigrationService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Starts legacy auto-mode migration before users hit session-scoped auto APIs.
 */
@Component
@RequiredArgsConstructor
public class AutoModeMigrationLifecycle {

    private final AutoModeMigrationService autoModeMigrationService;

    @EventListener(ApplicationReadyEvent.class)
    public void migrateOnApplicationReady() {
        autoModeMigrationService.migrateIfNeeded();
    }
}
