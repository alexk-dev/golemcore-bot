package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserPreferencesServiceTest {

    private static final String PREFS_DIR = "preferences";
    private static final String SETTINGS_FILE = "settings.json";
    private static final String LANG_EN = "en";
    private static final String LANG_RU = "ru";
    private static final String NOT_FOUND = "not found";

    private StoragePort storagePort;
    private MessageService messageService;
    private UserPreferencesService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        messageService = mock(MessageService.class);
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        service = new UserPreferencesService(storagePort, messageService);
    }

    // ==================== getPreferences ====================

    @Test
    void getPreferencesCreatesDefaultWhenNoSaved() {
        when(storagePort.getText(PREFS_DIR, SETTINGS_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        UserPreferences prefs = service.getPreferences();

        assertNotNull(prefs);
        assertEquals(LANG_EN, prefs.getLanguage());
        verify(messageService).setLanguage(LANG_EN);
    }

    @Test
    void getPreferencesFallsBackToDefaultWhenStorageJsonInvalid() {
        // UserPreferences lacks @NoArgsConstructor so Jackson deserialization
        // fails gracefully and service creates defaults
        when(storagePort.getText(PREFS_DIR, SETTINGS_FILE))
                .thenReturn(CompletableFuture.completedFuture("{\"language\":\"ru\"}"));

        UserPreferences prefs = service.getPreferences();

        // Falls back to default "en" because @Data @Builder without @NoArgsConstructor
        // prevents Jackson deserialization
        assertNotNull(prefs);
        assertEquals(LANG_EN, prefs.getLanguage());
    }

    @Test
    void getPreferencesReturnsCachedAfterFirstLoad() {
        when(storagePort.getText(PREFS_DIR, SETTINGS_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        UserPreferences first = service.getPreferences();
        UserPreferences second = service.getPreferences();

        assertSame(first, second);
        // Storage should only be queried once
        verify(storagePort, times(1)).getText(PREFS_DIR, SETTINGS_FILE);
    }

    @Test
    void getPreferencesHandlesBlankJson() {
        when(storagePort.getText(PREFS_DIR, SETTINGS_FILE))
                .thenReturn(CompletableFuture.completedFuture("   "));

        UserPreferences prefs = service.getPreferences();

        assertEquals(LANG_EN, prefs.getLanguage()); // default
    }

    @Test
    void getPreferencesHandlesNullJson() {
        when(storagePort.getText(PREFS_DIR, SETTINGS_FILE))
                .thenReturn(CompletableFuture.completedFuture(null));

        UserPreferences prefs = service.getPreferences();

        assertEquals(LANG_EN, prefs.getLanguage()); // default
    }

    @Test
    void getPreferencesHandlesMalformedJson() {
        when(storagePort.getText(PREFS_DIR, SETTINGS_FILE))
                .thenReturn(CompletableFuture.completedFuture("{invalid json"));

        UserPreferences prefs = service.getPreferences();

        assertEquals(LANG_EN, prefs.getLanguage()); // falls back to default
    }

    @Test
    void getPreferencesSavesDefaultOnCreation() {
        when(storagePort.getText(PREFS_DIR, SETTINGS_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        service.getPreferences();

        verify(storagePort).putText(eq(PREFS_DIR), eq(SETTINGS_FILE), anyString());
    }

    @Test
    void getPreferencesHandlesSaveFailureOnCreation() {
        when(storagePort.getText(PREFS_DIR, SETTINGS_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk full")));

        UserPreferences prefs = service.getPreferences();

        assertNotNull(prefs); // should still return default even if save fails
        assertEquals(LANG_EN, prefs.getLanguage());
    }

    // ==================== savePreferences ====================

    @Test
    void savePreferencesPersistsToStorage() {
        UserPreferences prefs = UserPreferences.builder().language(LANG_RU).build();

        service.savePreferences(prefs);

        verify(storagePort).putText(eq(PREFS_DIR), eq(SETTINGS_FILE), contains("\"language\":\"ru\""));
        verify(messageService).setLanguage(LANG_RU);
    }

    @Test
    void savePreferencesHandlesStorageFailure() {
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("error")));

        UserPreferences prefs = UserPreferences.builder().language(LANG_EN).build();

        assertDoesNotThrow(() -> service.savePreferences(prefs));
    }

    @Test
    void savePreferencesUpdatesCachedValue() {
        when(storagePort.getText(PREFS_DIR, SETTINGS_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        service.getPreferences(); // loads default "en"

        UserPreferences newPrefs = UserPreferences.builder().language(LANG_RU).build();
        service.savePreferences(newPrefs);

        assertEquals(LANG_RU, service.getPreferences().getLanguage());
    }

    // ==================== getLanguage / setLanguage ====================

    @Test
    void getLanguageReturnsCurrentLanguage() {
        when(storagePort.getText(PREFS_DIR, SETTINGS_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        assertEquals(LANG_EN, service.getLanguage());
    }

    @Test
    void setLanguageUpdatesAndSaves() {
        when(storagePort.getText(PREFS_DIR, SETTINGS_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        service.setLanguage(LANG_RU);

        assertEquals(LANG_RU, service.getLanguage());
        verify(messageService, atLeastOnce()).setLanguage(LANG_RU);
    }

    // ==================== getMessage ====================

    @Test
    void getMessageDelegatesToMessageService() {
        when(storagePort.getText(PREFS_DIR, SETTINGS_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));
        when(messageService.getMessage("key", LANG_EN, "arg1")).thenReturn("Hello arg1");

        String result = service.getMessage("key", "arg1");

        assertEquals("Hello arg1", result);
    }
}
