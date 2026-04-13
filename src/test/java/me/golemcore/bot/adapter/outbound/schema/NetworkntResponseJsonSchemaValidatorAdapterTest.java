package me.golemcore.bot.adapter.outbound.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkntResponseJsonSchemaValidatorAdapterTest {

    private NetworkntResponseJsonSchemaValidatorAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new NetworkntResponseJsonSchemaValidatorAdapter(new ObjectMapper());
    }

    @Test
    void shouldAcceptDraft202012ResponseSchema() {
        Map<String, Object> schema = Map.of(
                "$schema", "https://json-schema.org/draft/2020-12/schema",
                "type", "object",
                "required", List.of("version"),
                "properties", Map.of(
                        "version", Map.of("type", "string", "const", "1.0")));

        assertDoesNotThrow(() -> adapter.validateResponseJsonSchema(schema));
    }

    @Test
    void shouldRejectInvalidResponseSchemaType() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> adapter.validateResponseJsonSchema(Map.of("type", "invalid")));

        assertTrue(error.getMessage().contains("Invalid responseJsonSchema"));
    }
}
