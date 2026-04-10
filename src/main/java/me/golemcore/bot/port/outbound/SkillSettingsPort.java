package me.golemcore.bot.port.outbound;

import java.time.Duration;

/**
 * Domain-facing access to static skill settings.
 */
public interface SkillSettingsPort {

    SkillSettings skills();

    record SkillSettings(String directory, MarketplaceSettings marketplace) {
        public SkillSettings {
            marketplace = marketplace != null ? marketplace : MarketplaceSettings.disabled();
        }
    }

    record MarketplaceSettings(
            boolean enabled,
            String repositoryDirectory,
            String sandboxPath,
            String repositoryUrl,
            String branch,
            String apiBaseUrl,
            String rawBaseUrl,
            Duration remoteCacheTtl) {

        public static MarketplaceSettings disabled() {
            return new MarketplaceSettings(false, null, null, null, null, null, null, Duration.ofMinutes(5));
        }
    }
}
