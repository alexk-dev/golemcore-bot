package me.golemcore.bot.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillTemplateEngineTest {

    private static final String NO_VARS_TEXT = "No vars here";

    private SkillTemplateEngine engine;

    @BeforeEach
    void setUp() {
        engine = new SkillTemplateEngine();
    }

    @Test
    void render_simpleSubstitution() {
        String result = engine.render("Hello {{NAME}}", Map.of("NAME", "World"));
        assertEquals("Hello World", result);
    }

    @Test
    void render_multipleVariables() {
        String result = engine.render(
                "Connect to {{HOST}}:{{PORT}}",
                Map.of("HOST", "localhost", "PORT", "8080"));
        assertEquals("Connect to localhost:8080", result);
    }

    @Test
    void render_unresolvedLeftAsIs() {
        String result = engine.render("Key: {{API_KEY}}", Map.of());
        assertEquals("Key: {{API_KEY}}", result);
    }

    @Test
    void render_whitespaceInBraces() {
        String result = engine.render("Key: {{ API_KEY }}", Map.of("API_KEY", "abc123"));
        assertEquals("Key: abc123", result);
    }

    @Test
    void render_mixedResolvedAndUnresolved() {
        String result = engine.render(
                "Host: {{HOST}}, Key: {{KEY}}",
                Map.of("HOST", "example.com"));
        assertEquals("Host: example.com, Key: {{KEY}}", result);
    }

    @Test
    void render_nullContent() {
        assertNull(engine.render(null, Map.of("A", "B")));
    }

    @Test
    void render_emptyVars() {
        String result = engine.render(NO_VARS_TEXT, Map.of());
        assertEquals(NO_VARS_TEXT, result);
    }

    @Test
    void render_nullVars() {
        String result = engine.render(NO_VARS_TEXT, null);
        assertEquals(NO_VARS_TEXT, result);
    }

    @Test
    void render_specialCharsInValues() {
        String result = engine.render(
                "Pattern: {{REGEX}}",
                Map.of("REGEX", "$100.00 (20% off)"));
        assertEquals("Pattern: $100.00 (20% off)", result);
    }

    @Test
    void render_backslashInValues() {
        String result = engine.render(
                "Path: {{PATH}}",
                Map.of("PATH", "C:\\Users\\test"));
        assertEquals("Path: C:\\Users\\test", result);
    }

    @Test
    void render_adjacentPlaceholders() {
        String result = engine.render(
                "{{A}}{{B}}",
                Map.of("A", "Hello", "B", "World"));
        assertEquals("HelloWorld", result);
    }

    @Test
    void render_noPlaceholders() {
        String result = engine.render("Plain text content", Map.of("A", "B"));
        assertEquals("Plain text content", result);
    }
}
