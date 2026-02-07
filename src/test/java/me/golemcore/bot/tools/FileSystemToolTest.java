package me.golemcore.bot.tools;

import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.security.InjectionGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileSystemToolTest {

    @TempDir
    Path tempDir;

    private FileSystemTool tool;

    @BeforeEach
    void setUp() {
        BotProperties properties = createTestProperties(tempDir.toString());
        tool = new FileSystemTool(properties, new InjectionGuard());
    }

    private static BotProperties createTestProperties(String workspace) {
        BotProperties properties = new BotProperties();
        properties.getTools().getFilesystem().setEnabled(true);
        properties.getTools().getFilesystem().setWorkspace(workspace);
        return properties;
    }

    @Test
    void writeAndReadFile() throws Exception {
        // Write file
        Map<String, Object> writeParams = Map.of(
                "operation", "write_file",
                "path", "test.txt",
                "content", "Hello, World!");

        ToolResult writeResult = tool.execute(writeParams).get();
        assertTrue(writeResult.isSuccess());
        assertTrue(writeResult.getOutput().contains("written to"));

        // Read file
        Map<String, Object> readParams = Map.of(
                "operation", "read_file",
                "path", "test.txt");

        ToolResult readResult = tool.execute(readParams).get();
        assertTrue(readResult.isSuccess());
        assertEquals("Hello, World!", readResult.getOutput());
    }

    @Test
    void appendToFile() throws Exception {
        // Write initial content
        tool.execute(Map.of(
                "operation", "write_file",
                "path", "append.txt",
                "content", "Line 1\n")).get();

        // Append content
        ToolResult appendResult = tool.execute(Map.of(
                "operation", "write_file",
                "path", "append.txt",
                "content", "Line 2\n",
                "append", true)).get();

        assertTrue(appendResult.isSuccess());

        // Read and verify
        ToolResult readResult = tool.execute(Map.of(
                "operation", "read_file",
                "path", "append.txt")).get();

        assertEquals("Line 1\nLine 2\n", readResult.getOutput());
    }

    @Test
    void listDirectory() throws Exception {
        // Create some files
        Files.writeString(tempDir.resolve("file1.txt"), "content1");
        Files.writeString(tempDir.resolve("file2.txt"), "content2");
        Files.createDirectory(tempDir.resolve("subdir"));

        Map<String, Object> params = Map.of(
                "operation", "list_directory",
                "path", ".");

        ToolResult result = tool.execute(params).get();
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("file1.txt"));
        assertTrue(result.getOutput().contains("file2.txt"));
        assertTrue(result.getOutput().contains("subdir"));
    }

    @Test
    void createDirectory() throws Exception {
        Map<String, Object> params = Map.of(
                "operation", "create_directory",
                "path", "new/nested/dir");

        ToolResult result = tool.execute(params).get();
        assertTrue(result.isSuccess());
        assertTrue(Files.isDirectory(tempDir.resolve("new/nested/dir")));
    }

    @Test
    void deleteFile() throws Exception {
        // Create file
        Files.writeString(tempDir.resolve("to_delete.txt"), "delete me");

        Map<String, Object> params = Map.of(
                "operation", "delete",
                "path", "to_delete.txt");

        ToolResult result = tool.execute(params).get();
        assertTrue(result.isSuccess());
        assertFalse(Files.exists(tempDir.resolve("to_delete.txt")));
    }

    @Test
    void deleteDirectoryRecursively() throws Exception {
        // Create directory with contents
        Path dir = tempDir.resolve("to_delete_dir");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("file.txt"), "content");
        Files.writeString(dir.resolve("sub/nested.txt"), "nested");

        Map<String, Object> params = Map.of(
                "operation", "delete",
                "path", "to_delete_dir");

        ToolResult result = tool.execute(params).get();
        assertTrue(result.isSuccess());
        assertFalse(Files.exists(dir));
    }

    @Test
    void fileInfo() throws Exception {
        Files.writeString(tempDir.resolve("info.txt"), "test content");

        Map<String, Object> params = Map.of(
                "operation", "file_info",
                "path", "info.txt");

        ToolResult result = tool.execute(params).get();
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Type: File"));
        assertTrue(result.getOutput().contains("Size:"));
    }

    @Test
    void pathTraversalBlocked() throws Exception {
        Map<String, Object> params = Map.of(
                "operation", "read_file",
                "path", "../../../etc/passwd");

        ToolResult result = tool.execute(params).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("path traversal") ||
                result.getError().contains("Invalid path"));
    }

    @Test
    void readNonExistentFile() throws Exception {
        Map<String, Object> params = Map.of(
                "operation", "read_file",
                "path", "nonexistent.txt");

        ToolResult result = tool.execute(params).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("not found"));
    }

    @Test
    void missingParameters() throws Exception {
        // Missing operation
        ToolResult result1 = tool.execute(Map.of("path", "test.txt")).get();
        assertFalse(result1.isSuccess());

        // Missing path
        ToolResult result2 = tool.execute(Map.of("operation", "read_file")).get();
        assertFalse(result2.isSuccess());
    }

    @Test
    void disabledTool() throws Exception {
        BotProperties disabledProps = createTestProperties(tempDir.toString());
        disabledProps.getTools().getFilesystem().setEnabled(false);
        FileSystemTool disabledTool = new FileSystemTool(disabledProps, new InjectionGuard());

        ToolResult result = disabledTool.execute(Map.of(
                "operation", "read_file",
                "path", "test.txt")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("disabled"));
    }

    // ===== send_file Tests =====

    @Test
    void sendFileWithImage() throws Exception {
        byte[] pngBytes = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 };
        Files.write(tempDir.resolve("image.png"), pngBytes);

        ToolResult result = tool.execute(Map.of(
                "operation", "send_file",
                "path", "image.png")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("image.png"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertArrayEquals(pngBytes, (byte[]) data.get("file_bytes"));
        assertEquals("image.png", data.get("filename"));
        assertEquals("image/png", data.get("mime_type"));
        assertEquals(Attachment.Type.IMAGE, data.get("type"));
    }

    @Test
    void sendFileWithDocument() throws Exception {
        byte[] pdfBytes = new byte[] { 0x25, 0x50, 0x44, 0x46 };
        Files.write(tempDir.resolve("report.pdf"), pdfBytes);

        ToolResult result = tool.execute(Map.of(
                "operation", "send_file",
                "path", "report.pdf")).get();

        assertTrue(result.isSuccess());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("application/pdf", data.get("mime_type"));
        assertEquals(Attachment.Type.DOCUMENT, data.get("type"));
    }

    @Test
    void sendFileNonExistent() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "send_file",
                "path", "does_not_exist.txt")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("not found"));
    }

    @Test
    void sendFileDirectory() throws Exception {
        Files.createDirectory(tempDir.resolve("adir"));

        ToolResult result = tool.execute(Map.of(
                "operation", "send_file",
                "path", "adir")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Not a file"));
    }

    @Test
    void sendFileUnknownExtension() throws Exception {
        Files.write(tempDir.resolve("data.xyz"), new byte[] { 1, 2 });

        ToolResult result = tool.execute(Map.of(
                "operation", "send_file",
                "path", "data.xyz")).get();

        assertTrue(result.isSuccess());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("application/octet-stream", data.get("mime_type"));
        assertEquals(Attachment.Type.DOCUMENT, data.get("type"));
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
    void toolDefinitionIncludesSendFile() {
        String desc = tool.getDefinition().getDescription();
        assertTrue(desc.contains("send_file"));

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) tool.getDefinition().getInputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> opProp = (Map<String, Object>) props.get("operation");
        @SuppressWarnings("unchecked")
        java.util.List<String> enumValues = (java.util.List<String>) opProp.get("enum");
        assertTrue(enumValues.contains("send_file"));
    }
}
