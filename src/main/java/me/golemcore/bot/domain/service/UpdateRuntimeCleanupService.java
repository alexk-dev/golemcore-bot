package me.golemcore.bot.domain.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.port.outbound.UpdateSettingsPort;
import me.golemcore.bot.port.outbound.WorkspaceFilePort;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpdateRuntimeCleanupService {

    private static final String CURRENT_MARKER_NAME = "current.txt";
    private static final String STAGED_MARKER_NAME = "staged.txt";
    private static final String JARS_DIR_NAME = "jars";

    private final UpdateSettingsPort settingsPort;
    private final WorkspaceFilePort workspaceFilePort;
    private final RuntimeVersionSupport runtimeVersionSupport = new RuntimeVersionSupport();

    @SuppressWarnings("PMD.NullAssignment")
    public void cleanupAfterSuccessfulStartup() {
        cleanupAfterSuccessfulStartup(null);
    }

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
            deleteIfExists(jarsDir.resolve(currentAsset));
            currentAsset = null;
        }

        if (shouldDropStaleMarker(stagedAsset, runningVersion)) {
            deleteIfExists(stagedMarker);
            deleteIfExists(jarsDir.resolve(stagedAsset));
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

    private boolean shouldDeleteJar(Path path, Set<String> retainedAssets) {
        String fileName = fileName(path);
        return fileName != null && fileName.endsWith(".jar") && !retainedAssets.contains(fileName);
    }

    private String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : null;
    }

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
