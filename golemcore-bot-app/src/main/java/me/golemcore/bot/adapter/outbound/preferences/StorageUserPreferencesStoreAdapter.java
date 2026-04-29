package me.golemcore.bot.adapter.outbound.preferences;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.port.outbound.StoragePort;
import me.golemcore.bot.port.outbound.UserPreferencesStorePort;
import org.springframework.stereotype.Component;

@Component
public class StorageUserPreferencesStoreAdapter implements UserPreferencesStorePort {

    private static final String PREFERENCES_DIR = "preferences";
    private static final String SETTINGS_FILE = "settings.json";

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    public StorageUserPreferencesStoreAdapter(
            StoragePort storagePort,
            ObjectMapper objectMapper) {
        this.storagePort = storagePort;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<UserPreferences> loadPreferences() {
        try {
            String json = storagePort.getText(PREFERENCES_DIR, SETTINGS_FILE).join();
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, UserPreferences.class));
        } catch (IOException | RuntimeException exception) { // NOSONAR - fallback to defaults
            throw new IllegalStateException("Failed to load preferences", unwrapCompletion(exception));
        }
    }

    @Override
    public void savePreferences(UserPreferences preferences) {
        try {
            String json = objectMapper.writeValueAsString(preferences);
            storagePort.putText(PREFERENCES_DIR, SETTINGS_FILE, json).join();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist preferences", unwrapCompletion(exception));
        }
    }

    private Throwable unwrapCompletion(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }
}
