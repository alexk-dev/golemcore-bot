package me.golemcore.bot.adapter.outbound.preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StorageUserPreferencesStoreAdapterTest {

    private StoragePort storagePort;
    private ObjectMapper objectMapper;
    private StorageUserPreferencesStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        adapter = new StorageUserPreferencesStoreAdapter(storagePort, objectMapper);
    }

    @Test
    void shouldReturnEmptyOptionalWhenStoredPreferencesAreBlank() {
        when(storagePort.getText("preferences", "settings.json")).thenReturn(CompletableFuture.completedFuture(""));

        Optional<UserPreferences> preferences = adapter.loadPreferences();

        assertTrue(preferences.isEmpty());
    }

    @Test
    void shouldLoadPreferencesFromStorage() throws Exception {
        String json = objectMapper.writeValueAsString(UserPreferences.builder()
                .language("ru")
                .notificationsEnabled(false)
                .timezone("Europe/Moscow")
                .build());
        when(storagePort.getText("preferences", "settings.json")).thenReturn(CompletableFuture.completedFuture(json));

        Optional<UserPreferences> preferences = adapter.loadPreferences();

        assertTrue(preferences.isPresent());
        assertEquals("ru", preferences.get().getLanguage());
        assertFalse(preferences.get().isNotificationsEnabled());
    }

    @Test
    void shouldWrapLoadFailures() {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IOException("preferences unavailable"));
        when(storagePort.getText("preferences", "settings.json")).thenReturn(failed);

        IllegalStateException error = assertThrows(IllegalStateException.class, adapter::loadPreferences);

        assertEquals("Failed to load preferences", error.getMessage());
        assertInstanceOf(IOException.class, error.getCause());
    }

    @Test
    void shouldPersistPreferencesAsJson() {
        when(storagePort.putText(eq("preferences"), eq("settings.json"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        UserPreferences preferences = UserPreferences.builder()
                .language("es")
                .notificationsEnabled(true)
                .build();

        adapter.savePreferences(preferences);

        verify(storagePort).putText(eq("preferences"), eq("settings.json"), anyString());
    }

    @Test
    void shouldWrapSaveFailures() {
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IOException("cannot persist preferences"));
        when(storagePort.putText(eq("preferences"), eq("settings.json"), anyString())).thenReturn(failed);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> adapter.savePreferences(UserPreferences.builder().build()));

        assertEquals("Failed to persist preferences", error.getMessage());
        assertInstanceOf(IOException.class, error.getCause());
    }
}
