package me.golemcore.bot.domain.tools.workspace;

import me.golemcore.bot.port.outbound.WorkspaceFilePort;
import me.golemcore.bot.port.outbound.WorkspaceSettingsPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspacePathServiceTest {

    @TempDir
    Path tempDir;

    private Path workspaceRoot;
    private WorkspacePathService service;

    @BeforeEach
    void setUp() {
        workspaceRoot = tempDir.resolve("workspace");
        service = new WorkspacePathService(
                () -> new WorkspaceSettingsPort.WorkspaceSettings(workspaceRoot.toString(), workspaceRoot.toString()),
                new LocalTestWorkspaceFilePort());
        service.init();
    }

    @Test
    void shouldInitializeWorkspaceRootAndCreateDirectory() {
        assertEquals(workspaceRoot.toAbsolutePath().normalize(), service.getWorkspaceRoot());
        assertTrue(Files.isDirectory(workspaceRoot));
    }

    @Test
    void shouldResolveNullBlankAndTrimmedRelativePathsInsideWorkspace() {
        assertEquals(workspaceRoot.toAbsolutePath().normalize(), service.resolveSafePath(null));
        assertEquals(workspaceRoot.toAbsolutePath().normalize(), service.resolveSafePath(" "));
        assertEquals(workspaceRoot.resolve("notes/report.txt").toAbsolutePath().normalize(),
                service.resolveSafePath(" notes/report.txt "));
    }

    @Test
    void shouldRejectAbsoluteTraversalAndInvalidPaths() {
        assertThrows(IllegalArgumentException.class, () -> service.resolveSafePath("/tmp/file.txt"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveSafePath("\\tmp\\file.txt"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveSafePath("../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveSafePath("nested/..\\outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveSafePath("bad\u0000path"));
    }

    @Test
    void shouldRejectExistingSymlinkThatEscapesWorkspace() throws IOException {
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Path link = workspaceRoot.resolve("escape");
        Files.createSymbolicLink(link, outside);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.resolveSafePath("escape"));

        assertEquals("Path must be inside workspace", exception.getMessage());
    }

    @Test
    void shouldConvertWorkspacePathToSlashSeparatedRelativePath() {
        assertEquals("nested/report.txt",
                service.toRelativePath(workspaceRoot.resolve("nested").resolve("report.txt")));
    }

    @Test
    void shouldRequireFileName() {
        assertEquals("report.txt", service.requireFileName(workspaceRoot.resolve("report.txt")));
    }

    @Test
    void shouldRejectPathWithoutFileName() {
        assertThrows(IllegalStateException.class, () -> service.requireFileName(Path.of("/")));
    }

    @Test
    void shouldUseRequestedMimeTypeWhenProvided() {
        assertEquals("text/custom", service.resolveMimeType(workspaceRoot.resolve("report.bin"), " text/custom "));
    }

    @Test
    void shouldResolveMimeTypeFromKnownExtensionsWhenProbeIsUnavailable() {
        assertEquals("image/png", service.resolveMimeType(workspaceRoot.resolve("image.png"), null));
        assertEquals("image/jpeg", service.resolveMimeType(workspaceRoot.resolve("image.jpeg"), ""));
        assertEquals("image/webp", service.resolveMimeType(workspaceRoot.resolve("image.webp"), null));
        assertEquals("application/pdf", service.resolveMimeType(workspaceRoot.resolve("report.pdf"), null));
        assertEquals("application/octet-stream", service.resolveMimeType(workspaceRoot.resolve("data.unknown"), null));
    }

    @Test
    void shouldFailFastWhenWorkspaceDirectoryCannotBeCreated() throws IOException {
        WorkspaceFilePort failingPort = new LocalTestWorkspaceFilePort() {
            @Override
            public void createDirectories(Path path) throws IOException {
                throw new IOException("denied");
            }
        };
        WorkspacePathService broken = new WorkspacePathService(
                () -> new WorkspaceSettingsPort.WorkspaceSettings(workspaceRoot.toString(), workspaceRoot.toString()),
                failingPort);

        IllegalStateException exception = assertThrows(IllegalStateException.class, broken::init);

        assertEquals("Failed to initialize workspace root", exception.getMessage());
        assertSame(IOException.class, exception.getCause().getClass());
    }
}
