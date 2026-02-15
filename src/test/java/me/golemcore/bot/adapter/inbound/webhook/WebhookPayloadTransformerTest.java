package me.golemcore.bot.adapter.inbound.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WebhookPayloadTransformerTest {

    private static final String RAW_JSON = "{\"raw\":\"data\"}";

    private WebhookPayloadTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new WebhookPayloadTransformer();
    }

    @Test
    void shouldResolveSimplePlaceholders() {
        String template = "Push to {repository} by {user}";
        byte[] body = "{\"repository\":\"myapp\",\"user\":\"alex\"}".getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Push to myapp by alex", result);
    }

    @Test
    void shouldResolveNestedPlaceholders() {
        String template = "Push to {repository.name} by {pusher.name}";
        byte[] body = "{\"repository\":{\"name\":\"myapp\"},\"pusher\":{\"name\":\"alex\"}}"
                .getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Push to myapp by alex", result);
    }

    @Test
    void shouldReplaceMissingFieldsWithMarker() {
        String template = "Event: {action} on {missing.field}";
        byte[] body = "{\"action\":\"push\"}".getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Event: push on <missing>", result);
    }

    @Test
    void shouldReturnRawBodyWhenTemplateIsNull() {
        byte[] body = RAW_JSON.getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(null, body);

        assertEquals(RAW_JSON, result);
    }

    @Test
    void shouldReturnRawBodyWhenTemplateIsBlank() {
        byte[] body = RAW_JSON.getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform("  ", body);

        assertEquals(RAW_JSON, result);
    }

    @Test
    void shouldReturnTemplateWhenBodyIsInvalidJson() {
        String template = "Event: {action}";
        byte[] body = "not-json".getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Event: {action}", result);
    }

    @Test
    void shouldHandleNumericValues() {
        String template = "Stars: {stargazers_count}";
        byte[] body = "{\"stargazers_count\":42}".getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Stars: 42", result);
    }

    @Test
    void shouldHandleTemplateWithNoPlaceholders() {
        String template = "Static message with no placeholders";
        byte[] body = "{\"data\":\"ignored\"}".getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Static message with no placeholders", result);
    }
}
