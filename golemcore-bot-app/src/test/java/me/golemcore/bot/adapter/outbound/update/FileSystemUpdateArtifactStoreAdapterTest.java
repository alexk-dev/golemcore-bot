package me.golemcore.bot.adapter.outbound.update;

import me.golemcore.bot.port.outbound.ReleaseSourcePort;
import me.golemcore.bot.port.outbound.UpdateArtifactStorePort;
import me.golemcore.bot.port.outbound.UpdateSettingsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemUpdateArtifactStoreAdapterTest {

    @TempDir
    private Path tempDir;

    @Test
    void shouldStageActivateFindAndCleanupReleaseArtifacts() throws Exception {
        FileSystemUpdateArtifactStoreAdapter adapter = adapter();
        byte[] artifactBytes = "jar-bytes".getBytes(StandardCharsets.UTF_8);

        UpdateArtifactStorePort.PreparedArtifact prepared = adapter.stageReleaseAsset(
                new UpdateArtifactStorePort.StageArtifactRequest(
                        "golemcore-bot.jar",
                        new ByteArrayInputStream(artifactBytes),
                        checksum("golemcore-bot.jar", artifactBytes)));

        assertEquals("golemcore-bot.jar", prepared.assetName());
        assertArrayEquals(artifactBytes, Files.readAllBytes(tempDir.resolve("jars/golemcore-bot.jar")));
        assertEquals("golemcore-bot.jar", Files.readString(tempDir.resolve("staged.txt")).trim());
        assertTrue(adapter.findStagedArtifact().isPresent());
        assertTrue(adapter.findCurrentArtifact().isEmpty());

        adapter.activateStagedArtifact("golemcore-bot.jar");

        assertTrue(adapter.findCurrentArtifact().isPresent());
        assertTrue(adapter.findStagedArtifact().isEmpty());
        assertFalse(Files.exists(tempDir.resolve("staged.txt")));

        Files.writeString(tempDir.resolve("jars/golemcore-bot.jar.tmp"), "stale");
        adapter.cleanupTempArtifact("golemcore-bot.jar");
        adapter.cleanupTempArtifact(null);
        adapter.cleanupTempArtifact(" ");

        assertFalse(Files.exists(tempDir.resolve("jars/golemcore-bot.jar.tmp")));
    }

    @Test
    void shouldIgnoreMissingMarkersAndRejectUnsafeAssets() throws Exception {
        FileSystemUpdateArtifactStoreAdapter adapter = adapter();
        Files.writeString(tempDir.resolve("current.txt"), "missing.jar\n");
        Files.writeString(tempDir.resolve("staged.txt"), "\n");

        assertTrue(adapter.findCurrentArtifact().isEmpty());
        assertTrue(adapter.findStagedArtifact().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> adapter.activateStagedArtifact("../bot.jar"));
        assertThrows(IllegalArgumentException.class, () -> adapter.cleanupTempArtifact("nested/bot.jar"));
        assertThrows(IllegalArgumentException.class, () -> adapter.stageReleaseAsset(
                new UpdateArtifactStorePort.StageArtifactRequest(
                        "",
                        new ByteArrayInputStream(new byte[0]),
                        checksum("empty.jar", new byte[0]))));
    }

    @Test
    void shouldCleanTemporaryArtifactWhenChecksumVerificationFails() {
        FileSystemUpdateArtifactStoreAdapter adapter = adapter();
        byte[] artifactBytes = "corrupted".getBytes(StandardCharsets.UTF_8);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> adapter.stageReleaseAsset(
                new UpdateArtifactStorePort.StageArtifactRequest(
                        "bad.jar",
                        new ByteArrayInputStream(artifactBytes),
                        new ReleaseSourcePort.ChecksumInfo("deadbeef", "SHA-256", "bad.jar"))));

        assertTrue(exception.getMessage().contains("Checksum mismatch"));
        assertFalse(Files.exists(tempDir.resolve("jars/bad.jar")));
        assertFalse(Files.exists(tempDir.resolve("jars/bad.jar.tmp")));
        assertDoesNotThrow(() -> adapter.cleanupTempArtifact("bad.jar"));
    }

    private FileSystemUpdateArtifactStoreAdapter adapter() {
        UpdateSettingsPort settingsPort = () -> new UpdateSettingsPort.UpdateSettings(
                true,
                tempDir.toString(),
                3,
                Duration.ofHours(1));
        return new FileSystemUpdateArtifactStoreAdapter(settingsPort);
    }

    private ReleaseSourcePort.ChecksumInfo checksum(String assetName, byte[] content) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
        return new ReleaseSourcePort.ChecksumInfo(HexFormat.of().formatHex(hash), "SHA-256", assetName);
    }
}
