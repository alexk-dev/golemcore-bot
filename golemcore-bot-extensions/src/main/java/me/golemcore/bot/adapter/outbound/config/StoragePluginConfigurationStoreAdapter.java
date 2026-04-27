package me.golemcore.bot.adapter.outbound.config;

import java.io.IOException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.LinkedHashMap;
import java.util.Map;
import me.golemcore.bot.port.outbound.PluginConfigurationStorePort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Component;

@Component
public class StoragePluginConfigurationStoreAdapter implements PluginConfigurationStorePort {

    private static final String PREFERENCES_DIR = "preferences";
    private static final String PLUGINS_PREFIX = "plugins/";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final StoragePort storagePort;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public StoragePluginConfigurationStoreAdapter(StoragePort storagePort) {
        this.storagePort = storagePort;
    }

    @Override
    public boolean hasConfig(String pluginId) {
        return storagePort.exists(PREFERENCES_DIR, pathFor(pluginId)).join();
    }

    @Override
    public Map<String, Object> loadConfig(String pluginId) {
        try {
            String json = storagePort.getText(PREFERENCES_DIR, pathFor(pluginId)).join();
            if (json == null || json.isBlank()) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> config = objectMapper.readValue(json, MAP_TYPE);
            return config != null ? new LinkedHashMap<>(config) : new LinkedHashMap<>();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to read plugin config for " + pluginId, exception);
        }
    }

    @Override
    public void saveConfig(String pluginId, Map<String, Object> config) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            storagePort.putTextAtomic(PREFERENCES_DIR, pathFor(pluginId), json, true).join();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to write plugin config for " + pluginId, exception);
        }
    }

    @Override
    public void deleteConfig(String pluginId) {
        storagePort.deleteObject(PREFERENCES_DIR, pathFor(pluginId)).join();
    }

    private String pathFor(String pluginId) {
        return PLUGINS_PREFIX + pluginId + ".json";
    }
}
