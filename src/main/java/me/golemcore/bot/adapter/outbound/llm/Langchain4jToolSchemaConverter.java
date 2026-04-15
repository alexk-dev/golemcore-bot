package me.golemcore.bot.adapter.outbound.llm;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.ToolDefinition;

@Slf4j
class Langchain4jToolSchemaConverter {

    private static final String SCHEMA_KEY_PROPERTIES = "properties";

    List<ToolSpecification> convertTools(LlmRequest request) {
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, ToolDefinition> uniqueTools = new LinkedHashMap<>();
        for (ToolDefinition tool : request.getTools()) {
            if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
                log.warn("[LLM] Dropping tool with blank name before request serialization");
                continue;
            }
            ToolDefinition previous = uniqueTools.putIfAbsent(tool.getName(), tool);
            if (previous != null) {
                log.warn("[LLM] Dropping duplicate tool definition '{}' before request serialization", tool.getName());
            }
        }

        List<ToolSpecification> tools = new ArrayList<>(uniqueTools.size());
        for (ToolDefinition tool : uniqueTools.values()) {
            tools.add(convertToolDefinition(tool));
        }
        return tools;
    }

    private ToolSpecification convertToolDefinition(ToolDefinition tool) {
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(tool.getName())
                .description(tool.getDescription());

        if (tool.getInputSchema() != null) {
            Map<String, Object> schema = tool.getInputSchema();
            Map<String, Object> properties = stringObjectMap(schema.get(SCHEMA_KEY_PROPERTIES),
                    tool.getName(), SCHEMA_KEY_PROPERTIES);
            List<String> required = stringList(schema.get("required"), tool.getName(), "required");
            if (properties != null) {
                JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();
                for (Map.Entry<String, Object> entry : properties.entrySet()) {
                    String paramName = entry.getKey();
                    Map<String, Object> paramSchema = stringObjectMap(entry.getValue(), tool.getName(),
                            SCHEMA_KEY_PROPERTIES + "." + paramName);
                    if (paramSchema == null) {
                        log.warn("[LLM] Dropping invalid schema for tool '{}' param '{}'", tool.getName(), paramName);
                        continue;
                    }
                    schemaBuilder.addProperty(paramName, toJsonSchemaElement(tool.getName(),
                            SCHEMA_KEY_PROPERTIES + "." + paramName, paramSchema));
                }
                if (required != null && !required.isEmpty()) {
                    schemaBuilder.required(required);
                }
                builder.parameters(schemaBuilder.build());
            }
        }

        return builder.build();
    }

    private JsonSchemaElement toJsonSchemaElement(String toolName, String path, Map<String, Object> paramSchema) {
        String type = stringValue(paramSchema.get("type"), toolName, path + ".type");
        String description = stringValue(paramSchema.get("description"), toolName, path + ".description");
        List<String> enumValues = stringList(paramSchema.get("enum"), toolName, path + ".enum");

        if (enumValues != null && !enumValues.isEmpty()) {
            JsonEnumSchema.Builder builder = JsonEnumSchema.builder().enumValues(enumValues);
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            return builder.build();
        }

        if (type == null) {
            type = "string";
        }

        switch (type) {
        case "string" -> {
            JsonStringSchema.Builder builder = JsonStringSchema.builder();
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            return builder.build();
        }
        case "integer" -> {
            JsonIntegerSchema.Builder builder = JsonIntegerSchema.builder();
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            return builder.build();
        }
        case "number" -> {
            JsonNumberSchema.Builder builder = JsonNumberSchema.builder();
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            return builder.build();
        }
        case "boolean" -> {
            JsonBooleanSchema.Builder builder = JsonBooleanSchema.builder();
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            return builder.build();
        }
        case "array" -> {
            JsonArraySchema.Builder builder = JsonArraySchema.builder();
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            if (paramSchema.containsKey("items")) {
                Map<String, Object> items = stringObjectMap(paramSchema.get("items"), toolName, path + ".items");
                if (items != null) {
                    builder.items(toJsonSchemaElement(toolName, path + ".items", items));
                }
            }
            return builder.build();
        }
        case "object" -> {
            JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            if (paramSchema.containsKey(SCHEMA_KEY_PROPERTIES)) {
                Map<String, Object> nestedProps = stringObjectMap(paramSchema.get(SCHEMA_KEY_PROPERTIES),
                        toolName, path + "." + SCHEMA_KEY_PROPERTIES);
                if (nestedProps != null) {
                    for (Map.Entry<String, Object> entry : nestedProps.entrySet()) {
                        Map<String, Object> nestedSchema = stringObjectMap(entry.getValue(), toolName,
                                path + "." + SCHEMA_KEY_PROPERTIES + "." + entry.getKey());
                        if (nestedSchema == null) {
                            log.warn("[LLM] Dropping invalid nested schema for tool '{}' at {}", toolName,
                                    path + "." + SCHEMA_KEY_PROPERTIES + "." + entry.getKey());
                            continue;
                        }
                        builder.addProperty(entry.getKey(), toJsonSchemaElement(toolName,
                                path + "." + SCHEMA_KEY_PROPERTIES + "." + entry.getKey(), nestedSchema));
                    }
                }
            }
            return builder.build();
        }
        default -> {
            JsonStringSchema.Builder builder = JsonStringSchema.builder();
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            return builder.build();
        }
        }
    }

    @SuppressWarnings({
            "unchecked",
            "PMD.ReturnEmptyCollectionRatherThanNull"
    })
    private Map<String, Object> stringObjectMap(Object rawValue, String toolName, String path) {
        if (!(rawValue instanceof Map<?, ?> rawMap)) {
            if (rawValue != null) {
                log.warn("[LLM] Invalid schema object for tool '{}' at {}: {}", toolName, path,
                        rawValue.getClass().getSimpleName());
            }
            return null;
        }
        Map<String, Object> casted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                log.warn("[LLM] Dropping non-string schema key for tool '{}' at {}", toolName, path);
                continue;
            }
            casted.put(key, (Object) entry.getValue());
        }
        return casted;
    }

    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private List<String> stringList(Object rawValue, String toolName, String path) {
        if (!(rawValue instanceof List<?> rawList)) {
            if (rawValue != null) {
                log.warn("[LLM] Invalid schema list for tool '{}' at {}: {}", toolName, path,
                        rawValue.getClass().getSimpleName());
            }
            return null;
        }
        List<String> values = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (item instanceof String stringValue && !stringValue.isBlank()) {
                values.add(stringValue);
            } else {
                log.warn("[LLM] Dropping non-string schema list item for tool '{}' at {}", toolName, path);
            }
        }
        return values;
    }

    private String stringValue(Object rawValue, String toolName, String path) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof String stringValue) {
            return stringValue;
        }
        log.warn("[LLM] Invalid schema string for tool '{}' at {}: {}", toolName, path,
                rawValue.getClass().getSimpleName());
        return null;
    }
}
