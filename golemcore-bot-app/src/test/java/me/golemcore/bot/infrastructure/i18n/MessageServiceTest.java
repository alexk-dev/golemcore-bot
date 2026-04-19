package me.golemcore.bot.infrastructure.i18n;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageServiceTest {

    private static final String KEY_SETTINGS_TITLE = "settings.title";

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService();
    }

    // --- getMessage (current language) ---

    @Test
    void shouldReturnEnglishMessageByDefault() {
        String result = messageService.getMessage(KEY_SETTINGS_TITLE);

        assertEquals("Settings", result);
    }

    @Test
    void shouldReturnRussianMessageWhenLanguageSetToRu() {
        messageService.setLanguage("ru");

        String result = messageService.getMessage(KEY_SETTINGS_TITLE);

        assertEquals("\u041D\u0430\u0441\u0442\u0440\u043E\u0439\u043A\u0438", result);
    }

    @Test
    void shouldReturnKeyWhenMessageNotFound() {
        String result = messageService.getMessage("nonexistent.key");

        assertEquals("nonexistent.key", result);
    }

    @Test
    void shouldFormatMessageWithParameters() {
        String result = messageService.getMessage("system.error.general", "en", "timeout");

        assertEquals("An error occurred: timeout", result);
    }

    @Test
    void shouldFormatMessageWithMultipleParameters() {
        String result = messageService.getMessage("command.compact.done", "en", 10, 5);

        assertEquals("Compacted: removed 10 messages, kept last 5.", result);
    }

    @Test
    void shouldFormatRussianMessageWithParameters() {
        String result = messageService.getMessage("system.error.general", "ru", "timeout");

        assertEquals(
                "\u041F\u0440\u043E\u0438\u0437\u043E\u0448\u043B\u0430 \u043E\u0448\u0438\u0431\u043A\u0430: timeout",
                result);
    }

    // --- getMessage (explicit language) ---

    @Test
    void shouldReturnEnglishMessageWhenExplicitlyRequested() {
        String result = messageService.getMessage(KEY_SETTINGS_TITLE, "en");

        assertEquals("Settings", result);
    }

    @Test
    void shouldReturnRussianMessageWhenExplicitlyRequested() {
        String result = messageService.getMessage(KEY_SETTINGS_TITLE, "ru");

        assertEquals("\u041D\u0430\u0441\u0442\u0440\u043E\u0439\u043A\u0438", result);
    }

    @Test
    void shouldFallbackToEnglishWhenLanguageNotSupported() {
        String result = messageService.getMessage(KEY_SETTINGS_TITLE, "fr");

        assertEquals("Settings", result);
    }

    @Test
    void shouldReturnKeyWhenMessageNotFoundInAnyLanguage() {
        String result = messageService.getMessage("totally.missing.key", "en");

        assertEquals("totally.missing.key", result);
    }

    @Test
    void shouldReturnMessageWithoutFormattingWhenNoArgs() {
        String result = messageService.getMessage("security.unauthorized", "en");

        assertEquals("Access denied. You are not authorized to use this bot.", result);
    }

    // --- setLanguage ---

    @Test
    void shouldSetLanguageToEnglish() {
        messageService.setLanguage("ru");
        messageService.setLanguage("en");

        assertEquals("en", messageService.getLanguage());
    }

    @Test
    void shouldSetLanguageToRussian() {
        messageService.setLanguage("ru");

        assertEquals("ru", messageService.getLanguage());
    }

    @Test
    void shouldFallbackToDefaultWhenUnsupportedLanguageSet() {
        messageService.setLanguage("ru");
        messageService.setLanguage("fr");

        assertEquals("en", messageService.getLanguage());
    }

    @Test
    void shouldThrowWhenNullLanguageSet() {
        assertThrows(NullPointerException.class, () -> messageService.setLanguage(null));
    }

    // --- getLanguage ---

    @Test
    void shouldReturnDefaultLanguageInitially() {
        assertEquals("en", messageService.getLanguage());
    }

    // --- getLanguageDisplayName ---

    @Test
    void shouldReturnEnglishDisplayName() {
        String result = messageService.getLanguageDisplayName("en");

        assertEquals("English", result);
    }

    @Test
    void shouldReturnRussianDisplayName() {
        String result = messageService.getLanguageDisplayName("ru");

        assertEquals("\u0420\u0443\u0441\u0441\u043A\u0438\u0439", result);
    }

    @Test
    void shouldReturnLanguageCodeAsDisplayNameWhenUnknown() {
        String result = messageService.getLanguageDisplayName("de");

        assertEquals("de", result);
    }

    // --- getSupportedLanguages ---

    @Test
    void shouldReturnAllSupportedLanguages() {
        Set<String> supported = messageService.getSupportedLanguages();

        assertEquals(2, supported.size());
        assertTrue(supported.contains("en"));
        assertTrue(supported.contains("ru"));
    }

    // --- isSupported ---

    @Test
    void shouldReturnTrueForEnglish() {
        assertTrue(messageService.isSupported("en"));
    }

    @Test
    void shouldReturnTrueForRussian() {
        assertTrue(messageService.isSupported("ru"));
    }

    @Test
    void shouldReturnFalseForUnsupportedLanguage() {
        assertFalse(messageService.isSupported("fr"));
    }

    @Test
    void shouldThrowForNullLanguageInIsSupported() {
        assertThrows(NullPointerException.class, () -> messageService.isSupported(null));
    }

    @Test
    void shouldReturnFalseForEmptyLanguage() {
        assertFalse(messageService.isSupported(""));
    }
}
