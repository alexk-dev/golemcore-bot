package me.golemcore.bot.adapter.outbound.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;
import me.golemcore.bot.port.outbound.ReleaseSourcePort;
import me.golemcore.bot.port.outbound.UpdateArtifactStorePort;
import me.golemcore.bot.port.outbound.UpdateSettingsPort;
import org.springframework.stereotype.Component;

@Component
public class FileSystemUpdateArtifactStoreAdapter implements UpdateArtifactStorePort {

    private static final String JARS_DIR_NAME = "jars";
    private static final String CURRENT_MARKER_NAME = "current.txt";
    private static final String STAGED_MARKER_NAME = "staged.txt";
    private static final String UPDATE_PATH_ENV = "UPDATE_PATH";
    private static final int DOWNLOAD_BUFFER_SIZE = 8192;

    private final UpdateSettingsPort settingsPort;

    public FileSystemUpdateArtifactStoreAdapter(UpdateSettingsPort settingsPort) {
        this.settingsPort = settingsPort;
    }

    @Override
    public Optional<StoredArtifact> findCurrentArtifact() {
        String assetName = readMarker(getCurrentMarkerPath());
        if (assetName == null) {
            return Optional.empty();
        }
        Path jarPath = resolveJarPath(assetName);
        if (!Files.exists(jarPath)) {
            return Optional.empty();
        }
        return Optional.of(new StoredArtifact(assetName, readLastModifiedSafe(jarPath)));
    }

    @Override
    public Optional<StoredArtifact> findStagedArtifact() {
        String assetName = readMarker(getStagedMarkerPath());
        if (assetName == null) {
            return Optional.empty();
        }
        Path jarPath = resolveJarPath(assetName);
        if (!Files.exists(jarPath)) {
            return Optional.empty();
        }
        return Optional.of(new StoredArtifact(assetName, readLastModifiedSafe(getStagedMarkerPath())));
    }

    @Override
    public PreparedArtifact stageReleaseAsset(StageArtifactRequest request) {
        validateAssetName(request.assetName());
        Path targetJar = resolveJarPath(request.assetName());
        Path tempJar = targetJar.resolveSibling(request.assetName() + ".tmp");
        try {
            Path parent = targetJar.getParent();
            if (parent == null) {
                throw new IllegalStateException("Failed to resolve target parent path: " + targetJar);
            }
            ensureUpdateDirectoryWritable(parent);
            writeAsset(tempJar, request.assetStream());
            verifyChecksum(tempJar, request.checksumInfo());
            moveAtomically(tempJar, targetJar);
            writeMarker(getStagedMarkerPath(), request.assetName());
            return new PreparedArtifact(request.assetName(), readLastModifiedSafe(getStagedMarkerPath()));
        } catch (RuntimeException | IOException exception) {
            deleteIfExistsQuietly(tempJar);
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to stage release artifact: " + request.assetName(), exception);
        }
    }

    @Override
    public void activateStagedArtifact(String assetName) {
        validateAssetName(assetName);
        writeMarker(getCurrentMarkerPath(), assetName);
        deleteMarker(getStagedMarkerPath());
    }

    @Override
    public void cleanupTempArtifact(String assetName) {
        if (assetName == null || assetName.isBlank()) {
            return;
        }
        validateAssetName(assetName);
        Path targetJar = resolveJarPath(assetName);
        Path tempJar = targetJar.resolveSibling(assetName + ".tmp");
        deleteIfExistsQuietly(tempJar);
    }

    private void writeAsset(Path targetPath, InputStream assetStream) throws IOException {
        try (InputStream inputStream = assetStream) {
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void verifyChecksum(Path filePath, ReleaseSourcePort.ChecksumInfo checksumInfo) {
        String actualHash = computeDigest(filePath, checksumInfo.algorithm());
        if (!checksumInfo.hexDigest().equalsIgnoreCase(actualHash)) {
            deleteIfExistsQuietly(filePath);
            throw new IllegalStateException("Checksum mismatch for " + checksumInfo.assetName());
        }
    }

    private Path getUpdatesDir() {
        String configuredPath = settingsPort.update().updatesPath();
        return Path.of(configuredPath).toAbsolutePath().normalize();
    }

    private Path getJarsDir() {
        return getUpdatesDir().resolve(JARS_DIR_NAME);
    }

    private Path getCurrentMarkerPath() {
        return getUpdatesDir().resolve(CURRENT_MARKER_NAME);
    }

    private Path getStagedMarkerPath() {
        return getUpdatesDir().resolve(STAGED_MARKER_NAME);
    }

    private Path resolveJarPath(String assetName) {
        Path jarsDir = getJarsDir();
        Path resolved = jarsDir.resolve(assetName).normalize();
        if (!resolved.startsWith(jarsDir)) {
            throw new IllegalArgumentException("Invalid asset path");
        }
        return resolved;
    }

    private void validateAssetName(String assetName) {
        if (assetName == null || assetName.isBlank()) {
            throw new IllegalArgumentException("Invalid asset name");
        }
        if (assetName.contains("/") || assetName.contains("\\") || assetName.contains("..")) {
            throw new IllegalArgumentException("Asset name contains prohibited path characters");
        }
    }

    private String readMarker(Path markerPath) {
        try {
            if (!Files.exists(markerPath)) {
                return null;
            }
            String content = Files.readString(markerPath, StandardCharsets.UTF_8).trim();
            return content.isBlank() ? null : content;
        } catch (IOException exception) {
            return null;
        }
    }

    private void writeMarker(Path markerPath, String value) {
        try {
            Path parent = markerPath.getParent();
            if (parent == null) {
                throw new IllegalStateException("Failed to resolve marker parent path: " + markerPath);
            }
            ensureUpdateDirectoryWritable(parent);
            Files.writeString(
                    markerPath,
                    value + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write marker file: " + markerPath, exception);
        }
    }

    private void deleteMarker(Path markerPath) {
        try {
            Files.deleteIfExists(markerPath);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete marker file: " + markerPath, exception);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void ensureUpdateDirectoryWritable(Path directoryPath) {
        try {
            Files.createDirectories(directoryPath);
        } catch (IOException exception) {
            throw new IllegalStateException("Update directory is not writable: " + directoryPath
                    + ". Configure " + UPDATE_PATH_ENV + " to a writable path.", exception);
        }
    }

    private String computeDigest(Path filePath, String algorithm) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is not available", exception);
        }

        try (InputStream inputStream = Files.newInputStream(filePath, StandardOpenOption.READ)) {
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
            while (true) {
                int read = inputStream.read(buffer);
                if (read == -1) {
                    break;
                }
                digest.update(buffer, 0, read);
            }
            byte[] hash = digest.digest();
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to compute " + algorithm, exception);
        }
    }

    private Instant readLastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException exception) {
            return Instant.EPOCH;
        }
    }

    private void deleteIfExistsQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }
}
