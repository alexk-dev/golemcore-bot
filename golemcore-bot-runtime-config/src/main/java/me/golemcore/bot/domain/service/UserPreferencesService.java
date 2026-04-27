package me.golemcore.bot.domain.service;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.port.outbound.LocalizationPort;
import me.golemcore.bot.port.outbound.UserPreferencesStorePort;
import org.springframework.stereotype.Service;

/**
 * Service for managing global user preferences including language and timezone settings. Uses a single-user design
 * where preferences are stored in preferences/settings.json. Integrates with a localization port to apply language
 * preferences for i18n.
 */
@Service
@Slf4j
public class UserPreferencesService {

    private final UserPreferencesStorePort userPreferencesStorePort;
    private final LocalizationPort localizationPort;

    private volatile UserPreferences preferences;

    public UserPreferencesService(UserPreferencesStorePort userPreferencesStorePort,
            LocalizationPort localizationPort) {
        this.userPreferencesStorePort = userPreferencesStorePort;
        this.localizationPort = localizationPort;
    }

    /**
     * Get global preferences.
     */
    public UserPreferences getPreferences() {
        if (preferences == null) {
            synchronized (this) {
                if (preferences == null) {
                    preferences = loadOrCreate();
                }
            }
        }
        return preferences;
    }

    /**
     * Save global preferences.
     *
     * @throws IllegalStateException
     *             if persistence fails (in-memory state is rolled back)
     */
    public void savePreferences(UserPreferences prefs) {
        UserPreferences previousPrefs = this.preferences;
        String previousLanguage = previousPrefs != null ? previousPrefs.getLanguage() : null;

        this.preferences = prefs;
        localizationPort.setLanguage(prefs.getLanguage());

        try {
            userPreferencesStorePort.savePreferences(prefs);
            log.debug("Saved global preferences");
        } catch (Exception e) {
            // Rollback in-memory state on persist failure
            this.preferences = previousPrefs;
            if (previousLanguage != null) {
                localizationPort.setLanguage(previousLanguage);
            }
            log.error("Failed to save preferences, rolled back to previous state", e);
            throw new IllegalStateException("Failed to persist preferences", e);
        }
    }

    /**
     * Get preferred language.
     */
    public String getLanguage() {
        return getPreferences().getLanguage();
    }

    /**
     * Set preferred language.
     */
    public void setLanguage(String language) {
        UserPreferences prefs = getPreferences();
        prefs.setLanguage(language);
        savePreferences(prefs);
    }

    /**
     * Get localized message.
     */
    public String getMessage(String key, Object... args) {
        return localizationPort.getMessage(key, getLanguage(), args);
    }

    private UserPreferences loadOrCreate() {
        try {
            return userPreferencesStorePort.loadPreferences().map(prefs -> {
                localizationPort.setLanguage(prefs.getLanguage());
                log.debug("Loaded global preferences");
                return prefs;
            }).orElseGet(this::createDefaultPreferences);
        } catch (RuntimeException e) { // NOSONAR - intentionally catch all for fallback
            log.debug("No saved preferences or failed to parse, creating default: {}", e.getMessage());
        }

        return createDefaultPreferences();
    }

    private UserPreferences createDefaultPreferences() {
        UserPreferences prefs = UserPreferences.builder().language(localizationPort.defaultLanguage()).build();
        localizationPort.setLanguage(prefs.getLanguage());
        try {
            userPreferencesStorePort.savePreferences(prefs);
            log.debug("Created default preferences");
        } catch (Exception e) {
            log.error("Failed to save default preferences", e);
        }
        return prefs;
    }
}
