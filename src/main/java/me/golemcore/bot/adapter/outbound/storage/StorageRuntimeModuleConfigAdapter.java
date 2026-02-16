package me.golemcore.bot.adapter.outbound.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.port.outbound.RuntimeModuleConfigPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Storage-backed adapter for runtime module configs.
 *
 * Persists all module payloads into a single JSON document:
 * preferences/runtime-modules.json
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StorageRuntimeModuleConfigAdapter implements RuntimeModuleConfigPort {

    private static final String DIR = "preferences";
    private static final String FILE = "runtime-modules.json";

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, JsonNode> loadAll() {
        try {
            String json = storagePort.getText(DIR, FILE).join();
            if (json == null || json.isBlank()) {
                return new LinkedHashMap<>();
            }
            Map<String, JsonNode> map = objectMapper.readValue(json, new TypeReference<Map<String, JsonNode>>() {
            });
            return map != null ? new LinkedHashMap<>(map) : new LinkedHashMap<>();
        } catch (Exception e) {
            log.debug("[RuntimeModules] No saved modules config yet: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @Override
    public void saveAll(Map<String, JsonNode> modules) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(modules);
            storagePort.putText(DIR, FILE, json).join();
        } catch (Exception e) {
            log.error("[RuntimeModules] Failed to persist module configs", e);
        }
    }
}
