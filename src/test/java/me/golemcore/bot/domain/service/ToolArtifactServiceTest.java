package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.ToolArtifact;
import me.golemcore.bot.domain.model.ToolArtifactDownload;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolArtifactServiceTest {

    @TempDir
    Path tempDir;

    private ToolArtifactService toolArtifactService;
    private Path workspaceRoot;

    @BeforeEach
    void setUp() {
        workspaceRoot = tempDir.resolve("workspace");

        BotProperties botProperties = new BotProperties();
        botProperties.getTools().getFilesystem().setWorkspace(workspaceRoot.toString());

        WorkspacePathService workspacePathService = new WorkspacePathService(
                me.golemcore.bot.support.TestPorts.settings(botProperties));
        workspacePathService.init();
        toolArtifactService = new ToolArtifactService(workspacePathService);
    }

    @Test
    void shouldSaveArtifactUnderHiddenWorkspaceDirectory() {
        byte[] data = new byte[] { 1, 2, 3, 4 };

        ToolArtifact stored = toolArtifactService.saveArtifact(
                "session:42",
                "browserless_smart_scrape",
                "Monthly Report.pdf",
                data,
                "application/pdf");

        assertTrue(stored.getPath().startsWith(".golemcore/tool-artifacts/session_42/browserless_smart_scrape/"));
        assertTrue(stored.getPath().endsWith("/Monthly_Report.pdf"));
        assertEquals("Monthly_Report.pdf", stored.getFilename());
        assertEquals("application/pdf", stored.getMimeType());
        assertEquals(4L, stored.getSize());
        assertTrue(stored.getDownloadUrl().startsWith("/api/files/download?path="));
        assertTrue(Files.exists(workspaceRoot.resolve(stored.getPath())));
    }

    @Test
    void shouldNormalizeSegmentsAndFilenameWithoutRegexPostProcessing() {
        byte[] data = new byte[] { 1, 2, 3 };

        ToolArtifact stored = toolArtifactService.saveArtifact(
                "..__session///42--",
                "__browserless:::smart scrape--",
                ".___Monthly   Report__--",
                data,
                "application/pdf");

        assertTrue(stored.getPath().startsWith(
                ".golemcore/tool-artifacts/session_42/browserless_smart_scrape/"));
        assertTrue(stored.getPath().endsWith("/Monthly_Report.pdf"));
        assertEquals("Monthly_Report.pdf", stored.getFilename());
    }

    @Test
    void shouldReadStoredArtifactDownload() {
        byte[] data = new byte[] { 9, 8, 7 };
        ToolArtifact stored = toolArtifactService.saveArtifact(
                "session-1",
                "browserless_smart_scrape",
                "capture.png",
                data,
                "image/png");

        ToolArtifactDownload download = toolArtifactService.getDownload(stored.getPath());

        assertEquals(stored.getPath(), download.getPath());
        assertEquals("capture.png", download.getFilename());
        assertEquals("image/png", download.getMimeType());
        assertEquals(3L, download.getSize());
        assertArrayEquals(data, download.getData());
    }

    @Test
    void shouldBuildThumbnailBase64ForStoredImageArtifact() throws IOException {
        ToolArtifact stored = toolArtifactService.saveArtifact(
                "session-1",
                "pinchtab_screenshot",
                "capture.png",
                createPngImage(640, 320),
                "image/png");

        String thumbnailBase64 = toolArtifactService.buildThumbnailBase64(stored.getPath());

        assertNotNull(thumbnailBase64);
        assertTrue(thumbnailBase64.length() > 100);
    }

    @Test
    void shouldSkipThumbnailGenerationForNonImageArtifacts() {
        ToolArtifact stored = toolArtifactService.saveArtifact(
                "session-1",
                "report_tool",
                "report.pdf",
                new byte[] { 1, 2, 3 },
                "application/pdf");

        assertNull(toolArtifactService.buildThumbnailBase64(stored.getPath()));
    }

    @ParameterizedTest
    @MethodSource("invalidDownloadPaths")
    void shouldRejectInvalidPathsWhenDownloading(String path) {
        assertThrows(IllegalArgumentException.class, () -> toolArtifactService.getDownload(path));
    }

    @Test
    void shouldRejectWorkspaceFilesOutsideArtifactDirectory() throws IOException {
        Path notes = workspaceRoot.resolve("notes.txt");
        Files.createDirectories(workspaceRoot);
        Files.writeString(notes, "hello", StandardCharsets.UTF_8);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> toolArtifactService.getDownload("notes.txt"));

        assertTrue(exception.getMessage().contains("not a tool artifact"));
    }

    private static Stream<String> invalidDownloadPaths() {
        return Stream.of(
                null,
                "",
                " ",
                "../etc/passwd",
                "..\\windows\\system32",
                "/tmp/file",
                "\\tmp\\file");
    }

    private byte[] createPngImage(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, (x + y) % 2 == 0 ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
