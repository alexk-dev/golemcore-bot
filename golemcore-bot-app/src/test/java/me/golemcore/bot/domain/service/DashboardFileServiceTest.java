package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.DashboardFileContent;
import me.golemcore.bot.domain.model.DashboardFileNode;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DashboardFileServiceTest {

    @TempDir
    Path tempDir;

    private DashboardFileService dashboardFileService;
    private Path workspaceRoot;

    @BeforeEach
    void setUp() {
        workspaceRoot = tempDir.resolve("workspace");

        BotProperties botProperties = new BotProperties();
        botProperties.getTools().getFilesystem().setWorkspace(workspaceRoot.toString());

        WorkspacePathService workspacePathService = new WorkspacePathService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                new LocalTestWorkspaceFilePort());
        workspacePathService.init();
        dashboardFileService = new DashboardFileService(workspacePathService, new LocalTestWorkspaceFilePort());
    }

    @Test
    void shouldInitializeWorkspaceDirectoryOnInit() {
        assertTrue(Files.exists(workspaceRoot));
        assertTrue(Files.isDirectory(workspaceRoot));
    }

    @Test
    void shouldReturnSortedTreeWithDirectoriesFirstAndCaseInsensitiveOrder() throws IOException {
        writeTextFile("z-file.txt", "z");
        writeTextFile("B-file.txt", "b");
        Files.createDirectories(workspaceRoot.resolve("b-folder"));
        Files.createDirectories(workspaceRoot.resolve("A-folder"));

        List<DashboardFileNode> tree = dashboardFileService.getTree("");

        assertEquals(4, tree.size());
        assertEquals("A-folder", tree.get(0).getName());
        assertEquals("b-folder", tree.get(1).getName());
        assertEquals("B-file.txt", tree.get(2).getName());
        assertEquals("z-file.txt", tree.get(3).getName());
        assertEquals("directory", tree.get(0).getType());
        assertEquals("file", tree.get(2).getType());
    }

    @Test
    void shouldReturnRootTreeWhenPathIsNull() throws IOException {
        writeTextFile("src/App.tsx", "app");

        List<DashboardFileNode> tree = dashboardFileService.getTree(null);

        assertEquals(1, tree.size());
        assertEquals("src", tree.get(0).getName());
    }

    @Test
    void shouldSkipSymbolicLinksInTree() throws IOException {
        assumeTrue(isSymlinkSupported(), "Symlinks are not supported in this environment");

        writeTextFile("visible.txt", "ok");
        Path outsideTarget = tempDir.resolve("outside.txt");
        Files.writeString(outsideTarget, "outside", StandardCharsets.UTF_8);

        Path symlink = workspaceRoot.resolve("outside-link.txt");
        Files.createSymbolicLink(symlink, outsideTarget);

        List<DashboardFileNode> tree = dashboardFileService.getTree("");

        assertEquals(1, tree.size());
        assertEquals("visible.txt", tree.get(0).getName());
    }

    @Test
    void shouldThrowWhenTreePathDoesNotExist() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.getTree("missing"));

        assertTrue(exception.getMessage().contains("Path not found"));
    }

    @Test
    void shouldThrowWhenTreePathIsFile() throws IOException {
        writeTextFile("single.txt", "content");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.getTree("single.txt"));

        assertTrue(exception.getMessage().contains("not a directory"));
    }

    @Test
    void shouldReadUtf8ContentAndMetadata() throws IOException {
        writeTextFile("src/App.tsx", "export default function App() {}\n");

        DashboardFileContent content = dashboardFileService.getContent("src/App.tsx");

        assertEquals("src/App.tsx", content.getPath());
        assertEquals("export default function App() {}\n", content.getContent());
        assertEquals("export default function App() {}\n".getBytes(StandardCharsets.UTF_8).length, content.getSize());
        assertNotNull(content.getUpdatedAt());
        Instant.parse(content.getUpdatedAt());
    }

    @ParameterizedTest
    @MethodSource("blankPaths")
    void shouldThrowWhenContentPathIsBlank(String path) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.getContent(path));

        assertEquals("Path is required", exception.getMessage());
    }

    @Test
    void shouldThrowWhenContentPathDoesNotExist() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.getContent("src/missing.ts"));

        assertTrue(exception.getMessage().contains("File not found"));
    }

    @Test
    void shouldThrowWhenContentPathIsDirectory() throws IOException {
        Files.createDirectories(workspaceRoot.resolve("src"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.getContent("src"));

        assertTrue(exception.getMessage().contains("Not a file"));
    }

    @Test
    void shouldThrowWhenFileIsTooLargeForEditor() throws IOException {
        String oversizedContent = "a".repeat((2 * 1024 * 1024) + 1);
        writeTextFile("large.txt", oversizedContent);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.getContent("large.txt"));

        assertTrue(exception.getMessage().contains("File too large"));
    }

    @Test
    void shouldThrowWhenFileIsNotUtf8Text() throws IOException {
        Path binaryFile = workspaceRoot.resolve("bad.txt");
        Files.write(binaryFile, new byte[] { (byte) 0xC3, (byte) 0x28 });

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.getContent("bad.txt"));

        assertTrue(exception.getMessage().contains("valid UTF-8"));
    }

    @Test
    void shouldCreateFileWithParentDirectoriesWhenPathIsValid() {
        DashboardFileContent created = dashboardFileService.createContent("nested/dir/NewFile.ts", "console.log('ok')");

        assertEquals("nested/dir/NewFile.ts", created.getPath());
        assertEquals("console.log('ok')", created.getContent());
        assertTrue(Files.exists(workspaceRoot.resolve("nested/dir/NewFile.ts")));
        assertNotNull(created.getUpdatedAt());
    }

    @Test
    void shouldCreateFileWithEmptyContentWhenContentIsNull() throws IOException {
        DashboardFileContent created = dashboardFileService.createContent("empty.txt", null);

        assertEquals("", created.getContent());
        assertEquals(0L, created.getSize());
        assertEquals("", Files.readString(workspaceRoot.resolve("empty.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void shouldThrowWhenCreatePathAlreadyExists() throws IOException {
        writeTextFile("exists.txt", "existing");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.createContent("exists.txt", "new"));

        assertTrue(exception.getMessage().contains("already exists"));
    }

    @ParameterizedTest
    @MethodSource("blankPaths")
    void shouldThrowWhenCreatePathIsBlank(String path) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.createContent(path, "x"));

        assertEquals("Path is required", exception.getMessage());
    }

    @ParameterizedTest
    @MethodSource("invalidPaths")
    void shouldRejectInvalidPathsWhenCreatingContent(String path) {
        assertThrows(IllegalArgumentException.class, () -> dashboardFileService.createContent(path, "x"));
    }

    @Test
    void shouldSaveAndOverwriteExistingFile() throws IOException {
        writeTextFile("src/App.tsx", "old");

        DashboardFileContent saved = dashboardFileService.saveContent("src/App.tsx", "new-content");

        assertEquals("src/App.tsx", saved.getPath());
        assertEquals("new-content", saved.getContent());
        assertEquals("new-content", Files.readString(workspaceRoot.resolve("src/App.tsx"), StandardCharsets.UTF_8));
    }

    @Test
    void shouldCreateParentDirectoriesWhenSavingNewFile() {
        DashboardFileContent saved = dashboardFileService.saveContent("deep/tree/new.txt", "value");

        assertEquals("deep/tree/new.txt", saved.getPath());
        assertTrue(Files.exists(workspaceRoot.resolve("deep/tree/new.txt")));
    }

    @ParameterizedTest
    @MethodSource("blankPaths")
    void shouldThrowWhenSavePathIsBlank(String path) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.saveContent(path, "x"));

        assertEquals("Path is required", exception.getMessage());
    }

    @Test
    void shouldThrowWhenSaveContentIsNull() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.saveContent("file.txt", null));

        assertEquals("Content is required", exception.getMessage());
    }

    @ParameterizedTest
    @MethodSource("invalidPaths")
    void shouldRejectInvalidPathsWhenSavingContent(String path) {
        assertThrows(IllegalArgumentException.class, () -> dashboardFileService.saveContent(path, "x"));
    }

    @Test
    void shouldRenameFileAndCreateTargetParentDirectories() throws IOException {
        writeTextFile("src/Old.ts", "old");

        dashboardFileService.renamePath("src/Old.ts", "target/deep/New.ts");

        assertFalse(Files.exists(workspaceRoot.resolve("src/Old.ts")));
        assertTrue(Files.exists(workspaceRoot.resolve("target/deep/New.ts")));
        assertEquals("old", Files.readString(workspaceRoot.resolve("target/deep/New.ts"), StandardCharsets.UTF_8));
    }

    @Test
    void shouldRenameDirectoryRecursively() throws IOException {
        writeTextFile("src/module/file.ts", "module");

        dashboardFileService.renamePath("src/module", "src/module-renamed");

        assertFalse(Files.exists(workspaceRoot.resolve("src/module")));
        assertTrue(Files.exists(workspaceRoot.resolve("src/module-renamed/file.ts")));
    }

    @Test
    void shouldThrowWhenRenameSourceDoesNotExist() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.renamePath("missing.txt", "new.txt"));

        assertTrue(exception.getMessage().contains("Source path not found"));
    }

    @Test
    void shouldThrowWhenRenameTargetAlreadyExists() throws IOException {
        writeTextFile("from.txt", "from");
        writeTextFile("to.txt", "to");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.renamePath("from.txt", "to.txt"));

        assertTrue(exception.getMessage().contains("Target path already exists"));
    }

    @Test
    void shouldThrowWhenRenameSourceAndTargetAreSamePath() throws IOException {
        writeTextFile("same.txt", "value");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.renamePath("same.txt", "same.txt"));

        assertTrue(exception.getMessage().contains("Target path already exists"));
    }

    @Test
    void shouldThrowWhenRenamingWorkspaceRoot() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.renamePath(".", "renamed"));

        assertTrue(exception.getMessage().contains("Workspace root cannot be renamed"));
    }

    @ParameterizedTest
    @MethodSource("blankPaths")
    void shouldThrowWhenRenameSourcePathIsBlank(String sourcePath) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.renamePath(sourcePath, "target.txt"));

        assertEquals("Source path is required", exception.getMessage());
    }

    @ParameterizedTest
    @MethodSource("blankPaths")
    void shouldThrowWhenRenameTargetPathIsBlank(String targetPath) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.renamePath("source.txt", targetPath));

        assertEquals("Target path is required", exception.getMessage());
    }

    @ParameterizedTest
    @MethodSource("invalidPaths")
    void shouldRejectInvalidPathsWhenRenamingSource(String sourcePath) {
        assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.renamePath(sourcePath, "target.txt"));
    }

    @ParameterizedTest
    @MethodSource("invalidPaths")
    void shouldRejectInvalidPathsWhenRenamingTarget(String targetPath) throws IOException {
        writeTextFile("source.txt", "content");
        assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.renamePath("source.txt", targetPath));
    }

    @Test
    void shouldDeleteFileWhenPathExists() throws IOException {
        writeTextFile("delete-me.txt", "x");

        dashboardFileService.deletePath("delete-me.txt");

        assertFalse(Files.exists(workspaceRoot.resolve("delete-me.txt")));
    }

    @Test
    void shouldDeleteDirectoryRecursively() throws IOException {
        writeTextFile("to-delete/deep/file-1.txt", "1");
        writeTextFile("to-delete/deep/file-2.txt", "2");

        dashboardFileService.deletePath("to-delete");

        assertFalse(Files.exists(workspaceRoot.resolve("to-delete")));
    }

    @ParameterizedTest
    @MethodSource("blankPaths")
    void shouldThrowWhenDeletePathIsBlank(String path) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.deletePath(path));

        assertEquals("Path is required", exception.getMessage());
    }

    @Test
    void shouldThrowWhenDeletePathDoesNotExist() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.deletePath("missing"));

        assertTrue(exception.getMessage().contains("Path not found"));
    }

    @Test
    void shouldThrowWhenDeletingWorkspaceRoot() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.deletePath("."));

        assertTrue(exception.getMessage().contains("Workspace root cannot be deleted"));
    }

    @ParameterizedTest
    @MethodSource("invalidPaths")
    void shouldRejectInvalidPathsWhenDeleting(String path) {
        assertThrows(IllegalArgumentException.class, () -> dashboardFileService.deletePath(path));
    }

    @Test
    void shouldRejectPathThroughSymlinkParentThatPointsOutsideWorkspace() throws IOException {
        assumeTrue(isSymlinkSupported(), "Symlinks are not supported in this environment");

        Path outsideDir = tempDir.resolve("outside");
        Files.createDirectories(outsideDir);

        Path symlinkDir = workspaceRoot.resolve("linked-out");
        Files.createSymbolicLink(symlinkDir, outsideDir);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.createContent("linked-out/escape.txt", "x"));

        assertTrue(exception.getMessage().contains("inside workspace"));
    }

    @Test
    void shouldReturnLazyTreeWithDirectoryChildHintsWhenDepthIsOne() throws IOException {
        writeTextFile("src/main/App.java", "class App {}");
        writeTextFile("README.md", "docs");

        List<DashboardFileNode> tree = dashboardFileService.getTree("", 1, false);

        DashboardFileNode src = tree.stream()
                .filter(node -> "src".equals(node.getPath()))
                .findFirst()
                .orElseThrow();
        assertEquals("directory", src.getType());
        assertTrue(src.isHasChildren());
        assertTrue(src.getChildren().isEmpty());

        DashboardFileNode readme = tree.stream()
                .filter(node -> "README.md".equals(node.getPath()))
                .findFirst()
                .orElseThrow();
        assertEquals("file", readme.getType());
        assertEquals("text/markdown", readme.getMimeType());
        assertFalse(readme.isBinary());
        assertFalse(readme.isImage());
    }

    @Test
    void shouldHideGeneratedAndIgnoredDirectoriesByDefault() throws IOException {
        writeTextFile("src/App.java", "class App {}");
        writeTextFile("node_modules/pkg/index.js", "module.exports = {}");
        writeTextFile("target/classes/App.class", "compiled");
        writeTextFile(".git/config", "[core]");

        List<DashboardFileNode> tree = dashboardFileService.getTree("", 1, false);

        List<String> names = tree.stream().map(DashboardFileNode::getName).toList();
        assertEquals(List.of("src"), names);
    }

    @Test
    void shouldIncludeGeneratedDirectoriesWhenRequested() throws IOException {
        writeTextFile("src/App.java", "class App {}");
        writeTextFile("node_modules/pkg/index.js", "module.exports = {}");
        writeTextFile("target/classes/App.class", "compiled");

        List<DashboardFileNode> tree = dashboardFileService.getTree("", 1, true);

        List<String> names = tree.stream().map(DashboardFileNode::getName).toList();
        assertTrue(names.contains("src"));
        assertTrue(names.contains("node_modules"));
        assertTrue(names.contains("target"));
    }

    @ParameterizedTest
    @MethodSource("extensionOnlyTextPaths")
    void shouldTreatKnownTextExtensionsAsEditableWhenProbeFallsBackToOctetStream(String path) throws IOException {
        writeTextFile(path, "content");

        DashboardFileContent content = dashboardFileService.getContent(path);

        assertEquals("content", content.getContent());
        assertFalse(content.isBinary());
        assertFalse(content.isImage());
        assertTrue(content.isEditable());
    }

    @ParameterizedTest
    @MethodSource("nonEditableMimeTypes")
    void shouldReturnBinaryMetadataForNonEditableFiles(String path, byte[] data, String expectedMimeType)
            throws IOException {
        Path file = workspaceRoot.resolve(path);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, data);

        DashboardFileContent content = dashboardFileService.getContent(path);

        assertEquals(path, content.getPath());
        assertEquals(expectedMimeType, content.getMimeType());
        assertEquals(data.length, content.getSize());
        assertEquals(null, content.getContent());
        assertTrue(content.isBinary());
        assertFalse(content.isEditable());
    }

    @ParameterizedTest
    @MethodSource("invalidUploadFilenames")
    void shouldRejectInvalidUploadFilenames(String filename) {
        byte[] data = "uploaded".getBytes(StandardCharsets.UTF_8);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.uploadFile("uploads", filename, data, "text/plain"));

        assertEquals("Filename is required", exception.getMessage());
    }

    @Test
    void shouldSanitizeUploadFilenameToLeafName() {
        byte[] data = "uploaded".getBytes(StandardCharsets.UTF_8);

        DashboardFileContent uploaded = dashboardFileService.uploadFile("uploads", "../notes.txt", data,
                "text/plain");

        assertEquals("uploads/notes.txt", uploaded.getPath());
        assertTrue(Files.exists(workspaceRoot.resolve("uploads/notes.txt")));
    }

    @Test
    void shouldUploadFileIntoWorkspaceRootWhenTargetDirectoryIsNull() {
        byte[] data = "uploaded".getBytes(StandardCharsets.UTF_8);

        DashboardFileContent uploaded = dashboardFileService.uploadFile(null, "root.txt", data, "text/plain");

        assertEquals("root.txt", uploaded.getPath());
        assertEquals("uploaded", uploaded.getContent());
    }

    @Test
    void shouldRejectEditableUploadWhenDataIsNotUtf8() {
        byte[] data = new byte[] { (byte) 0xC3, (byte) 0x28 };

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.uploadFile("uploads", "bad.txt", data, "text/plain"));

        assertTrue(exception.getMessage().contains("valid UTF-8"));
    }

    @Test
    void shouldReturnUploadedBinaryMetadataWhenUploadedFileIsNotEditable() {
        byte[] data = new byte[] { 1, 2, 3, 4 };

        DashboardFileContent uploaded = dashboardFileService.uploadFile("uploads", "archive.bin", data,
                "application/octet-stream");

        assertEquals("uploads/archive.bin", uploaded.getPath());
        assertEquals(null, uploaded.getContent());
        assertEquals("application/octet-stream", uploaded.getMimeType());
        assertTrue(uploaded.isBinary());
        assertFalse(uploaded.isImage());
        assertFalse(uploaded.isEditable());
    }

    @Test
    void shouldRejectNullUploadData() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.uploadFile("uploads", "notes.txt", null, "text/plain"));

        assertEquals("File data is required", exception.getMessage());
    }

    @Test
    void shouldThrowWhenDownloadPathIsBlank() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.getDownload(" "));

        assertEquals("Path is required", exception.getMessage());
    }

    @Test
    void shouldThrowWhenDownloadPathDoesNotExist() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.getDownload("missing.txt"));

        assertTrue(exception.getMessage().contains("File not found"));
    }

    @Test
    void shouldThrowWhenDownloadPathIsDirectory() throws IOException {
        Files.createDirectories(workspaceRoot.resolve("reports"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dashboardFileService.getDownload("reports"));

        assertTrue(exception.getMessage().contains("Not a file"));
    }

    @Test
    void shouldReturnImageMetadataWithoutReadingImageAsUtf8() throws IOException {
        byte[] pngBytes = new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D
        };
        Path path = workspaceRoot.resolve("images/logo.png");
        Files.createDirectories(path.getParent());
        Files.write(path, pngBytes);

        DashboardFileContent content = dashboardFileService.getContent("images/logo.png");

        assertEquals("images/logo.png", content.getPath());
        assertEquals(pngBytes.length, content.getSize());
        assertEquals("image/png", content.getMimeType());
        assertTrue(content.isBinary());
        assertTrue(content.isImage());
        assertFalse(content.isEditable());
        assertEquals("/api/files/download?path=images%2Flogo.png", content.getDownloadUrl());
    }

    @Test
    void shouldDownloadWorkspaceFileWhenPathIsValid() throws IOException {
        writeTextFile("reports/output.txt", "download me");

        me.golemcore.bot.domain.model.ToolArtifactDownload download = dashboardFileService
                .getDownload("reports/output.txt");

        assertEquals("reports/output.txt", download.getPath());
        assertEquals("output.txt", download.getFilename());
        assertEquals("text/plain", download.getMimeType());
        assertEquals("download me", new String(download.getData(), StandardCharsets.UTF_8));
    }

    @Test
    void shouldUploadFileIntoTargetDirectory() {
        byte[] data = "uploaded".getBytes(StandardCharsets.UTF_8);

        DashboardFileContent uploaded = dashboardFileService.uploadFile("uploads", "notes.txt", data, "text/plain");

        assertEquals("uploads/notes.txt", uploaded.getPath());
        assertEquals("uploaded", uploaded.getContent());
        assertEquals("text/plain", uploaded.getMimeType());
        assertTrue(uploaded.isEditable());
        assertTrue(Files.exists(workspaceRoot.resolve("uploads/notes.txt")));
    }

    private static Stream<String> extensionOnlyTextPaths() {
        return Stream.of(
                "src/App.java",
                "script.js",
                "component.jsx",
                "config.json",
                "docs/readme.yml",
                "docs/readme.yaml",
                "pom.xml",
                "index.html",
                "styles/main.css",
                "styles/main.scss",
                "bin/run.sh",
                "scripts/app.py",
                "cmd/main.go",
                "src/lib.rs",
                "src/Main.kt",
                "db/schema.sql",
                "config/app.toml",
                "config/app.ini");
    }

    private static Stream<Arguments> nonEditableMimeTypes() {
        return Stream.of(
                Arguments.of(
                        "assets/logo.jpg",
                        new byte[] { (byte) 0xFF, (byte) 0xD8, 0x00 },
                        "image/jpeg"),
                Arguments.of(
                        "assets/logo.webp",
                        new byte[] { 0x52, 0x49, 0x46, 0x46 },
                        "image/webp"),
                Arguments.of(
                        "docs/report.pdf",
                        new byte[] { 0x25, 0x50, 0x44, 0x46 },
                        "application/pdf"),
                Arguments.of(
                        "data/archive.bin",
                        new byte[] { 0x00, 0x01, 0x02 },
                        "application/octet-stream"));
    }

    private static Stream<String> invalidUploadFilenames() {
        return Stream.of("", " ", ".", "..", "nested/..", "nested/.");
    }

    private Path writeTextFile(String relativePath, String content) throws IOException {
        Path path = workspaceRoot.resolve(relativePath);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private boolean isSymlinkSupported() {
        Path target = tempDir.resolve("symlink-target");
        Path symlink = tempDir.resolve("symlink-probe");
        try {
            Files.createDirectories(target);
            Files.deleteIfExists(symlink);
            Files.createSymbolicLink(symlink, target);
            return Files.isSymbolicLink(symlink);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(symlink);
            } catch (IOException ignored) {
                // no-op in tests
            }
        }
    }

    private static Stream<String> blankPaths() {
        return Stream.of("", " ", "\t");
    }

    private static Stream<String> invalidPaths() {
        return Stream.of(
                "../escape.txt",
                "..\\escape.txt",
                "/etc/passwd",
                "\\windows\\system32");
    }
}
