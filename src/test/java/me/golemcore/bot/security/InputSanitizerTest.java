package me.golemcore.bot.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InputSanitizerTest {

    private InputSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new InputSanitizer();
    }

    @Test
    void normalizeUnicode_removesInvisibleCharacters() {
        String input = "Hello\u200BWorld"; // Zero-width space
        String result = sanitizer.normalizeUnicode(input);
        assertEquals("HelloWorld", result);
    }

    @Test
    void sanitize_handlesNull() {
        String result = sanitizer.sanitize(null);
        assertEquals("", result);
    }

    @Test
    void sanitize_appliesFullPipeline() {
        String input = "Hello <script>keep</script>\u200B World";
        String result = sanitizer.sanitize(input);
        assertTrue(result.contains("<script>keep</script>"));
        assertFalse(result.contains("\u200B"));
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("World"));
    }
}
