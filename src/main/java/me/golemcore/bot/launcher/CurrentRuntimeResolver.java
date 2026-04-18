package me.golemcore.bot.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import me.golemcore.bot.domain.service.RuntimeVersionSupport;

final class CurrentRuntimeResolver {

    private final EnvironmentReader environmentReader;
    private final PropertyReader propertyReader;
    private final LauncherOutput output;
    private final RuntimeVersionReader runtimeVersionReader;
    private final RuntimeVersionSupport runtimeVersionSupport;

    CurrentRuntimeResolver(
            EnvironmentReader environmentReader,
            PropertyReader propertyReader,
            LauncherOutput output,
            RuntimeVersionReader runtimeVersionReader,
            RuntimeVersionSupport runtimeVersionSupport) {
        this.environmentReader = environmentReader;
        this.propertyReader = propertyReader;
        this.output = output;
        this.runtimeVersionReader = runtimeVersionReader;
        this.runtimeVersionSupport = runtimeVersionSupport;
    }

    /**
     * Resolves the staged runtime jar pointed to by {@code current.txt}.
     *
     * <p>
     * The marker content is validated aggressively so a corrupted or hostile marker
     * cannot escape the updates directory via path traversal. If the bundled
     * runtime version is strictly newer, the marker is ignored so a fresh image can
     * take over from an older persisted runtime.
     *
     * @param launcherArguments
     *            normalized launcher arguments
     * @return staged runtime jar path, or {@code null} when no valid staged jar
     *         exists
     */
    Path resolveCurrentJar(LauncherArguments launcherArguments) {
        Path updatesDir = resolveUpdatesDir(launcherArguments);
        Path markerPath = updatesDir.resolve(RuntimeLauncher.CURRENT_MARKER_NAME);
        if (!Files.isRegularFile(markerPath)) {
            return null;
        }

        try {
            String assetName = Files.readString(markerPath, StandardCharsets.UTF_8).trim();
            if (assetName.isBlank()) {
                return null;
            }
            if (assetName.contains("/") || assetName.contains("\\") || assetName.contains("..")) {
                output.error("Ignoring invalid current marker asset name: " + assetName);
                return null;
            }

            Path jarsDir = updatesDir.resolve(RuntimeLauncher.JARS_DIR_NAME).toAbsolutePath().normalize();
            Path jarPath = jarsDir.resolve(assetName).toAbsolutePath().normalize();
            if (!jarPath.startsWith(jarsDir)) {
                output.error("Ignoring current marker outside updates directory: " + jarPath);
                return null;
            }
            if (!Files.isRegularFile(jarPath)) {
                output.error("Current marker points to a missing jar: " + jarPath);
                return null;
            }
            if (isBundledRuntimeNewer(assetName)) {
                return null;
            }
            return jarPath;
        } catch (IOException e) {
            output.error("Failed to read current marker: " + LauncherText.safeMessage(e));
            return null;
        }
    }

    /**
     * Resolves the updates directory using the same precedence as the runtime.
     *
     * <p>
     * Explicit launcher options win over JVM properties, which win over the ambient
     * environment. When no explicit updates path exists, the launcher derives it
     * from the storage root and finally falls back to the default workspace
     * location under the user home directory.
     *
     * @param launcherArguments
     *            normalized launcher arguments
     * @return normalized updates directory path
     */
    Path resolveUpdatesDir(LauncherArguments launcherArguments) {
        String configuredUpdatesPath = LauncherText.firstNonBlank(
                launcherArguments.updatesPath(),
                propertyReader.get(RuntimeLauncher.UPDATE_PATH_PROPERTY),
                environmentReader.get(RuntimeLauncher.UPDATE_PATH_ENV));
        if (configuredUpdatesPath != null) {
            return LauncherPaths.normalizePath(configuredUpdatesPath);
        }

        String storageBasePath = LauncherText.firstNonBlank(
                launcherArguments.storagePath(),
                propertyReader.get(RuntimeLauncher.STORAGE_PATH_PROPERTY),
                environmentReader.get(RuntimeLauncher.STORAGE_PATH_ENV));
        if (storageBasePath != null) {
            return LauncherPaths.normalizePath(storageBasePath).resolve("updates").normalize();
        }

        return Path.of(System.getProperty("user.home"), ".golemcore", "workspace", "updates")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Decide whether the bundled runtime should override the persisted current jar.
     *
     * <p>
     * Equal versions keep using the persisted jar, so only a strictly newer bundled
     * image suppresses the current marker.
     * </p>
     */
    private boolean isBundledRuntimeNewer(String assetName) {
        String bundledVersion = runtimeVersionSupport.normalizeVersion(runtimeVersionReader.currentVersion());
        String currentJarVersion = runtimeVersionSupport.extractVersionFromAssetName(assetName);
        if (bundledVersion == null || currentJarVersion == null) {
            return false;
        }
        if (!runtimeVersionSupport.isSemanticVersion(bundledVersion)
                || !runtimeVersionSupport.isSemanticVersion(currentJarVersion)) {
            return false;
        }

        boolean bundledIsNewer = runtimeVersionSupport.compareVersions(bundledVersion, currentJarVersion) > 0;
        if (bundledIsNewer) {
            output.info("Ignoring current marker because bundled runtime " + bundledVersion
                    + " is newer than " + currentJarVersion);
        }
        return bundledIsNewer;
    }
}
