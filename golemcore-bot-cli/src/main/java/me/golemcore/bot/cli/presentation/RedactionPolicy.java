package me.golemcore.bot.cli.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

public interface RedactionPolicy {

    String REDACTED_VALUE = "[REDACTED]";

    JsonNode redact(JsonNode node);

    static RedactionPolicy none() {
        return node -> node;
    }

    static RedactionPolicy defaultPolicy() {
        return new SensitiveKeyRedactionPolicy();
    }
}

final class SensitiveKeyRedactionPolicy implements RedactionPolicy {

    @Override
    public JsonNode redact(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode copy = node.deepCopy();
        redactInPlace(copy);
        return copy;
    }

    private static void redactInPlace(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitiveKey(field.getKey())) {
                    objectNode.set(field.getKey(), TextNode.valueOf(REDACTED_VALUE));
                } else {
                    redactInPlace(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            for (JsonNode item : arrayNode) {
                redactInPlace(item);
            }
        }
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("password")
                || normalized.contains("apikey")
                || normalized.contains("authorization")
                || normalized.contains("credential")
                || normalized.contains("privatekey");
    }
}
