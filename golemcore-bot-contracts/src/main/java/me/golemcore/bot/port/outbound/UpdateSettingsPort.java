package me.golemcore.bot.port.outbound;

import java.time.Duration;

/**
 * Domain-facing access to update runtime settings.
 */
public interface UpdateSettingsPort {

    UpdateSettings update();

    record UpdateSettings(boolean enabled, String updatesPath, int maxKeptVersions, Duration checkInterval) {
        public UpdateSettings {
            checkInterval = checkInterval != null ? checkInterval : Duration.ofHours(1);
        }
    }
}
