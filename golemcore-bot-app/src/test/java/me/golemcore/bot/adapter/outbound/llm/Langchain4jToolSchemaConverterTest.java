package me.golemcore.bot.adapter.outbound.llm;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class Langchain4jToolSchemaConverterTest {

    private final Langchain4jToolSchemaConverter converter = new Langchain4jToolSchemaConverter();

    @Test
    void shouldPreserveTopLevelAndNestedRequiredFields() {
        ToolDefinition tool = ToolDefinition.builder()
                .name("emit_signal")
                .description("Emit a lifecycle signal")
                .inputSchema(Map.of(
                        "type", "object",
                        "required", List.of("signal_type", "summary"),
                        "properties", Map.of(
                                "signal_type", Map.of(
                                        "type", "string",
                                        "description", "Signal type"),
                                "summary", Map.of(
                                        "type", "string",
                                        "description", "Summary"),
                                "evidence_refs", Map.of(
                                        "type", "array",
                                        "items", Map.of(
                                                "type", "object",
                                                "required", List.of("kind", "ref"),
                                                "properties", Map.of(
                                                        "kind", Map.of("type", "string"),
                                                        "ref", Map.of("type", "string")))))))
                .build();

        ToolSpecification specification = converter.convertTools(LlmRequest.builder()
                .tools(List.of(tool))
                .build()).getFirst();

        JsonObjectSchema parameters = specification.parameters();
        assertEquals(List.of("signal_type", "summary"), parameters.required());
        assertInstanceOf(JsonStringSchema.class, parameters.properties().get("signal_type"));

        JsonArraySchema evidenceRefs = assertInstanceOf(JsonArraySchema.class,
                parameters.properties().get("evidence_refs"));
        JsonObjectSchema evidenceItem = assertInstanceOf(JsonObjectSchema.class, evidenceRefs.items());
        assertEquals(List.of("kind", "ref"), evidenceItem.required());
        assertEquals(Set.of("kind", "ref"), evidenceItem.properties().keySet());
    }

    @Test
    void shouldDropInvalidNestedPropertiesWithoutDroppingValidSiblings() {
        ToolDefinition tool = ToolDefinition.builder()
                .name("configure")
                .description("Configure runtime")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "config", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "valid", Map.of("type", "string"),
                                                "invalid", "not-a-schema-map")))))
                .build();

        ToolSpecification specification = converter.convertTools(LlmRequest.builder()
                .tools(List.of(tool))
                .build()).getFirst();

        JsonObjectSchema config = assertInstanceOf(JsonObjectSchema.class,
                specification.parameters().properties().get("config"));
        Map<String, JsonSchemaElement> nestedProperties = config.properties();
        assertEquals(List.of("valid"), nestedProperties.keySet().stream().toList());
        assertInstanceOf(JsonStringSchema.class, nestedProperties.get("valid"));
    }
}
