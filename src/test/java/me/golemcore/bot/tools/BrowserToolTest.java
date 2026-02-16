package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.BrowserComponent;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.BrowserPage;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserToolTest {

    private static final String EXAMPLE_URL = "https://example.com";
    private static final String URL = "url";
    private static final String MODE = "mode";

    private BrowserComponent browserComponent;
    private RuntimeConfigService runtimeConfigService;
    private BrowserTool tool;

    @BeforeEach
    void setUp() {
        browserComponent = mock(BrowserComponent.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isBrowserEnabled()).thenReturn(true);
        tool = new BrowserTool(browserComponent, runtimeConfigService);
    }

    // ===== isEnabled =====

    @Test
    void shouldBeEnabledWhenConfigEnabled() {
        assertTrue(tool.isEnabled());
    }

    @Test
    void shouldBeDisabledWhenConfigDisabled() {
        when(runtimeConfigService.isBrowserEnabled()).thenReturn(false);
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
        ToolResult result = tool.execute(Map.of(URL, "  ")).join();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("URL is required"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "javascript:alert(1)", "data:text/html,<h1>test</h1>", "file:///etc/passwd" })
    void shouldRejectDangerousUrlSchemes(String dangerousUrl) {
        ToolResult result = tool.execute(Map.of(URL, dangerousUrl)).join();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Only http and https URLs are allowed"));
    }

    @Test
    void shouldRejectCustomScheme() {
        ToolResult result = tool.execute(Map.of(URL, "ftp://example.com")).join();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Only http and https URLs are allowed"));
    }

    @Test
    void shouldPrependHttpsWhenNoScheme() {
        BrowserPage page = BrowserPage.builder()
                .url(EXAMPLE_URL)
                .title("Example")
                .text("Hello")
                .build();
        when(browserComponent.navigate(EXAMPLE_URL))
                .thenReturn(CompletableFuture.completedFuture(page));

        ToolResult result = tool.execute(Map.of(URL, "example.com")).join();

        assertTrue(result.isSuccess());
        verify(browserComponent).navigate(EXAMPLE_URL);
    }

    // ===== Text mode (default) =====

    @Test
    void shouldExtractTextByDefault() {
        BrowserPage page = BrowserPage.builder()
                .url(EXAMPLE_URL)
                .title("Example Domain")
                .text("This is an example page.")
                .build();
        when(browserComponent.navigate(EXAMPLE_URL))
                .thenReturn(CompletableFuture.completedFuture(page));

        ToolResult result = tool.execute(Map.of(URL, EXAMPLE_URL)).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Example Domain"));
        assertTrue(result.getOutput().contains("This is an example page."));
    }

    @Test
    void shouldTruncateLongText() {
        String longText = "A".repeat(20000);
        BrowserPage page = BrowserPage.builder()
                .url(EXAMPLE_URL)
                .title("Test")
                .text(longText)
                .build();
        when(browserComponent.navigate(EXAMPLE_URL))
                .thenReturn(CompletableFuture.completedFuture(page));

        ToolResult result = tool.execute(Map.of(URL, EXAMPLE_URL)).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("(truncated)"));
        assertTrue(result.getOutput().length() < longText.length());
    }

    // ===== HTML mode =====

    @Test
    void shouldReturnHtmlInHtmlMode() {
        String html = "<html><body>Hello</body></html>";
        when(browserComponent.getHtml(EXAMPLE_URL))
                .thenReturn(CompletableFuture.completedFuture(html));

        ToolResult result = tool.execute(Map.of(URL, EXAMPLE_URL, MODE, "html")).join();

        assertTrue(result.isSuccess());
        assertEquals(html, result.getOutput());
    }

    @Test
    void shouldTruncateLongHtml() {
        String longHtml = "<p>" + "B".repeat(25000) + "</p>";
        when(browserComponent.getHtml(EXAMPLE_URL))
                .thenReturn(CompletableFuture.completedFuture(longHtml));

        ToolResult result = tool.execute(Map.of(URL, EXAMPLE_URL, MODE, "html")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("(truncated)"));
    }

    // ===== Screenshot mode =====

    @Test
    void shouldReturnScreenshotWithAttachment() {
        byte[] screenshotBytes = new byte[] { 0x50, 0x4E, 0x47 };
        when(browserComponent.screenshot(EXAMPLE_URL))
                .thenReturn(CompletableFuture.completedFuture(screenshotBytes));

        ToolResult result = tool.execute(Map.of(URL, EXAMPLE_URL, MODE, "screenshot")).join();

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

        ToolResult result = tool.execute(Map.of(URL, "https://broken.example.com")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Failed to browse page"));
    }

    @Test
    void shouldReturnFailureOnScreenshotError() {
        when(browserComponent.screenshot(EXAMPLE_URL))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Browser crashed")));

        ToolResult result = tool.execute(Map.of(URL, EXAMPLE_URL, MODE, "screenshot")).join();

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

        ToolResult result = tool.execute(Map.of(URL, "http://example.com")).join();

        assertTrue(result.isSuccess());
    }
}
