package me.golemcore.bot.adapter.outbound.storage;

import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class LocalStorageAdapterTest {

    private static final String TEST_DIR = "test-dir";

    @TempDir
    Path tempDir;

    private LocalStorageAdapter storageAdapter;

    @BeforeEach
    void setUp() {
        BotProperties properties = new BotProperties();
        properties.getStorage().getLocal().setBasePath(tempDir.toString());

        storageAdapter = new LocalStorageAdapter(properties);
        storageAdapter.init();
    }

    @Test
    void putAndGetText() throws ExecutionException, InterruptedException {
        String directory = TEST_DIR;
        String path = "test-file.txt";
        String content = "Hello, World!";

        storageAdapter.putText(directory, path, content).get();
        String retrieved = storageAdapter.getText(directory, path).get();

        assertEquals(content, retrieved);
    }

    @Test
    void putAndGetObject() throws ExecutionException, InterruptedException {
        String directory = TEST_DIR;
        String path = "test-file.bin";
        byte[] content = new byte[] { 1, 2, 3, 4, 5 };

        storageAdapter.putObject(directory, path, content).get();
        byte[] retrieved = storageAdapter.getObject(directory, path).get();

        assertArrayEquals(content, retrieved);
    }

    @Test
    void exists_returnsTrueForExistingFile() throws ExecutionException, InterruptedException {
        String directory = TEST_DIR;
        String path = "existing.txt";

        storageAdapter.putText(directory, path, "content").get();

        assertTrue(storageAdapter.exists(directory, path).get());
    }

    @Test
    void exists_returnsFalseForNonExisting() throws ExecutionException, InterruptedException {
        assertFalse(storageAdapter.exists(TEST_DIR, "non-existing.txt").get());
    }

    @Test
    void deleteObject_removesFile() throws ExecutionException, InterruptedException {
        String directory = TEST_DIR;
        String path = "to-delete.txt";

        storageAdapter.putText(directory, path, "content").get();
        assertTrue(storageAdapter.exists(directory, path).get());

        storageAdapter.deleteObject(directory, path).get();
        assertFalse(storageAdapter.exists(directory, path).get());
    }

    @Test
    void listObjects_returnsAllFiles() throws ExecutionException, InterruptedException {
        String directory = TEST_DIR;

        storageAdapter.putText(directory, "file1.txt", "content1").get();
        storageAdapter.putText(directory, "file2.txt", "content2").get();
        storageAdapter.putText(directory, "subdir/file3.txt", "content3").get();

        List<String> files = storageAdapter.listObjects(directory, "").get();

        assertEquals(3, files.size());
        assertTrue(files.contains("file1.txt"));
        assertTrue(files.contains("file2.txt"));
    }

    @Test
    void appendText_appendsToFile() throws ExecutionException, InterruptedException {
        String directory = TEST_DIR;
        String path = "append.txt";

        storageAdapter.appendText(directory, path, "Line 1\n").get();
        storageAdapter.appendText(directory, path, "Line 2\n").get();

        String content = storageAdapter.getText(directory, path).get();

        assertTrue(content.contains("Line 1"));
        assertTrue(content.contains("Line 2"));
    }

    @Test
    void getText_returnsNullForNonExisting() throws ExecutionException, InterruptedException {
        assertNull(storageAdapter.getText("directory", "non-existing.txt").get());
    }

    @Test
    void ensureDirectory_createsDirectory() throws ExecutionException, InterruptedException {
        String directory = "new-directory";

        storageAdapter.ensureDirectory(directory).get();

        assertTrue(tempDir.resolve(directory).toFile().exists());
        assertTrue(tempDir.resolve(directory).toFile().isDirectory());
    }
}
