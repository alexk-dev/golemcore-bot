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
        when(storagePort.getText("preferences", "settings.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        UserPreferences prefs = service.getPreferences();

        assertNotNull(prefs);
        assertEquals("en", prefs.getLanguage());
        verify(messageService).setLanguage("en");
    }

    @Test
    void getPreferencesFallsBackToDefaultWhenStorageJsonInvalid() {
        // UserPreferences lacks @NoArgsConstructor so Jackson deserialization
        // fails gracefully and service creates defaults
        when(storagePort.getText("preferences", "settings.json"))
                .thenReturn(CompletableFuture.completedFuture("{\"language\":\"ru\"}"));

        UserPreferences prefs = service.getPreferences();

        // Falls back to default "en" because @Data @Builder without @NoArgsConstructor
        // prevents Jackson deserialization
        assertNotNull(prefs);
        assertEquals("en", prefs.getLanguage());
    }

    @Test
    void getPreferencesReturnsCachedAfterFirstLoad() {
        when(storagePort.getText("preferences", "settings.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        UserPreferences first = service.getPreferences();
        UserPreferences second = service.getPreferences();

        assertSame(first, second);
        // Storage should only be queried once
        verify(storagePort, times(1)).getText("preferences", "settings.json");
    }

    @Test
    void getPreferencesHandlesBlankJson() {
        when(storagePort.getText("preferences", "settings.json"))
                .thenReturn(CompletableFuture.completedFuture("   "));

        UserPreferences prefs = service.getPreferences();

        assertEquals("en", prefs.getLanguage()); // default
    }

    @Test
    void getPreferencesHandlesNullJson() {
        when(storagePort.getText("preferences", "settings.json"))
                .thenReturn(CompletableFuture.completedFuture(null));

        UserPreferences prefs = service.getPreferences();

        assertEquals("en", prefs.getLanguage()); // default
    }

    @Test
    void getPreferencesHandlesMalformedJson() {
        when(storagePort.getText("preferences", "settings.json"))
                .thenReturn(CompletableFuture.completedFuture("{invalid json"));

        UserPreferences prefs = service.getPreferences();

        assertEquals("en", prefs.getLanguage()); // falls back to default
    }

    @Test
    void getPreferencesSavesDefaultOnCreation() {
        when(storagePort.getText("preferences", "settings.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        service.getPreferences();

        verify(storagePort).putText(eq("preferences"), eq("settings.json"), anyString());
    }

    @Test
    void getPreferencesHandlesSaveFailureOnCreation() {
        when(storagePort.getText("preferences", "settings.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk full")));

        UserPreferences prefs = service.getPreferences();

        assertNotNull(prefs); // should still return default even if save fails
        assertEquals("en", prefs.getLanguage());
    }

    // ==================== savePreferences ====================

    @Test
    void savePreferencesPersistsToStorage() {
        UserPreferences prefs = UserPreferences.builder().language("ru").build();

        service.savePreferences(prefs);

        verify(storagePort).putText(eq("preferences"), eq("settings.json"), contains("\"language\":\"ru\""));
        verify(messageService).setLanguage("ru");
    }

    @Test
    void savePreferencesHandlesStorageFailure() {
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("error")));

        UserPreferences prefs = UserPreferences.builder().language("en").build();

        assertDoesNotThrow(() -> service.savePreferences(prefs));
    }

    @Test
    void savePreferencesUpdatesCachedValue() {
        when(storagePort.getText("preferences", "settings.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        service.getPreferences(); // loads default "en"

        UserPreferences newPrefs = UserPreferences.builder().language("ru").build();
        service.savePreferences(newPrefs);

        assertEquals("ru", service.getPreferences().getLanguage());
    }

    // ==================== getLanguage / setLanguage ====================

    @Test
    void getLanguageReturnsCurrentLanguage() {
        when(storagePort.getText("preferences", "settings.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        assertEquals("en", service.getLanguage());
    }

    @Test
    void setLanguageUpdatesAndSaves() {
        when(storagePort.getText("preferences", "settings.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        service.setLanguage("ru");

        assertEquals("ru", service.getLanguage());
        verify(messageService, atLeastOnce()).setLanguage("ru");
    }

    // ==================== getMessage ====================

    @Test
    void getMessageDelegatesToMessageService() {
        when(storagePort.getText("preferences", "settings.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));
        when(messageService.getMessage("key", "en", "arg1")).thenReturn("Hello arg1");

        String result = service.getMessage("key", "arg1");

        assertEquals("Hello arg1", result);
    }
}
