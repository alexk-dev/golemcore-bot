package me.golemcore.bot.domain.service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.golemcore.bot.port.outbound.PluginConfigurationStorePort;
import org.springframework.stereotype.Service;

/**
 * Stores plugin-owned configuration separately from engine runtime config.
 */
@Service
public class PluginConfigurationService {

    private final PluginConfigurationStorePort pluginConfigurationStorePort;
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public PluginConfigurationService(PluginConfigurationStorePort pluginConfigurationStorePort) {
        this.pluginConfigurationStorePort = pluginConfigurationStorePort;
    }

    public boolean hasPluginConfig(String pluginId) {
        String normalized = normalizePluginId(pluginId);
        return pluginConfigurationStorePort.hasConfig(normalized);
    }

    public synchronized Map<String, Object> getPluginConfig(String pluginId) {
        String normalized = normalizePluginId(pluginId);
        Map<String, Object> cached = cache.get(normalized);
        if (cached != null) {
            return copyMap(cached);
        }

        Map<String, Object> normalizedConfig = pluginConfigurationStorePort.loadConfig(normalized);
        cache.put(normalized, normalizedConfig);
        return copyMap(normalizedConfig);
    }

    public synchronized void savePluginConfig(String pluginId, Map<String, Object> config) {
        String normalized = normalizePluginId(pluginId);
        Map<String, Object> normalizedConfig = copyMap(config != null ? config : Map.of());
        pluginConfigurationStorePort.saveConfig(normalized, normalizedConfig);
        cache.put(normalized, normalizedConfig);
    }

    public synchronized void deletePluginConfig(String pluginId) {
        String normalized = normalizePluginId(pluginId);
        pluginConfigurationStorePort.deleteConfig(normalized);
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
