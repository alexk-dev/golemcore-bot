package me.golemcore.bot.adapter.outbound.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalWorkspaceFileAdapterTest {

    @TempDir
    private Path tempDir;

    @Test
    void shouldDelegateWorkspaceFileOperationsToFileSystem() throws Exception {
        LocalWorkspaceFileAdapter adapter = new LocalWorkspaceFileAdapter();
        Path directory = tempDir.resolve("workspace");
        Path textFile = directory.resolve("notes.txt");
        Path binaryFile = directory.resolve("payload.bin");
        Path movedFile = directory.resolve("moved.bin");

        adapter.createDirectories(directory);
        adapter.writeString(textFile, "hello");
        adapter.write(binaryFile, "bytes".getBytes(StandardCharsets.UTF_8));

        assertTrue(adapter.exists(textFile));
        assertEquals("hello", adapter.readString(textFile));
        assertArrayEquals("bytes".getBytes(StandardCharsets.UTF_8), adapter.readAllBytes(binaryFile));
        assertEquals(5L, adapter.size(textFile));
        assertTrue(adapter.isRegularFile(textFile));
        assertTrue(adapter.isDirectory(directory));
        assertFalse(adapter.isDirectory(textFile));
        assertFalse(adapter.isRegularFile(directory));
        assertEquals(textFile.toRealPath(), adapter.resolveRealPath(textFile));
        assertDoesNotThrow(() -> adapter.probeContentType(textFile));
        assertDoesNotThrow(() -> adapter.getLastModifiedTime(textFile));

        List<Path> listed = adapter.list(directory);
        List<Path> walked = adapter.walk(directory);

        assertTrue(listed.contains(textFile));
        assertTrue(walked.contains(textFile));
        assertTrue(walked.contains(binaryFile));

        adapter.move(binaryFile, movedFile);
        assertFalse(adapter.exists(binaryFile));
        assertTrue(adapter.exists(movedFile));
        assertTrue(adapter.deleteIfExists(movedFile));
        assertFalse(adapter.deleteIfExists(movedFile));
        adapter.delete(textFile);
        assertFalse(adapter.exists(textFile));
    }

    @Test
    void shouldDetectSymbolicLinksWhenSupported() throws Exception {
        LocalWorkspaceFileAdapter adapter = new LocalWorkspaceFileAdapter();
        Path target = tempDir.resolve("target.txt");
        Path link = tempDir.resolve("link.txt");
        Files.writeString(target, "target");

        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | SecurityException exception) {
            return;
        }

        assertTrue(adapter.isSymbolicLink(link));
        assertFalse(adapter.isSymbolicLink(target));
    }
}
