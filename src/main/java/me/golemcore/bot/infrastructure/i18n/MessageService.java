package me.golemcore.bot.infrastructure.i18n;

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
import org.springframework.stereotype.Service;

import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internationalization service for localized bot messages.
 *
 * <p>
 * Supports multiple languages with resource bundles:
 * <ul>
 * <li>English (en) - default fallback</li>
 * <li>Russian (ru)</li>
 * </ul>
 *
 * <p>
 * Message bundles are loaded from {@code messages_<lang>.properties} resources.
 * Supports parametric messages using {@link MessageFormat} syntax.
 *
 * <p>
 * Falls back to English if a key is missing in the requested language. If a key
 * is missing entirely, returns {@code [key]} placeholder.
 *
 * @since 1.0
 */
@Service
@Slf4j
public class MessageService {

    public static final String LANG_EN = "en";
    public static final String LANG_RU = "ru";
    public static final String DEFAULT_LANG = LANG_EN;

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of(LANG_EN, LANG_RU);

    private final Map<String, ResourceBundle> bundles = new ConcurrentHashMap<>();
    private volatile String language = DEFAULT_LANG;

    public MessageService() {
        for (String lang : SUPPORTED_LANGUAGES) {
            loadBundle(lang);
        }
    }

    private void loadBundle(String lang) {
        try {
            Locale locale = Locale.forLanguageTag(lang);
            ResourceBundle bundle = ResourceBundle.getBundle("messages", locale);
            bundles.put(lang, bundle);
            log.info("Loaded message bundle for language: {}", lang);
        } catch (MissingResourceException e) {
            log.warn("Failed to load message bundle for language: {}", lang);
        }
    }

    /**
     * Get message in current global language.
     */
    public String getMessage(String key, Object... args) {
        return getMessage(key, language, args);
    }

    /**
     * Get message for specific language.
     */
    public String getMessage(String key, String lang, Object... args) {
        ResourceBundle bundle = bundles.get(lang);
        if (bundle == null) {
            bundle = bundles.get(DEFAULT_LANG);
        }

        try {
            String message = bundle.getString(key);
            if (args != null && args.length > 0) {
                return MessageFormat.format(message, args);
            }
            return message;
        } catch (MissingResourceException e) {
            log.warn("Missing message key: {} for language: {}", key, lang);
            return key;
        }
    }

    /**
     * Get current global language.
     */
    public String getLanguage() {
        return language;
    }

    /**
     * Set global language.
     */
    public void setLanguage(String lang) {
        if (SUPPORTED_LANGUAGES.contains(lang)) {
            language = lang;
            log.info("Global language set to: {}", lang);
        } else {
            log.warn("Unsupported language: {}, using default", lang);
            language = DEFAULT_LANG;
        }
    }

    /**
     * Get all supported languages.
     */
    public Set<String> getSupportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }

    /**
     * Get language display name.
     */
    public String getLanguageDisplayName(String lang) {
        return switch (lang) {
        case LANG_EN -> "English";
        case LANG_RU -> "Русский";
        default -> lang;
        };
    }

    /**
     * Check if language is supported.
     */
    public boolean isSupported(String lang) {
        return SUPPORTED_LANGUAGES.contains(lang);
    }
}
