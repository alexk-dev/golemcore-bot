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

    // ==================== Special characters in values ====================

    @Test
    void shouldHandleDollarSignInValue() {
        String template = "Price: {price}";
        byte[] body = "{\"price\":\"$100\"}".getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Price: $100", result);
    }

    @Test
    void shouldHandleBackslashInValue() {
        String template = "Path: {path}";
        byte[] body = "{\"path\":\"C:\\\\Users\\\\test\"}".getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Path: C:\\Users\\test", result);
    }

    @Test
    void shouldHandleRegexMetacharactersInValue() {
        String template = "Pattern: {pattern}";
        byte[] body = "{\"pattern\":\"$1 and \\\\1 replacement\"}".getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Pattern: $1 and \\1 replacement", result);
    }

    @Test
    void shouldHandleMultiplePlaceholdersWithSpecialChars() {
        String template = "Commit {ref} by {user}: {message}";
        byte[] body = "{\"ref\":\"$HEAD\",\"user\":\"dev\\\\team\",\"message\":\"Fix $100 bug\"}"
                .getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Commit $HEAD by dev\\team: Fix $100 bug", result);
    }

    // ==================== Array and object values ====================

    @Test
    void shouldHandleArrayValue() {
        String template = "Tags: {tags}";
        byte[] body = "{\"tags\":[\"v1.0\",\"release\"]}".getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Tags: [\"v1.0\",\"release\"]", result);
    }

    @Test
    void shouldHandleObjectValue() {
        String template = "Config: {config}";
        byte[] body = "{\"config\":{\"key\":\"value\"}}".getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Config: {\"key\":\"value\"}", result);
    }

    @Test
    void shouldHandleBooleanValue() {
        String template = "Active: {active}";
        byte[] body = "{\"active\":true}".getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Active: true", result);
    }

    @Test
    void shouldHandleNullValue() {
        String template = "Value: {value}";
        byte[] body = "{\"value\":null}".getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Value: null", result);
    }

    // ==================== Edge cases ====================

    @Test
    void shouldHandleEmptyStringValue() {
        String template = "Name: {name}";
        byte[] body = "{\"name\":\"\"}".getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Name: ", result);
    }

    @Test
    void shouldHandleDeeplyNestedPath() {
        String template = "Value: {a.b.c.d}";
        byte[] body = "{\"a\":{\"b\":{\"c\":{\"d\":\"deep\"}}}}".getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("Value: deep", result);
    }

    @Test
    void shouldHandleArrayIndexInPath() {
        // Arrays accessed via numeric index are not supported - should return <missing>
        String template = "First: {items.0}";
        byte[] body = "{\"items\":[\"one\",\"two\"]}".getBytes(StandardCharsets.UTF_8);

        String result = transformer.transform(template, body);

        assertEquals("First: <missing>", result);
    }
}
