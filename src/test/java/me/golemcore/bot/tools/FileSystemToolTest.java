package me.golemcore.bot.tools;

import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.plugin.builtin.security.InjectionGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileSystemToolTest {

    private static final String OPERATION = "operation";
    private static final String PATH = "path";
    private static final String CONTENT = "content";
    private static final String READ_FILE = "read_file";
    private static final String WRITE_FILE = "write_file";
    private static final String SEND_FILE = "send_file";
    private static final String NOT_FOUND = "not found";
    private static final String TEST_TXT = "test.txt";
    private static final String MIME_TYPE = "mime_type";
    private static final String TYPE = "type";
    private static final String ADIR = "adir";
    private static final String IMAGE_PNG = "image.png";
    private static final String SUPPRESS_UNCHECKED = "unchecked";

    @TempDir
    Path tempDir;

    private FileSystemTool tool;
    private RuntimeConfigService runtimeConfigService;

    @BeforeEach
    void setUp() {
        BotProperties properties = createTestProperties(tempDir.toString());
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isFilesystemEnabled()).thenReturn(true);
        when(runtimeConfigService.isPromptInjectionDetectionEnabled()).thenReturn(true);
        when(runtimeConfigService.isCommandInjectionDetectionEnabled()).thenReturn(true);
        tool = new FileSystemTool(properties, runtimeConfigService, new InjectionGuard(runtimeConfigService));
    }

    private static BotProperties createTestProperties(String workspace) {
        BotProperties properties = new BotProperties();
        properties.getTools().getFilesystem().setWorkspace(workspace);
        return properties;
    }

    @Test
    void writeAndReadFile() throws Exception {
        // Write file
        Map<String, Object> writeParams = Map.of(
                OPERATION, WRITE_FILE,
                PATH, TEST_TXT,
                CONTENT, "Hello, World!");

        ToolResult writeResult = tool.execute(writeParams).get();
        assertTrue(writeResult.isSuccess());
        assertTrue(writeResult.getOutput().contains("written to"));

        // Read file
        Map<String, Object> readParams = Map.of(
                OPERATION, READ_FILE,
                PATH, TEST_TXT);

        ToolResult readResult = tool.execute(readParams).get();
        assertTrue(readResult.isSuccess());
        assertEquals("Hello, World!", readResult.getOutput());
    }

    @Test
    void appendToFile() throws Exception {
        // Write initial content
        tool.execute(Map.of(
                OPERATION, WRITE_FILE,
                PATH, "append.txt",
                CONTENT, "Line 1\n")).get();

        // Append content
        ToolResult appendResult = tool.execute(Map.of(
                OPERATION, WRITE_FILE,
                PATH, "append.txt",
                CONTENT, "Line 2\n",
                "append", true)).get();

        assertTrue(appendResult.isSuccess());

        // Read and verify
        ToolResult readResult = tool.execute(Map.of(
                OPERATION, READ_FILE,
                PATH, "append.txt")).get();

        assertEquals("Line 1\nLine 2\n", readResult.getOutput());
    }

    @Test
    void listDirectory() throws Exception {
        // Create some files
        Files.writeString(tempDir.resolve("file1.txt"), "content1");
        Files.writeString(tempDir.resolve("file2.txt"), "content2");
        Files.createDirectory(tempDir.resolve("subdir"));

        Map<String, Object> params = Map.of(
                OPERATION, "list_directory",
                PATH, ".");

        ToolResult result = tool.execute(params).get();
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("file1.txt"));
        assertTrue(result.getOutput().contains("file2.txt"));
        assertTrue(result.getOutput().contains("subdir"));
    }

    @Test
    void createDirectory() throws Exception {
        Map<String, Object> params = Map.of(
                OPERATION, "create_directory",
                PATH, "new/nested/dir");

        ToolResult result = tool.execute(params).get();
        assertTrue(result.isSuccess());
        assertTrue(Files.isDirectory(tempDir.resolve("new/nested/dir")));
    }

    @Test
    void deleteFile() throws Exception {
        // Create file
        Files.writeString(tempDir.resolve("to_delete.txt"), "delete me");

        Map<String, Object> params = Map.of(
                OPERATION, "delete",
                PATH, "to_delete.txt");

        ToolResult result = tool.execute(params).get();
        assertTrue(result.isSuccess());
        assertFalse(Files.exists(tempDir.resolve("to_delete.txt")));
    }

    @Test
    void deleteDirectoryRecursively() throws Exception {
        // Create directory with contents
        Path dir = tempDir.resolve("to_delete_dir");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("file.txt"), CONTENT);
        Files.writeString(dir.resolve("sub/nested.txt"), "nested");

        Map<String, Object> params = Map.of(
                OPERATION, "delete",
                PATH, "to_delete_dir");

        ToolResult result = tool.execute(params).get();
        assertTrue(result.isSuccess());
        assertFalse(Files.exists(dir));
    }

    @Test
    void fileInfo() throws Exception {
        Files.writeString(tempDir.resolve("info.txt"), "test content");

        Map<String, Object> params = Map.of(
                OPERATION, "file_info",
                PATH, "info.txt");

        ToolResult result = tool.execute(params).get();
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Type: File"));
        assertTrue(result.getOutput().contains("Size:"));
    }

    @Test
    void pathTraversalBlocked() throws Exception {
        Map<String, Object> params = Map.of(
                OPERATION, READ_FILE,
                PATH, "../../../etc/passwd");

        ToolResult result = tool.execute(params).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("path traversal") ||
                result.getError().contains("Invalid path"));
    }

    @Test
    void readNonExistentFile() throws Exception {
        Map<String, Object> params = Map.of(
                OPERATION, READ_FILE,
                PATH, "nonexistent.txt");

        ToolResult result = tool.execute(params).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(NOT_FOUND));
    }

    @Test
    void missingParameters() throws Exception {
        // Missing operation
        ToolResult result1 = tool.execute(Map.of(PATH, TEST_TXT)).get();
        assertFalse(result1.isSuccess());

        // Missing path
        ToolResult result2 = tool.execute(Map.of(OPERATION, READ_FILE)).get();
        assertFalse(result2.isSuccess());
    }

    @Test
    void disabledTool() throws Exception {
        BotProperties disabledProps = createTestProperties(tempDir.toString());
        // enabled is now controlled via RuntimeConfigService
        RuntimeConfigService disabledRuntimeConfigService = mock(RuntimeConfigService.class);
        when(disabledRuntimeConfigService.isFilesystemEnabled()).thenReturn(false);
        when(disabledRuntimeConfigService.isPromptInjectionDetectionEnabled()).thenReturn(true);
        when(disabledRuntimeConfigService.isCommandInjectionDetectionEnabled()).thenReturn(true);
        FileSystemTool disabledTool = new FileSystemTool(
                disabledProps,
                disabledRuntimeConfigService,
                new InjectionGuard(disabledRuntimeConfigService));

        ToolResult result = disabledTool.execute(Map.of(
                OPERATION, READ_FILE,
                PATH, TEST_TXT)).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("disabled"));
    }

    // ===== send_file Tests =====

    @Test
    void sendFileWithImage() throws Exception {
        byte[] pngBytes = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 };
        Files.write(tempDir.resolve(IMAGE_PNG), pngBytes);

        ToolResult result = tool.execute(Map.of(
                OPERATION, SEND_FILE,
                PATH, IMAGE_PNG)).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains(IMAGE_PNG));

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertArrayEquals(pngBytes, (byte[]) data.get("file_bytes"));
        assertEquals(IMAGE_PNG, data.get("filename"));
        assertEquals("image/png", data.get(MIME_TYPE));
        assertEquals(Attachment.Type.IMAGE, data.get(TYPE));
    }

    @Test
    void sendFileWithDocument() throws Exception {
        byte[] pdfBytes = new byte[] { 0x25, 0x50, 0x44, 0x46 };
        Files.write(tempDir.resolve("report.pdf"), pdfBytes);

        ToolResult result = tool.execute(Map.of(
                OPERATION, SEND_FILE,
                PATH, "report.pdf")).get();

        assertTrue(result.isSuccess());

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("application/pdf", data.get(MIME_TYPE));
        assertEquals(Attachment.Type.DOCUMENT, data.get(TYPE));
    }

    @Test
    void sendFileNonExistent() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, SEND_FILE,
                PATH, "does_not_exist.txt")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(NOT_FOUND));
    }

    @Test
    void sendFileDirectory() throws Exception {
        Files.createDirectory(tempDir.resolve(ADIR));

        ToolResult result = tool.execute(Map.of(
                OPERATION, SEND_FILE,
                PATH, ADIR)).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Not a file"));
    }

    @Test
    void sendFileUnknownExtension() throws Exception {
        Files.write(tempDir.resolve("data.xyz"), new byte[] { 1, 2 });

        ToolResult result = tool.execute(Map.of(
                OPERATION, SEND_FILE,
                PATH, "data.xyz")).get();

        assertTrue(result.isSuccess());

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("application/octet-stream", data.get(MIME_TYPE));
        assertEquals(Attachment.Type.DOCUMENT, data.get(TYPE));
    }

    @Test
    void detectMimeTypes() {
        assertEquals("image/png", FileSystemTool.detectMimeType("photo.png"));
        assertEquals("image/jpeg", FileSystemTool.detectMimeType("photo.jpg"));
        assertEquals("image/jpeg", FileSystemTool.detectMimeType("photo.jpeg"));
        assertEquals("application/pdf", FileSystemTool.detectMimeType("doc.pdf"));
        assertEquals("text/csv", FileSystemTool.detectMimeType("data.csv"));
        assertEquals("application/zip", FileSystemTool.detectMimeType("archive.zip"));
        assertEquals("text/plain", FileSystemTool.detectMimeType("readme.txt"));
        assertEquals("application/json", FileSystemTool.detectMimeType("config.json"));
        assertEquals("application/octet-stream", FileSystemTool.detectMimeType("noext"));
        assertEquals("application/octet-stream", FileSystemTool.detectMimeType("unknown.abc"));
    }

    @Test
    void sendFileWithCaption() throws Exception {
        Files.write(tempDir.resolve("audio.mp3"), new byte[] { 0x49, 0x44 });

        ToolResult result = tool.execute(Map.of(
                OPERATION, SEND_FILE,
                PATH, "audio.mp3")).get();

        assertTrue(result.isSuccess());

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("audio/mpeg", data.get(MIME_TYPE));
        assertEquals(Attachment.Type.DOCUMENT, data.get(TYPE));
    }

    // ===== write_file edge cases =====

    @Test
    void shouldFailWriteWithoutContent() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, WRITE_FILE,
                PATH, TEST_TXT)).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Missing content"));
    }

    @Test
    void shouldCreateParentDirectoriesOnWrite() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, WRITE_FILE,
                PATH, "deep/nested/dir/file.txt",
                CONTENT, "hello")).get();
        assertTrue(result.isSuccess());
        assertTrue(Files.exists(tempDir.resolve("deep/nested/dir/file.txt")));
    }

    // ===== read_file edge cases =====

    @Test
    void shouldFailReadOnDirectory() throws Exception {
        Files.createDirectory(tempDir.resolve(ADIR));
        ToolResult result = tool.execute(Map.of(
                OPERATION, READ_FILE,
                PATH, ADIR)).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Not a file"));
    }

    // ===== list_directory edge cases =====

    @Test
    void shouldFailListOnNonExistentDir() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, "list_directory",
                PATH, "nonexistent")).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(NOT_FOUND));
    }

    @Test
    void shouldFailListOnFile() throws Exception {
        Files.writeString(tempDir.resolve("file.txt"), "hello");
        ToolResult result = tool.execute(Map.of(
                OPERATION, "list_directory",
                PATH, "file.txt")).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Not a directory"));
    }

    // ===== create_directory edge cases =====

    @Test
    void shouldSucceedWhenDirectoryAlreadyExists() throws Exception {
        Files.createDirectory(tempDir.resolve("existing"));
        ToolResult result = tool.execute(Map.of(
                OPERATION, "create_directory",
                PATH, "existing")).get();
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("already exists"));
    }

    @Test
    void shouldFailCreateDirWhenFileExists() throws Exception {
        Files.writeString(tempDir.resolve("afile"), CONTENT);
        ToolResult result = tool.execute(Map.of(
                OPERATION, "create_directory",
                PATH, "afile")).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("not a directory"));
    }

    // ===== delete edge cases =====

    @Test
    void shouldFailDeleteNonExistent() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, "delete",
                PATH, "nonexistent")).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(NOT_FOUND));
    }

    // ===== file_info edge cases =====

    @Test
    void shouldReturnDirectoryInfo() throws Exception {
        Files.createDirectory(tempDir.resolve("infodir"));
        ToolResult result = tool.execute(Map.of(
                OPERATION, "file_info",
                PATH, "infodir")).get();
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Directory"));
    }

    @Test
    void shouldFailInfoOnNonExistent() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, "file_info",
                PATH, "nonexistent")).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(NOT_FOUND));
    }

    // ===== unknown operation =====

    @Test
    void shouldFailOnUnknownOperation() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, "unknown_op",
                PATH, TEST_TXT)).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Unknown operation"));
    }

    // ===== getDefinition =====

    @Test
    void shouldReturnValidDefinition() {
        assertNotNull(tool.getDefinition());
        assertEquals("filesystem", tool.getDefinition().getName());
        assertNotNull(tool.getDefinition().getDescription());
        assertNotNull(tool.getDefinition().getInputSchema());
    }

    // ===== isEnabled =====

    @Test
    void shouldBeEnabled() {
        assertTrue(tool.isEnabled());
    }

    // ===== Additional MIME type tests =====

    @Test
    void shouldDetectWebpMimeType() {
        assertEquals("image/webp", FileSystemTool.detectMimeType("image.webp"));
    }

    @Test
    void shouldDetectYamlMimeType() {
        assertEquals("text/yaml", FileSystemTool.detectMimeType("config.yml"));
        assertEquals("text/yaml", FileSystemTool.detectMimeType("config.yaml"));
    }

    @Test
    void shouldDetectCodeMimeTypes() {
        assertEquals("text/x-python", FileSystemTool.detectMimeType("script.py"));
        assertEquals("text/x-java", FileSystemTool.detectMimeType("Main.java"));
        assertEquals("text/javascript", FileSystemTool.detectMimeType("app.js"));
        assertEquals("text/typescript", FileSystemTool.detectMimeType("app.ts"));
    }

    @Test
    void shouldDetectArchiveMimeTypes() {
        assertEquals("application/x-tar", FileSystemTool.detectMimeType("archive.tar"));
        assertEquals("application/gzip", FileSystemTool.detectMimeType("archive.gz"));
    }

    @Test
    void shouldDetectMediaMimeTypes() {
        assertEquals("audio/mpeg", FileSystemTool.detectMimeType("song.mp3"));
        assertEquals("video/mp4", FileSystemTool.detectMimeType("video.mp4"));
        assertEquals("audio/wav", FileSystemTool.detectMimeType("sound.wav"));
    }

    @Test
    void toolDefinitionIncludesSendFile() {
        String desc = tool.getDefinition().getDescription();
        assertTrue(desc.contains("send_file"));

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        Map<String, Object> schema = (Map<String, Object>) tool.getDefinition().getInputSchema();
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        Map<String, Object> opProp = (Map<String, Object>) props.get(OPERATION);
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        java.util.List<String> enumValues = (java.util.List<String>) opProp.get("enum");
        assertTrue(enumValues.contains("send_file"));
    }
}
