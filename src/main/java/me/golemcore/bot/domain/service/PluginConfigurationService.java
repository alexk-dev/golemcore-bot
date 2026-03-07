package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Service;

import me.golemcore.bot.port.outbound.StoragePort;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores plugin-owned configuration separately from engine runtime config.
 */
@Service
public class PluginConfigurationService {

    private static final String PREFERENCES_DIR = "preferences";
    private static final String PLUGINS_PREFIX = "plugins/";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public PluginConfigurationService(StoragePort storagePort) {
        this.storagePort = storagePort;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public boolean hasPluginConfig(String pluginId) {
        return storagePort.exists(PREFERENCES_DIR, pathFor(pluginId)).join();
    }

    public synchronized Map<String, Object> getPluginConfig(String pluginId) {
        String normalized = normalizePluginId(pluginId);
        Map<String, Object> cached = cache.get(normalized);
        if (cached != null) {
            return copyMap(cached);
        }

        String json = storagePort.getText(PREFERENCES_DIR, pathFor(normalized)).join();
        if (json == null || json.isBlank()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            cache.put(normalized, empty);
            return new LinkedHashMap<>();
        }

        try {
            Map<String, Object> config = objectMapper.readValue(json, MAP_TYPE);
            Map<String, Object> normalizedConfig = config != null ? config : new LinkedHashMap<>();
            cache.put(normalized, normalizedConfig);
            return copyMap(normalizedConfig);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read plugin config for " + normalized, e);
        }
    }

    public synchronized void savePluginConfig(String pluginId, Map<String, Object> config) {
        String normalized = normalizePluginId(pluginId);
        Map<String, Object> normalizedConfig = copyMap(config != null ? config : Map.of());
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalizedConfig);
            storagePort.putTextAtomic(PREFERENCES_DIR, pathFor(normalized), json, true).join();
            cache.put(normalized, normalizedConfig);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write plugin config for " + normalized, e);
        }
    }

    public synchronized void deletePluginConfig(String pluginId) {
        String normalized = normalizePluginId(pluginId);
        storagePort.deleteObject(PREFERENCES_DIR, pathFor(normalized)).join();
        cache.remove(normalized);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copyMap(Map<String, Object> source) {
        Map<String, Object> copied = new LinkedHashMap<>();
        source.forEach((key, value) -> copied.put(key, copyValue(value)));
        return copied;
    }

    private Object copyValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> copied.put(String.valueOf(key), copyValue(nestedValue)));
            return copied;
        }
        if (value instanceof java.util.List<?> list) {
            java.util.List<Object> copied = new java.util.ArrayList<>(list.size());
            list.forEach(item -> copied.add(copyValue(item)));
            return copied;
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        return value;
    }

    private String pathFor(String pluginId) {
        return PLUGINS_PREFIX + pluginId + ".json";
    }

    private String normalizePluginId(String pluginId) {
        if (pluginId == null || !pluginId.matches("[a-z0-9][a-z0-9-]*/[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("Plugin id must match <owner>/<plugin>");
        }
        return pluginId;
    }
}
