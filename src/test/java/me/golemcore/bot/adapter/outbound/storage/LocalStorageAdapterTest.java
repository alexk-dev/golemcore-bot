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
    private static final String CONTENT_DEFAULT = "content";
    private static final String FILE_1 = "file1.txt";

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

        storageAdapter.putText(directory, path, CONTENT_DEFAULT).get();

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

        storageAdapter.putText(directory, path, CONTENT_DEFAULT).get();
        assertTrue(storageAdapter.exists(directory, path).get());

        storageAdapter.deleteObject(directory, path).get();
        assertFalse(storageAdapter.exists(directory, path).get());
    }

    @Test
    void listObjects_returnsAllFiles() throws ExecutionException, InterruptedException {
        String directory = TEST_DIR;

        storageAdapter.putText(directory, FILE_1, "content1").get();
        storageAdapter.putText(directory, "file2.txt", "content2").get();
        storageAdapter.putText(directory, "subdir/file3.txt", "content3").get();

        List<String> files = storageAdapter.listObjects(directory, "").get();

        assertEquals(3, files.size());
        assertTrue(files.contains(FILE_1));
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

    // ==================== Path traversal ====================

    @Test
    void shouldBlockPathTraversal() {
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> storageAdapter.putText("test", "../../etc/passwd", "hack").get());
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void shouldBlockPathTraversalOnGet() {
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> storageAdapter.getText("test", "../../../secret").get());
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    // ==================== listObjects edge cases ====================

    @Test
    void shouldReturnEmptyListForNonExistentDirectory() throws ExecutionException, InterruptedException {
        List<String> files = storageAdapter.listObjects("non-existent-dir", "").get();
        assertTrue(files.isEmpty());
    }

    @Test
    void shouldReturnEmptyListForNonExistentPrefix() throws ExecutionException, InterruptedException {
        storageAdapter.putText(TEST_DIR, FILE_1, CONTENT_DEFAULT).get();
        List<String> files = storageAdapter.listObjects(TEST_DIR, "nonexistent-prefix").get();
        assertTrue(files.isEmpty());
    }

    @Test
    void shouldListObjectsWithNullPrefix() throws ExecutionException, InterruptedException {
        storageAdapter.putText(TEST_DIR, FILE_1, CONTENT_DEFAULT).get();
        List<String> files = storageAdapter.listObjects(TEST_DIR, null).get();
        assertFalse(files.isEmpty());
    }

    @Test
    void shouldListObjectsWithEmptyPrefix() throws ExecutionException, InterruptedException {
        storageAdapter.putText(TEST_DIR, "a.txt", "a").get();
        storageAdapter.putText(TEST_DIR, "b.txt", "b").get();
        List<String> files = storageAdapter.listObjects(TEST_DIR, "").get();
        assertEquals(2, files.size());
    }

    // ==================== getObject for non-existing file ====================

    @Test
    void shouldReturnNullBytesForNonExistingFile() throws ExecutionException, InterruptedException {
        byte[] result = storageAdapter.getObject(TEST_DIR, "no-such-file.bin").get();
        assertNull(result);
    }

    // ==================== deleteObject for non-existing file ====================

    @Test
    void shouldNotThrowWhenDeletingNonExistingFile() throws ExecutionException, InterruptedException {
        assertDoesNotThrow(() -> storageAdapter.deleteObject(TEST_DIR, "does-not-exist.txt").get());
    }

    // ==================== Nested directory creation on put ====================

    @Test
    void shouldCreateNestedDirectoriesOnPut() throws ExecutionException, InterruptedException {
        storageAdapter.putText(TEST_DIR, "deep/nested/dir/file.txt", "deep content").get();
        String retrieved = storageAdapter.getText(TEST_DIR, "deep/nested/dir/file.txt").get();
        assertEquals("deep content", retrieved);
    }

    // ==================== appendText creates parent dirs ====================

    @Test
    void shouldCreateParentDirsOnAppend() throws ExecutionException, InterruptedException {
        storageAdapter.appendText(TEST_DIR, "new-subdir/append.log", "line1\n").get();
        String content = storageAdapter.getText(TEST_DIR, "new-subdir/append.log").get();
        assertEquals("line1\n", content);
    }

    // ==================== exists for directory ====================

    @Test
    void shouldReturnFalseForExistsOnDirectory() throws ExecutionException, InterruptedException {
        storageAdapter.ensureDirectory("check-dir").get();
        // exists checks a file path, not a directory — basepath/check-dir/ is the dir
        // itself
        boolean result = storageAdapter.exists("check-dir", "no-file").get();
        assertFalse(result);
    }

    // ==================== Atomic write ====================

    @Test
    void putTextAtomic_writesContentSuccessfully() throws ExecutionException, InterruptedException {
        String directory = TEST_DIR;
        String path = "atomic-test.json";
        String content = "{\"key\": \"value\"}";

        storageAdapter.putTextAtomic(directory, path, content, false).get();

        String retrieved = storageAdapter.getText(directory, path).get();
        assertEquals(content, retrieved);
    }

    @Test
    void putTextAtomic_createsBackupWhenRequested() throws ExecutionException, InterruptedException {
        String directory = TEST_DIR;
        String path = "config.json";
        String originalContent = "{\"version\": 1}";
        String updatedContent = "{\"version\": 2}";

        // Write original
        storageAdapter.putTextAtomic(directory, path, originalContent, false).get();

        // Update with backup
        storageAdapter.putTextAtomic(directory, path, updatedContent, true).get();

        // Verify current content
        String current = storageAdapter.getText(directory, path).get();
        assertEquals(updatedContent, current);

        // Verify backup exists with original content
        String backup = storageAdapter.getText(directory, path + ".bak").get();
        assertEquals(originalContent, backup);
    }

    @Test
    void putTextAtomic_noBackupLeftWithoutFlag() throws ExecutionException, InterruptedException {
        String directory = TEST_DIR;
        String path = "no-backup.json";

        storageAdapter.putTextAtomic(directory, path, "first", false).get();
        storageAdapter.putTextAtomic(directory, path, "second", false).get();

        assertFalse(storageAdapter.exists(directory, path + ".bak").get());
    }

    @Test
    void putTextAtomic_noTempFileLeftAfterSuccess() throws ExecutionException, InterruptedException {
        String directory = TEST_DIR;
        String path = "clean.json";

        storageAdapter.putTextAtomic(directory, path, "content", false).get();

        assertFalse(storageAdapter.exists(directory, path + ".tmp").get());
    }

    @Test
    void putTextAtomic_createsParentDirectories() throws ExecutionException, InterruptedException {
        String directory = TEST_DIR;
        String path = "deep/nested/atomic.json";
        String content = "{\"nested\": true}";

        storageAdapter.putTextAtomic(directory, path, content, false).get();

        String retrieved = storageAdapter.getText(directory, path).get();
        assertEquals(content, retrieved);
    }

    @Test
    void putTextAtomic_overwritesExistingContent() throws ExecutionException, InterruptedException {
        String directory = TEST_DIR;
        String path = "overwrite.json";

        storageAdapter.putTextAtomic(directory, path, "old", false).get();
        storageAdapter.putTextAtomic(directory, path, "new", false).get();

        assertEquals("new", storageAdapter.getText(directory, path).get());
    }
}
