package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.port.outbound.UserPreferencesStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserPreferencesServiceTest {

    private static final String LANG_EN = "en";
    private static final String LANG_RU = "ru";
    private static final String NOT_FOUND = "not found";

    private UserPreferencesStorePort userPreferencesStorePort;
    private MessageService messageService;
    private UserPreferencesService service;

    @BeforeEach
    void setUp() {
        userPreferencesStorePort = mock(UserPreferencesStorePort.class);
        messageService = mock(MessageService.class);
        service = new UserPreferencesService(userPreferencesStorePort,
                me.golemcore.bot.support.TestPorts.localization(messageService));
    }

    // ==================== getPreferences ====================

    @Test
    void getPreferencesCreatesDefaultWhenNoSaved() {
        when(userPreferencesStorePort.loadPreferences())
                .thenThrow(new IllegalStateException(NOT_FOUND));

        UserPreferences prefs = service.getPreferences();

        assertNotNull(prefs);
        assertEquals(LANG_EN, prefs.getLanguage());
        verify(messageService).setLanguage(LANG_EN);
    }

    @Test
    void getPreferencesDeserializesStoredJson() {
        when(userPreferencesStorePort.loadPreferences())
                .thenReturn(Optional.of(UserPreferences.builder().language(LANG_RU).build()));

        UserPreferences prefs = service.getPreferences();

        assertNotNull(prefs);
        assertEquals("ru", prefs.getLanguage());
    }

    @Test
    void getPreferencesReturnsCachedAfterFirstLoad() {
        when(userPreferencesStorePort.loadPreferences())
                .thenThrow(new IllegalStateException(NOT_FOUND));

        UserPreferences first = service.getPreferences();
        UserPreferences second = service.getPreferences();

        assertSame(first, second);
        // Storage should only be queried once
        verify(userPreferencesStorePort, times(1)).loadPreferences();
    }

    @Test
    void getPreferencesHandlesBlankJson() {
        when(userPreferencesStorePort.loadPreferences())
                .thenReturn(Optional.empty());

        UserPreferences prefs = service.getPreferences();

        assertEquals(LANG_EN, prefs.getLanguage()); // default
    }

    @Test
    void getPreferencesHandlesNullJson() {
        when(userPreferencesStorePort.loadPreferences())
                .thenReturn(Optional.empty());

        UserPreferences prefs = service.getPreferences();

        assertEquals(LANG_EN, prefs.getLanguage()); // default
    }

    @Test
    void getPreferencesHandlesMalformedJson() {
        when(userPreferencesStorePort.loadPreferences())
                .thenThrow(new IllegalStateException("malformed"));

        UserPreferences prefs = service.getPreferences();

        assertEquals(LANG_EN, prefs.getLanguage()); // falls back to default
    }

    @Test
    void getPreferencesSavesDefaultOnCreation() {
        when(userPreferencesStorePort.loadPreferences())
                .thenThrow(new IllegalStateException(NOT_FOUND));

        service.getPreferences();

        verify(userPreferencesStorePort).savePreferences(any(UserPreferences.class));
    }

    @Test
    void getPreferencesHandlesSaveFailureOnCreation() {
        when(userPreferencesStorePort.loadPreferences())
                .thenThrow(new IllegalStateException(NOT_FOUND));
        doThrow(new IllegalStateException("disk full"))
                .when(userPreferencesStorePort).savePreferences(any(UserPreferences.class));

        UserPreferences prefs = service.getPreferences();

        assertNotNull(prefs); // should still return default even if save fails
        assertEquals(LANG_EN, prefs.getLanguage());
    }

    // ==================== savePreferences ====================

    @Test
    void savePreferencesPersistsToStorage() {
        UserPreferences prefs = UserPreferences.builder().language(LANG_RU).build();

        service.savePreferences(prefs);

        verify(userPreferencesStorePort).savePreferences(prefs);
        verify(messageService).setLanguage(LANG_RU);
    }

    @Test
    void savePreferencesThrowsOnStorageFailure() {
        doThrow(new IllegalStateException("disk error"))
                .when(userPreferencesStorePort).savePreferences(any(UserPreferences.class));

        UserPreferences prefs = UserPreferences.builder().language(LANG_EN).build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.savePreferences(prefs));
        assertTrue(ex.getMessage().contains("Failed to persist"));
    }

    @Test
    void savePreferencesRollsBackOnStorageFailure() {
        // First load creates default preferences with "en"
        when(userPreferencesStorePort.loadPreferences())
                .thenReturn(Optional.of(UserPreferences.builder().language(LANG_EN).build()));

        UserPreferences original = service.getPreferences();
        assertEquals(LANG_EN, original.getLanguage());

        // Now make storage fail for the next save
        UserPreferences newPrefs = UserPreferences.builder().language(LANG_RU).build();
        doThrow(new IllegalStateException("disk full"))
                .when(userPreferencesStorePort).savePreferences(newPrefs);

        // Attempt to save should throw
        assertThrows(IllegalStateException.class, () -> service.savePreferences(newPrefs));

        // After failure, preferences should be rolled back to original
        assertEquals(LANG_EN, service.getPreferences().getLanguage());
        // MessageService should also be rolled back
        verify(messageService, atLeastOnce()).setLanguage(LANG_EN);
    }

    @Test
    void savePreferencesUpdatesCachedValue() {
        when(userPreferencesStorePort.loadPreferences())
                .thenThrow(new IllegalStateException(NOT_FOUND));

        service.getPreferences(); // loads default "en"

        UserPreferences newPrefs = UserPreferences.builder().language(LANG_RU).build();
        service.savePreferences(newPrefs);

        assertEquals(LANG_RU, service.getPreferences().getLanguage());
    }

    // ==================== getLanguage / setLanguage ====================

    @Test
    void getLanguageReturnsCurrentLanguage() {
        when(userPreferencesStorePort.loadPreferences())
                .thenThrow(new IllegalStateException(NOT_FOUND));

        assertEquals(LANG_EN, service.getLanguage());
    }

    @Test
    void setLanguageUpdatesAndSaves() {
        when(userPreferencesStorePort.loadPreferences())
                .thenThrow(new IllegalStateException(NOT_FOUND));

        service.setLanguage(LANG_RU);

        assertEquals(LANG_RU, service.getLanguage());
        verify(messageService, atLeastOnce()).setLanguage(LANG_RU);
    }

    // ==================== getMessage ====================

    @Test
    void getMessageDelegatesToMessageService() {
        when(userPreferencesStorePort.loadPreferences())
                .thenThrow(new IllegalStateException(NOT_FOUND));
        when(messageService.getMessage("key", LANG_EN, "arg1")).thenReturn("Hello arg1");

        String result = service.getMessage("key", "arg1");

        assertEquals("Hello arg1", result);
    }
}
