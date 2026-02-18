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

import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.port.outbound.StoragePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Service for managing global user preferences including language and timezone
 * settings. Uses a single-user design where preferences are stored in
 * preferences/settings.json. Integrates with {@link MessageService} to apply
 * language preferences for i18n.
 */
@Service
@Slf4j
public class UserPreferencesService {

    private static final String PREFERENCES_DIR = "preferences";
    private static final String SETTINGS_FILE = "settings.json";

    private final StoragePort storagePort;
    private final MessageService messageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile UserPreferences preferences;

    public UserPreferencesService(StoragePort storagePort, MessageService messageService) {
        this.storagePort = storagePort;
        this.messageService = messageService;
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
        messageService.setLanguage(prefs.getLanguage());

        try {
            String json = objectMapper.writeValueAsString(prefs);
            storagePort.putText(PREFERENCES_DIR, SETTINGS_FILE, json).join();
            log.debug("Saved global preferences");
        } catch (Exception e) {
            // Rollback in-memory state on persist failure
            this.preferences = previousPrefs;
            if (previousLanguage != null) {
                messageService.setLanguage(previousLanguage);
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
        return messageService.getMessage(key, getLanguage(), args);
    }

    private UserPreferences loadOrCreate() {
        try {
            String json = storagePort.getText(PREFERENCES_DIR, SETTINGS_FILE).join();
            if (json != null && !json.isBlank()) {
                UserPreferences prefs = objectMapper.readValue(json, UserPreferences.class);
                messageService.setLanguage(prefs.getLanguage());
                log.debug("Loaded global preferences");
                return prefs;
            }
        } catch (IOException | RuntimeException e) { // NOSONAR - intentionally catch all for fallback
            log.debug("No saved preferences or failed to parse, creating default: {}", e.getMessage());
        }

        UserPreferences prefs = UserPreferences.builder()
                .language(MessageService.DEFAULT_LANG)
                .build();
        messageService.setLanguage(prefs.getLanguage());
        try {
            String json = objectMapper.writeValueAsString(prefs);
            storagePort.putText(PREFERENCES_DIR, SETTINGS_FILE, json).join();
            log.debug("Created default preferences");
        } catch (Exception e) {
            log.error("Failed to save default preferences", e);
        }
        return prefs;
    }
}
