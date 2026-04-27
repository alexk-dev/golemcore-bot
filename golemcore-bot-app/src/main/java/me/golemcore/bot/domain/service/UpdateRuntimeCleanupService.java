package me.golemcore.bot.domain.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.port.outbound.UpdateSettingsPort;
import me.golemcore.bot.port.outbound.WorkspaceFilePort;
import me.golemcore.bot.runtime.RuntimeVersionSupport;
import org.springframework.stereotype.Service;

/**
 * Cleans up runtime update artifacts after a successful startup.
 *
 * <p>
 * The service keeps the currently active runtime jar and any still-relevant
 * staged candidate, while deleting obsolete jars left behind by previous
 * updates. When the process is already running from a newer bundled image, it
 * also clears stale persisted markers that would otherwise keep forcing an
 * older jar on the next restart.
 * </p>
 */
@Service
@Slf4j
public class UpdateRuntimeCleanupService {

    private static final String CURRENT_MARKER_NAME = "current.txt";
    private static final String STAGED_MARKER_NAME = "staged.txt";
    private static final String JARS_DIR_NAME = "jars";

    private final UpdateSettingsPort settingsPort;
    private final WorkspaceFilePort workspaceFilePort;
    private final RuntimeVersionSupport runtimeVersionSupport = new RuntimeVersionSupport();

    public UpdateRuntimeCleanupService(
            UpdateSettingsPort settingsPort,
            WorkspaceFilePort workspaceFilePort) {
        this.settingsPort = settingsPort;
        this.workspaceFilePort = workspaceFilePort;
    }

    /**
     * Run startup cleanup without comparing against an explicitly supplied running
     * version.
     */
    @SuppressWarnings("PMD.NullAssignment")
    public void cleanupAfterSuccessfulStartup() {
        cleanupAfterSuccessfulStartup(null);
    }

    /**
     * Remove obsolete runtime jars and clear stale marker files after a successful
     * startup.
     *
     * <p>
     * When {@code runningVersion} is present and strictly newer than a marker
     * target, the stale marker and its jar are removed. Equal versions are kept
     * intentionally so variant A behavior remains intact: a same-version persisted
     * jar continues to win over the bundled image on future restarts.
     * </p>
     *
     * @param runningVersion
     *            normalized version of the runtime that actually started, or
     *            {@code null} when the caller only wants generic cleanup
     */
    @SuppressWarnings("PMD.NullAssignment")
    public void cleanupAfterSuccessfulStartup(String runningVersion) {
        if (!settingsPort.update().enabled()) {
            return;
        }

        Path updatesDir = Path.of(settingsPort.update().updatesPath()).toAbsolutePath().normalize();
        Path jarsDir = updatesDir.resolve(JARS_DIR_NAME);
        if (!workspaceFilePort.isDirectory(jarsDir)) {
            return;
        }

        Path currentMarker = updatesDir.resolve(CURRENT_MARKER_NAME);
        Path stagedMarker = updatesDir.resolve(STAGED_MARKER_NAME);
        String currentAsset = readMarker(currentMarker);
        String stagedAsset = readMarker(stagedMarker);

        if (shouldDropStaleMarker(currentAsset, runningVersion)) {
            deleteIfExists(currentMarker);
            deleteAssetJarIfSafe(jarsDir, currentAsset);
            currentAsset = null;
        }

        if (shouldDropStaleMarker(stagedAsset, runningVersion)) {
            deleteIfExists(stagedMarker);
            deleteAssetJarIfSafe(jarsDir, stagedAsset);
            stagedAsset = null;
        }

        if (currentAsset != null && currentAsset.equals(stagedAsset)) {
            deleteIfExists(stagedMarker);
            stagedAsset = null;
        }

        Set<String> retainedAssets = new HashSet<>();
        if (currentAsset != null) {
            retainedAssets.add(currentAsset);
        }
        if (stagedAsset != null) {
            retainedAssets.add(stagedAsset);
        }

        try {
            List<Path> jarPaths = workspaceFilePort.list(jarsDir);
            for (Path path : jarPaths) {
                if (workspaceFilePort.isRegularFile(path) && shouldDeleteJar(path, retainedAssets)) {
                    deleteIfExists(path);
                }
            }
        } catch (IOException e) {
            log.warn("[update] failed to cleanup old runtime jars: {}", e.getMessage());
        }
    }

    /**
     * Decide whether a persisted marker points to an older runtime than the one
     * that has already started successfully.
     */
    private boolean shouldDropStaleMarker(String assetName, String runningVersion) {
        if (assetName == null || runningVersion == null || runningVersion.isBlank()) {
            return false;
        }
        String assetVersion = runtimeVersionSupport.extractVersionFromAssetName(assetName);
        if (assetVersion == null) {
            return false;
        }
        if (!runtimeVersionSupport.isSemanticVersion(runtimeVersionSupport.normalizeVersion(runningVersion))
                || !runtimeVersionSupport.isSemanticVersion(assetVersion)) {
            return false;
        }
        return runtimeVersionSupport.compareVersions(runningVersion, assetVersion) > 0;
    }

    /**
     * Delete a jar only when the marker asset name resolves safely under the
     * runtime jars directory.
     */
    private void deleteAssetJarIfSafe(Path jarsDir, String assetName) {
        Path assetPath = resolveAssetPath(jarsDir, assetName);
        if (assetPath == null) {
            return;
        }
        deleteIfExists(assetPath);
    }

    /**
     * Resolve a marker asset name to an on-disk jar path while preventing path
     * traversal or nested path tricks from escaping {@code jarsDir}.
     */
    private Path resolveAssetPath(Path jarsDir, String assetName) {
        if (!isValidAssetName(assetName)) {
            return null;
        }
        Path normalizedJarsDir = jarsDir.toAbsolutePath().normalize();
        Path assetPath = normalizedJarsDir.resolve(assetName).toAbsolutePath().normalize();
        return assetPath.startsWith(normalizedJarsDir) ? assetPath : null;
    }

    /**
     * Accept only plain file names written by the update artifact store.
     */
    private boolean isValidAssetName(String assetName) {
        if (assetName == null || assetName.isBlank()) {
            return false;
        }
        return !assetName.contains("/") && !assetName.contains("\\") && !assetName.contains("..");
    }

    private boolean shouldDeleteJar(Path path, Set<String> retainedAssets) {
        String fileName = fileName(path);
        return fileName != null && fileName.endsWith(".jar") && !retainedAssets.contains(fileName);
    }

    private String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : null;
    }

    /**
     * Read a marker file best-effort. Invalid or unreadable markers are treated as
     * absent so startup cleanup stays resilient.
     */
    private String readMarker(Path markerPath) {
        try {
            if (!workspaceFilePort.exists(markerPath)) {
                return null;
            }
            String content = workspaceFilePort.readString(markerPath).trim();
            return content.isBlank() ? null : content;
        } catch (IOException e) {
            return null;
        }
    }

    private void deleteIfExists(Path path) {
        try {
            workspaceFilePort.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("[update] failed to delete {}: {}", path, e.getMessage());
        }
    }
}
