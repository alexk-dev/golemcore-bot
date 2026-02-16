package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.port.outbound.RuntimeModuleConfigPort;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Service for dynamic runtime module configs (NoSQL-style JSON payload per
 * module).
 */
@Service
@RequiredArgsConstructor
public class RuntimeModuleConfigService {

    private static final Pattern MODULE_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,63}$");

    private final RuntimeModuleConfigPort port;
    private final ObjectMapper objectMapper;

    public Map<String, JsonNode> getAllMasked() {
        Map<String, JsonNode> source = port.loadAll();
        Map<String, JsonNode> masked = new LinkedHashMap<>();
        source.forEach((k, v) -> masked.put(k, maskSecrets(v)));
        return masked;
    }

    public Map<String, JsonNode> patchMasked(Map<String, JsonNode> patch) {
        Map<String, JsonNode> current = port.loadAll();

        for (Map.Entry<String, JsonNode> e : patch.entrySet()) {
            String moduleId = normalizeAndValidateModuleId(e.getKey());
            JsonNode incoming = e.getValue();
            JsonNode merged = mergeNode(current.get(moduleId), incoming);
            current.put(moduleId, merged);
        }

        port.saveAll(current);
        return getAllMasked();
    }

    private String normalizeAndValidateModuleId(String moduleId) {
        String id = moduleId == null ? "" : moduleId.trim().toLowerCase();
        if (!MODULE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid module id: " + moduleId);
        }
        return id;
    }

    private JsonNode mergeNode(JsonNode current, JsonNode incoming) {
        if (incoming == null || incoming.isNull()) {
            return current != null ? current.deepCopy() : objectMapper.createObjectNode();
        }
        if (current == null || current.isNull()) {
            return incoming.deepCopy();
        }

        if (current.isObject() && incoming.isObject()) {
            ObjectNode result = (ObjectNode) current.deepCopy();
            incoming.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode inVal = entry.getValue();
                JsonNode curVal = result.get(key);

                if (inVal != null && inVal.isTextual() && "***".equals(inVal.textValue()) && isSecretKey(key)) {
                    return;
                }

                if (curVal != null && curVal.isObject() && inVal != null && inVal.isObject()) {
                    result.set(key, mergeNode(curVal, inVal));
                } else {
                    result.set(key, inVal);
                }
            });
            return result;
        }

        return incoming.deepCopy();
    }

    private JsonNode maskSecrets(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode copy = (ObjectNode) node.deepCopy();
            copy.fieldNames().forEachRemaining(field -> {
                JsonNode child = copy.get(field);
                if (isSecretKey(field) && child != null && child.isTextual() && !child.textValue().isBlank()) {
                    copy.put(field, "***");
                } else {
                    copy.set(field, maskSecrets(child));
                }
            });
            return copy;
        }
        if (node.isArray()) {
            ArrayNode arr = objectMapper.createArrayNode();
            node.forEach(item -> arr.add(maskSecrets(item)));
            return arr;
        }
        return node;
    }

    private boolean isSecretKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase();
        return k.contains("key") || k.contains("token") || k.contains("secret") || k.contains("password");
    }
}
