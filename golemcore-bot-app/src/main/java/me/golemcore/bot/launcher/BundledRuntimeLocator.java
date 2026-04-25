package me.golemcore.bot.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

final class BundledRuntimeLocator {

    private static final String LIB_DIR_NAME = "lib";
    private static final String RUNTIME_DIR_NAME = "runtime";

    private final EnvironmentReader environmentReader;
    private final PropertyReader propertyReader;
    private final LauncherOutput output;
    private final BundledRuntimeResolver bundledRuntimeResolver;

    BundledRuntimeLocator(
            EnvironmentReader environmentReader,
            PropertyReader propertyReader,
            LauncherOutput output,
            BundledRuntimeResolver bundledRuntimeResolver) {
        this.environmentReader = environmentReader;
        this.propertyReader = propertyReader;
        this.output = output;
        this.bundledRuntimeResolver = bundledRuntimeResolver;
    }

    /**
     * Resolves a bundled runtime jar shipped with the launcher itself.
     *
     * <p>
     * An explicit launcher option or system property wins first. Otherwise the
     * launcher inspects the code-source location and common app-image directories
     * to support both direct jars and native distributions produced by
     * {@code jpackage}.
     *
     * @return bundled runtime jar path, or {@code null} when the launcher should
     *         use classpath mode
     */
    Path resolve(LauncherArguments launcherArguments) {
        String configuredBundledJar = LauncherText.firstNonBlank(
                launcherArguments.bundledJar(),
                propertyReader.get(RuntimeLauncher.BUNDLED_JAR_PROPERTY),
                environmentReader.get(RuntimeLauncher.BUNDLED_JAR_ENV));
        if (configuredBundledJar != null) {
            Path configuredPath = LauncherPaths.normalizePath(configuredBundledJar);
            if (isRuntimeJar(configuredPath)) {
                return configuredPath;
            }
            output.error("Ignoring missing bundled runtime jar: " + configuredPath);
            return null;
        }

        Path resolvedLocation = bundledRuntimeResolver.resolve();
        if (resolvedLocation == null) {
            return null;
        }

        Path normalizedLocation = resolvedLocation.toAbsolutePath().normalize();
        if (isBundledRuntimeJar(normalizedLocation)) {
            return normalizedLocation;
        }

        Path siblingJar = findBundledRuntimeJar(normalizedLocation);
        if (siblingJar != null) {
            return siblingJar;
        }

        return findBundledRuntimeJarInCommonRuntimeDirs(normalizedLocation);
    }

    /**
     * Searches app-image layouts that place the runtime jar in a nearby
     * {@code runtime} directory instead of next to the launcher artifact.
     *
     * <p>
     * This covers the directory shapes produced by local native bundles on Linux
     * and macOS.
     *
     * @param resolvedLocation
     *            launcher code-source location
     * @return discovered bundled runtime jar, or {@code null} when none exists
     *         nearby
     */
    private Path findBundledRuntimeJarInCommonRuntimeDirs(Path resolvedLocation) {
        Path currentDirectory = Files.isDirectory(resolvedLocation)
                ? resolvedLocation
                : resolvedLocation.getParent();
        if (currentDirectory == null) {
            return null;
        }

        Set<Path> searchDirectories = new LinkedHashSet<>();
        addSearchDirectory(searchDirectories, currentDirectory.resolve(RUNTIME_DIR_NAME));
        addSearchDirectory(searchDirectories, currentDirectory.resolve(LIB_DIR_NAME).resolve(RUNTIME_DIR_NAME));

        Path parentDirectory = currentDirectory.getParent();
        if (parentDirectory != null) {
            addSearchDirectory(searchDirectories, parentDirectory);
            addSearchDirectory(searchDirectories, parentDirectory.resolve(RUNTIME_DIR_NAME));
            addSearchDirectory(searchDirectories, parentDirectory.resolve(LIB_DIR_NAME).resolve(RUNTIME_DIR_NAME));
        }

        for (Path searchDirectory : searchDirectories) {
            Path bundledJar = findBundledRuntimeJar(searchDirectory);
            if (bundledJar != null) {
                return bundledJar;
            }
        }
        return null;
    }

    private static void addSearchDirectory(Set<Path> searchDirectories, Path directory) {
        if (directory != null) {
            searchDirectories.add(directory.toAbsolutePath().normalize());
        }
    }

    /**
     * Scans a single directory for a bundled runtime jar.
     *
     * @param resolvedLocation
     *            directory or file near the bundled runtime
     * @return matching runtime jar, or {@code null} when no candidate is present
     */
    private Path findBundledRuntimeJar(Path resolvedLocation) {
        Path searchDirectory = Files.isDirectory(resolvedLocation)
                ? resolvedLocation
                : resolvedLocation.getParent();
        if (searchDirectory == null || !Files.isDirectory(searchDirectory)) {
            return null;
        }

        try (Stream<Path> stream = Files.list(searchDirectory)) {
            return stream
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .filter(this::isBundledRuntimeJar)
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            output.error("Failed to inspect bundled runtime directory: " + LauncherText.safeMessage(e));
            return null;
        }
    }

    private boolean isBundledRuntimeJar(Path path) {
        if (!isRuntimeJar(path)) {
            return false;
        }
        Path fileNamePath = path.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
        return fileName.startsWith("bot-") && !fileName.endsWith(".jar.original");
    }

    private boolean isRuntimeJar(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        Path fileNamePath = path.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".jar");
    }
}
