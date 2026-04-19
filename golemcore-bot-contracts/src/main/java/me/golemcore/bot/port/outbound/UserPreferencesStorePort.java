package me.golemcore.bot.port.outbound;

import java.util.Optional;
import me.golemcore.bot.domain.model.UserPreferences;

/**
 * Loads and persists global user preferences.
 */
public interface UserPreferencesStorePort {

    Optional<UserPreferences> loadPreferences();

    void savePreferences(UserPreferences preferences);
}
