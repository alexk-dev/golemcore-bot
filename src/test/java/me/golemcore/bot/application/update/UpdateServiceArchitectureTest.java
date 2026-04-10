package me.golemcore.bot.application.update;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UpdateServiceArchitectureTest {

    private static final Path SOURCE_PATH = Path.of(
            "src/main/java/me/golemcore/bot/application/update/UpdateService.java");

    @Test
    void shouldDependOnUpdateArtifactStorePortForFilesystemWork() {
        String source = readSource();

        assertTrue(source.contains("UpdateArtifactStorePort"),
                "UpdateService should delegate marker and staged/current jar work to UpdateArtifactStorePort");
    }

    @Test
    void shouldNotUseLowLevelFilesystemTypesDirectly() {
        String source = readSource();

        assertFalse(source.contains("import java.nio.file.Files;"),
                "UpdateService must not import Files directly");
        assertFalse(source.contains("import java.nio.file.Path;"),
                "UpdateService must not import Path directly");
        assertFalse(source.contains("import java.nio.file.StandardCopyOption;"),
                "UpdateService must not import StandardCopyOption directly");
        assertFalse(source.contains("import java.nio.file.StandardOpenOption;"),
                "UpdateService must not import StandardOpenOption directly");
        assertFalse(source.contains("Files.exists("),
                "UpdateService must not check filesystem state directly");
        assertFalse(source.contains("Files.readString("),
                "UpdateService must not read marker files directly");
        assertFalse(source.contains("Files.writeString("),
                "UpdateService must not write marker files directly");
        assertFalse(source.contains("Files.copy("),
                "UpdateService must not copy downloaded artifacts directly");
        assertFalse(source.contains("Files.move("),
                "UpdateService must not move staged artifacts directly");
        assertFalse(source.contains("Files.newInputStream("),
                "UpdateService must not open artifact streams directly from the local filesystem");
        assertFalse(source.contains("Files.createDirectories("),
                "UpdateService must not ensure writable directories directly");
        assertFalse(source.contains("Files.deleteIfExists("),
                "UpdateService must not delete marker/temp files directly");
    }

    private String readSource() {
        try {
            return Files.readString(SOURCE_PATH);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read UpdateService source", exception);
        }
    }
}
