package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.BrowserComponent;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.BrowserPage;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserToolTest {

    private BrowserComponent browserComponent;
    private BotProperties properties;
    private BrowserTool tool;

    @BeforeEach
    void setUp() {
        browserComponent = mock(BrowserComponent.class);
        properties = new BotProperties();
        properties.getBrowser().setEnabled(true);
        tool = new BrowserTool(browserComponent, properties);
    }

    // ===== getDefinition =====

    @Test
    void shouldReturnValidDefinition() {
        var def = tool.getDefinition();
        assertEquals("browse", def.getName());
        assertNotNull(def.getDescription());
        assertNotNull(def.getInputSchema());
    }

    // ===== isEnabled =====

    @Test
    void shouldBeEnabledWhenConfigEnabled() {
        assertTrue(tool.isEnabled());
    }

    @Test
    void shouldBeDisabledWhenConfigDisabled() {
        properties.getBrowser().setEnabled(false);
        assertFalse(tool.isEnabled());
    }

    // ===== URL validation =====

    @Test
    void shouldFailWhenUrlIsNull() {
        ToolResult result = tool.execute(Map.of()).join();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("URL is required"));
    }

    @Test
    void shouldFailWhenUrlIsBlank() {
        ToolResult result = tool.execute(Map.of("url", "  ")).join();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("URL is required"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "javascript:alert(1)", "data:text/html,<h1>test</h1>", "file:///etc/passwd" })
    void shouldRejectDangerousUrlSchemes(String url) {
        ToolResult result = tool.execute(Map.of("url", url)).join();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Only http and https URLs are allowed"));
    }

    @Test
    void shouldRejectCustomScheme() {
        ToolResult result = tool.execute(Map.of("url", "ftp://example.com")).join();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Only http and https URLs are allowed"));
    }

    @Test
    void shouldPrependHttpsWhenNoScheme() {
        BrowserPage page = BrowserPage.builder()
                .url("https://example.com")
                .title("Example")
                .text("Hello")
                .build();
        when(browserComponent.navigate("https://example.com"))
                .thenReturn(CompletableFuture.completedFuture(page));

        ToolResult result = tool.execute(Map.of("url", "example.com")).join();

        assertTrue(result.isSuccess());
        verify(browserComponent).navigate("https://example.com");
    }

    // ===== Text mode (default) =====

    @Test
    void shouldExtractTextByDefault() {
        BrowserPage page = BrowserPage.builder()
                .url("https://example.com")
                .title("Example Domain")
                .text("This is an example page.")
                .build();
        when(browserComponent.navigate("https://example.com"))
                .thenReturn(CompletableFuture.completedFuture(page));

        ToolResult result = tool.execute(Map.of("url", "https://example.com")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Example Domain"));
        assertTrue(result.getOutput().contains("This is an example page."));
    }

    @Test
    void shouldTruncateLongText() {
        String longText = "A".repeat(20000);
        BrowserPage page = BrowserPage.builder()
                .url("https://example.com")
                .title("Test")
                .text(longText)
                .build();
        when(browserComponent.navigate("https://example.com"))
                .thenReturn(CompletableFuture.completedFuture(page));

        ToolResult result = tool.execute(Map.of("url", "https://example.com")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("(truncated)"));
        assertTrue(result.getOutput().length() < longText.length());
    }

    // ===== HTML mode =====

    @Test
    void shouldReturnHtmlInHtmlMode() {
        String html = "<html><body>Hello</body></html>";
        when(browserComponent.getHtml("https://example.com"))
                .thenReturn(CompletableFuture.completedFuture(html));

        ToolResult result = tool.execute(Map.of("url", "https://example.com", "mode", "html")).join();

        assertTrue(result.isSuccess());
        assertEquals(html, result.getOutput());
    }

    @Test
    void shouldTruncateLongHtml() {
        String longHtml = "<p>" + "B".repeat(25000) + "</p>";
        when(browserComponent.getHtml("https://example.com"))
                .thenReturn(CompletableFuture.completedFuture(longHtml));

        ToolResult result = tool.execute(Map.of("url", "https://example.com", "mode", "html")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("(truncated)"));
    }

    // ===== Screenshot mode =====

    @Test
    void shouldReturnScreenshotWithAttachment() {
        byte[] screenshotBytes = new byte[] { 0x50, 0x4E, 0x47 };
        when(browserComponent.screenshot("https://example.com"))
                .thenReturn(CompletableFuture.completedFuture(screenshotBytes));

        ToolResult result = tool.execute(Map.of("url", "https://example.com", "mode", "screenshot")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Screenshot captured"));
        assertNotNull(result.getData());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertTrue(data.containsKey("attachment"));

        Attachment attachment = (Attachment) data.get("attachment");
        assertEquals(Attachment.Type.IMAGE, attachment.getType());
        assertEquals("screenshot.png", attachment.getFilename());
        assertEquals("image/png", attachment.getMimeType());
        assertArrayEquals(screenshotBytes, attachment.getData());
    }

    // ===== Error handling =====

    @Test
    void shouldReturnFailureOnNavigationError() {
        when(browserComponent.navigate("https://broken.example.com"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Connection refused")));

        ToolResult result = tool.execute(Map.of("url", "https://broken.example.com")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Failed to browse page"));
    }

    @Test
    void shouldReturnFailureOnScreenshotError() {
        when(browserComponent.screenshot("https://example.com"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Browser crashed")));

        ToolResult result = tool.execute(Map.of("url", "https://example.com", "mode", "screenshot")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Failed to browse page"));
    }

    @Test
    void shouldAcceptHttpUrl() {
        BrowserPage page = BrowserPage.builder()
                .url("http://example.com")
                .title("Example")
                .text("Test")
                .build();
        when(browserComponent.navigate("http://example.com"))
                .thenReturn(CompletableFuture.completedFuture(page));

        ToolResult result = tool.execute(Map.of("url", "http://example.com")).join();

        assertTrue(result.isSuccess());
    }
}
