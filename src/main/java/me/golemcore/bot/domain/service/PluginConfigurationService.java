package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Service;

import me.golemcore.bot.port.outbound.StoragePort;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores plugin-owned configuration separately from engine runtime config.
 */
@Service
public class PluginConfigurationService {

    private static final String PREFERENCES_DIR = "preferences";
    private static final String PLUGINS_PREFIX = "plugins/";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
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
        String normalized = normalizePluginId(pluginId);
        return storagePort.exists(PREFERENCES_DIR, pathFor(normalized)).join();
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
        if (pluginId == null) {
            throw new IllegalArgumentException("Plugin id must match <owner>/<plugin>");
        }
        String normalized = pluginId.trim().toLowerCase(Locale.ROOT);
        int separatorIndex = normalized.indexOf('/');
        if (separatorIndex <= 0 || separatorIndex != normalized.lastIndexOf('/')
                || separatorIndex == normalized.length() - 1) {
            throw new IllegalArgumentException("Plugin id must match <owner>/<plugin>");
        }
        String owner = normalized.substring(0, separatorIndex);
        String name = normalized.substring(separatorIndex + 1);
        validateIdentifier(owner, "Plugin owner");
        validateIdentifier(name, "Plugin name");
        return owner + "/" + name;
    }

    private void validateIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean alphanumeric = (current >= 'a' && current <= 'z') || (current >= '0' && current <= '9');
            if (index == 0 && !alphanumeric) {
                throw new IllegalArgumentException(label + " must start with a lowercase letter or digit");
            }
            if (!alphanumeric && current != '-') {
                throw new IllegalArgumentException(label + " must contain only lowercase letters, digits, and '-'");
            }
        }
    }
}
