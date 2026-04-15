package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookResponseSchemaLayerTest {

    private final WebhookResponseSchemaLayer layer = new WebhookResponseSchemaLayer();

    @Test
    void shouldApplyWhenWebhookMessageCarriesResponseSchema() {
        AgentContext context = AgentContext.builder()
                .messages(List.of(Message.builder()
                        .role("user")
                        .metadata(Map.of(ContextAttributes.WEBHOOK_RESPONSE_JSON_SCHEMA_TEXT, schemaText()))
                        .build()))
                .build();

        ContextLayerResult result = layer.assemble(context);

        assertTrue(layer.appliesTo(context));
        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("Webhook Response JSON Contract"));
        assertTrue(result.getContent().contains("\"version\""));
    }

    @Test
    void shouldApplyWhenSchemaTextIsCarriedByContextAttribute() {
        AgentContext context = AgentContext.builder()
                .messages(List.of(Message.builder().role("user").build()))
                .build();
        context.setAttribute(ContextAttributes.WEBHOOK_RESPONSE_JSON_SCHEMA_TEXT, schemaText());

        ContextLayerResult result = layer.assemble(context);

        assertTrue(layer.appliesTo(context));
        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("Webhook Response JSON Contract"));
        assertTrue(result.getContent().contains("\"version\""));
    }

    @Test
    void shouldSkipWhenNoResponseSchemaIsPresent() {
        AgentContext context = AgentContext.builder()
                .messages(List.of(Message.builder().role("user").build()))
                .build();

        assertFalse(layer.appliesTo(context));
        assertFalse(layer.assemble(context).hasContent());
    }

    @Test
    void shouldSkipWhenContextHasNoUsableSchemaText() {
        AgentContext emptyContext = AgentContext.builder()
                .messages(List.of())
                .build();
        AgentContext blankSchemaContext = AgentContext.builder()
                .messages(List.of(Message.builder()
                        .role("user")
                        .metadata(Map.of(ContextAttributes.WEBHOOK_RESPONSE_JSON_SCHEMA_TEXT, " "))
                        .build()))
                .build();

        assertFalse(layer.appliesTo(null));
        assertFalse(layer.appliesTo(emptyContext));
        assertFalse(layer.appliesTo(blankSchemaContext));
    }

    private String schemaText() {
        return """
                {
                  "type" : "object",
                  "properties" : {
                    "version" : {
                      "const" : "1.0"
                    }
                  }
                }
                """;
    }
}
