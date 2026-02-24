package me.golemcore.bot.adapter.outbound.browser;

import com.microsoft.playwright.Playwright;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Component
@RequiredArgsConstructor
@Slf4j
public class PlaywrightDriverBundleService {

    private static final String PLAYWRIGHT_CLI_DIR_PROPERTY = "playwright.cli.dir";
    private static final String PLAYWRIGHT_NODE_DIR_NAME = "playwright-driver";
    private static final String BUNDLES_DIR_NAME = "bundles";
    private static final String INSTALLS_DIR_NAME = "installs";
    private static final String ARTIFACT_PREFIX = "driver-bundle-";
    private static final String ARTIFACT_SUFFIX = ".jar";
    private static final String REPO_MAVEN_APACHE = "https://repo.maven.apache.org/maven2";
    private static final String REPO_MAVEN_ORG = "https://repo1.maven.org/maven2";
    private static final String ARTIFACT_BASE_PATH = "/com/microsoft/playwright/driver-bundle/";
    private static final String HOST_MAVEN_APACHE = "repo.maven.apache.org";
    private static final String HOST_MAVEN_ORG = "repo1.maven.org";
    private static final String HTTPS_SCHEME = "https";
    private static final int HTTPS_PORT = 443;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final int DOWNLOAD_BUFFER_SIZE = 8192;
    // Security limits for archive extraction (zip-slip/zip-bomb hardening).
    private static final long MAX_BUNDLE_JAR_SIZE_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_ARCHIVE_ENTRY_COUNT = 20_000;
    private static final long MAX_ENTRY_SIZE_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_TOTAL_EXTRACTED_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_COMPRESSION_RATIO = 100L;
    private static final Pattern SAFE_VERSION_PATTERN = Pattern.compile("^[0-9A-Za-z._-]+$");
    private static final Pattern SEMVER_PATTERN = Pattern
            .compile("^(\\d++)\\.(\\d++)\\.(\\d++)(?:-([0-9A-Za-z.-]++))?$");

    private final BotProperties botProperties;

    public synchronized Path ensureDriverReady() {
        Path preconfiguredDir = resolvePreconfiguredDir();
        if (preconfiguredDir != null) {
            return preconfiguredDir;
        }

        String expectedVersion = resolvePlaywrightVersion();
        String platform = resolvePlatformClassifier();

        Path installsRoot = getManagedRoot().resolve(INSTALLS_DIR_NAME);
        Path installDir = installsRoot.resolve(expectedVersion).resolve(platform).normalize();
        if (isDriverDirectoryReady(installDir)) {
            applyDriverDir(installDir);
            log.info("[Playwright] Driver bundle {} already installed for {}", expectedVersion, platform);
            return installDir;
        }

        List<String> installedVersions = listInstalledVersions(installsRoot, platform);
        if (installedVersions.isEmpty()) {
            log.info("[Playwright] No installed driver bundle found for platform {}", platform);
        } else {
            log.info("[Playwright] Installed driver bundle versions for {}: {}", platform,
                    String.join(", ", installedVersions));
        }

        Path bundlesRoot = getManagedRoot().resolve(BUNDLES_DIR_NAME);
        Path bundleJar = bundlesRoot.resolve(ARTIFACT_PREFIX + expectedVersion + ARTIFACT_SUFFIX).normalize();
        if (!Files.exists(bundleJar)) {
            downloadBundle(expectedVersion, bundleJar);
        }

        extractBundleForPlatform(bundleJar, platform, installDir);
        if (!isDriverDirectoryReady(installDir)) {
            throw new IllegalStateException(
                    "Playwright driver bundle installation is incomplete for " + expectedVersion + " / " + platform);
        }
        applyDriverDir(installDir);

        log.info("[Playwright] Installed driver bundle {} for {} at {}", expectedVersion, platform, installDir);
        return installDir;
    }

    protected HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    protected String resolvePlaywrightVersion() {
        Package playwrightPackage = Playwright.class.getPackage();
        String version = playwrightPackage != null ? playwrightPackage.getImplementationVersion() : null;
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("Unable to determine Playwright version from classpath");
        }
        return normalizeVersion(version);
    }

    protected String resolvePlatformClassifier() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        boolean arm64 = "aarch64".equals(arch) || "arm64".equals(arch);
        boolean amd64 = "x86_64".equals(arch) || "amd64".equals(arch);

        if (osName.contains("mac")) {
            return arm64 ? "mac-arm64" : "mac";
        }
        if (osName.contains("linux")) {
            return arm64 ? "linux-arm64" : "linux";
        }
        if (osName.contains("win")) {
            if (!amd64) {
                throw new IllegalStateException("Unsupported Windows architecture for Playwright: " + arch);
            }
            return "win32_x64";
        }
        throw new IllegalStateException("Unsupported OS for Playwright driver: " + osName + " / " + arch);
    }

    private Path resolvePreconfiguredDir() {
        String preconfigured = System.getProperty(PLAYWRIGHT_CLI_DIR_PROPERTY);
        if (preconfigured == null || preconfigured.isBlank()) {
            return null;
        }

        Path preconfiguredPath = Path.of(preconfigured).toAbsolutePath().normalize();
        if (isDriverDirectoryReady(preconfiguredPath)) {
            log.info("[Playwright] Using preconfigured {}={}", PLAYWRIGHT_CLI_DIR_PROPERTY, preconfiguredPath);
            return preconfiguredPath;
        }

        log.warn("[Playwright] Ignoring preconfigured {}: {} (directory is not usable)", PLAYWRIGHT_CLI_DIR_PROPERTY,
                preconfiguredPath);
        return null;
    }

    private Path getManagedRoot() {
        return Path.of(botProperties.getUpdate().getUpdatesPath())
                .toAbsolutePath()
                .normalize()
                .resolve(PLAYWRIGHT_NODE_DIR_NAME);
    }

    private List<String> listInstalledVersions(Path installsRoot, String platform) {
        if (!Files.exists(installsRoot)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(installsRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(versionDir -> isDriverDirectoryReady(versionDir.resolve(platform)))
                    .map(path -> path.getFileName().toString())
                    .sorted(this::compareVersions)
                    .toList();
        } catch (IOException e) {
            log.debug("[Playwright] Failed to list installed versions: {}", e.getMessage());
            return List.of();
        }
    }

    private void applyDriverDir(Path driverDir) {
        System.setProperty(PLAYWRIGHT_CLI_DIR_PROPERTY, driverDir.toString());
    }

    private boolean isDriverDirectoryReady(Path directory) {
        Path cli = directory.resolve("package").resolve("cli.js");
        if (!Files.isRegularFile(cli)) {
            return false;
        }
        Path node = directory.resolve("node");
        Path nodeExe = directory.resolve("node.exe");
        return Files.isRegularFile(node) || Files.isRegularFile(nodeExe);
    }

    private void downloadBundle(String version, Path targetBundlePath) {
        ensureSafeVersion(version);
        IOException lastIo = null;
        RuntimeException lastRuntime = null;

        for (String repoBase : List.of(REPO_MAVEN_APACHE, REPO_MAVEN_ORG)) {
            URI uri = buildBundleUri(repoBase, version);
            try {
                downloadBundleFromUri(uri, targetBundlePath);
                return;
            } catch (IOException e) {
                lastIo = e;
                log.warn("[Playwright] Failed to download {} from {}: {}", version, uri, e.getMessage());
            } catch (RuntimeException e) {
                lastRuntime = e;
                log.warn("[Playwright] Failed to download {} from {}: {}", version, uri, e.getMessage());
            }
        }

        if (lastIo != null) {
            throw new IllegalStateException("Failed to download Playwright driver bundle " + version, lastIo);
        }
        if (lastRuntime != null) {
            throw new IllegalStateException("Failed to download Playwright driver bundle " + version, lastRuntime);
        }
        throw new IllegalStateException("Failed to download Playwright driver bundle " + version);
    }

    private void downloadBundleFromUri(URI uri, Path targetBundlePath) throws IOException {
        URI trustedUri = requireTrustedMavenUri(uri);
        Path parent = targetBundlePath.getParent();
        if (parent == null) {
            throw new IllegalStateException("Failed to resolve target bundle directory");
        }
        ensureDirectoryWritable(parent);

        Path tempPath = targetBundlePath.resolveSibling(targetBundlePath.getFileName() + ".tmp");
        Files.deleteIfExists(tempPath);

        HttpRequest request = HttpRequest.newBuilder(trustedUri)
                .GET()
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", "golemcore-playwright-driver")
                .build();

        HttpResponse<InputStream> response;
        try {
            response = buildHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Playwright bundle download interrupted", e);
        }

        int statusCode = response.statusCode();
        if (statusCode >= 400) {
            try (InputStream ignored = response.body()) {
                // close response body
            }
            throw new IllegalStateException("Playwright bundle download failed with HTTP " + statusCode);
        }

        try (InputStream input = response.body()) {
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
            try (OutputStream output = Files.newOutputStream(
                    tempPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                while (true) {
                    int read = input.read(buffer);
                    if (read == -1) {
                        break;
                    }
                    output.write(buffer, 0, read);
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(tempPath);
            throw e;
        }

        moveAtomically(tempPath, targetBundlePath);
    }

    private void extractBundleForPlatform(Path bundleJar, String platform, Path installDir) {
        if (isDriverDirectoryReady(installDir)) {
            return;
        }

        validateBundleJarFile(bundleJar);

        Path parent = installDir.getParent();
        if (parent == null) {
            throw new IllegalStateException("Failed to resolve install directory parent");
        }
        ensureDirectoryWritable(parent);

        String prefix = "driver/" + platform + "/";
        Path installDirFileName = installDir.getFileName();
        if (installDirFileName == null) {
            throw new IllegalStateException("Failed to resolve install directory name");
        }
        Path tempDir = installDir.resolveSibling(installDirFileName.toString() + ".tmp");
        deleteRecursivelyQuietly(tempDir);

        boolean extracted = false;
        int scannedEntries = 0;
        long totalExtractedBytes = 0L;
        try {
            Files.createDirectories(tempDir);
            try (JarFile jarFile = new JarFile(bundleJar.toFile())) {
                java.util.Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    scannedEntries++;
                    if (scannedEntries > MAX_ARCHIVE_ENTRY_COUNT) {
                        throw new IllegalStateException("Playwright bundle contains too many archive entries");
                    }
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    if (!entryName.startsWith(prefix)) {
                        continue;
                    }
                    extracted = true;
                    String relativeName = entryName.substring(prefix.length());
                    if (relativeName.isBlank()) {
                        continue;
                    }

                    Path targetPath = tempDir.resolve(relativeName).normalize();
                    if (!targetPath.startsWith(tempDir)) {
                        throw new IllegalStateException("Playwright bundle entry escapes target directory");
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(targetPath);
                        continue;
                    }

                    validateEntryMetadata(entry);
                    Path targetParent = targetPath.getParent();
                    if (targetParent != null) {
                        Files.createDirectories(targetParent);
                    }
                    long writtenBytes = copyEntryWithLimits(jarFile, entry, targetPath, totalExtractedBytes);
                    totalExtractedBytes += writtenBytes;
                }
            }

            if (!extracted) {
                throw new IllegalStateException("Platform " + platform + " is not present in Playwright bundle");
            }

            ensureNodeExecutable(tempDir);
            moveDirectory(tempDir, installDir);
        } catch (IOException e) {
            deleteRecursivelyQuietly(tempDir);
            throw new IllegalStateException("Failed to extract Playwright bundle", e);
        } catch (RuntimeException e) {
            deleteRecursivelyQuietly(tempDir);
            throw e;
        }
    }

    private void validateBundleJarFile(Path bundleJar) {
        if (!Files.isRegularFile(bundleJar)) {
            throw new IllegalStateException("Playwright bundle is not a regular file: " + bundleJar);
        }
        try {
            long bundleSize = Files.size(bundleJar);
            if (bundleSize <= 0L) {
                throw new IllegalStateException("Playwright bundle file is empty: " + bundleJar);
            }
            if (bundleSize > MAX_BUNDLE_JAR_SIZE_BYTES) {
                throw new IllegalStateException("Playwright bundle file is too large: " + bundleSize + " bytes");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect Playwright bundle file: " + bundleJar, e);
        }
    }

    private void validateEntryMetadata(JarEntry entry) {
        long declaredSize = entry.getSize();
        if (declaredSize > MAX_ENTRY_SIZE_BYTES) {
            throw new IllegalStateException("Playwright bundle entry exceeds size limit: " + entry.getName());
        }

        long compressedSize = entry.getCompressedSize();
        if (declaredSize > 0L && compressedSize > 0L) {
            long ratio = declaredSize / compressedSize;
            if (ratio > MAX_COMPRESSION_RATIO) {
                throw new IllegalStateException(
                        "Playwright bundle entry has suspicious compression ratio: " + entry.getName());
            }
        }
    }

    private long copyEntryWithLimits(JarFile jarFile, JarEntry entry, Path targetPath, long extractedSoFar)
            throws IOException {
        long written = 0L;
        try (InputStream input = jarFile.getInputStream(entry);
                OutputStream output = Files.newOutputStream(
                        targetPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
            while (true) {
                int read = input.read(buffer);
                if (read == -1) {
                    break;
                }
                written += read;
                if (written > MAX_ENTRY_SIZE_BYTES) {
                    throw new IllegalStateException("Playwright bundle entry exceeds size limit while extracting: "
                            + entry.getName());
                }
                if (extractedSoFar + written > MAX_TOTAL_EXTRACTED_BYTES) {
                    throw new IllegalStateException("Playwright bundle extraction exceeds total size limit");
                }
                output.write(buffer, 0, read);
            }
        }

        long declaredSize = entry.getSize();
        if (declaredSize >= 0L && declaredSize != written) {
            throw new IllegalStateException("Playwright bundle entry size mismatch: " + entry.getName());
        }
        return written;
    }

    private void ensureNodeExecutable(Path driverDir) {
        Path nodePath = driverDir.resolve("node");
        if (!Files.exists(nodePath)) {
            return;
        }
        try {
            boolean changed = nodePath.toFile().setExecutable(true, false);
            if (!changed) {
                log.debug("[Playwright] Failed to mark node executable: {}", nodePath);
            }
        } catch (SecurityException e) {
            log.debug("[Playwright] Failed to mark node executable: {}", e.getMessage());
        }
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            deleteRecursivelyQuietly(target);
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteRecursivelyQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    private void ensureDirectoryWritable(Path directoryPath) {
        try {
            Files.createDirectories(directoryPath);
        } catch (IOException e) {
            throw new IllegalStateException("Playwright driver directory is not writable: " + directoryPath, e);
        }
    }

    private URI buildBundleUri(String baseRepositoryUrl, String version) {
        String url = baseRepositoryUrl + ARTIFACT_BASE_PATH + version + "/" + ARTIFACT_PREFIX + version
                + ARTIFACT_SUFFIX;
        return URI.create(url);
    }

    private URI requireTrustedMavenUri(URI uri) {
        if (uri == null) {
            throw new IllegalStateException("Playwright bundle URI is missing");
        }
        if (!HTTPS_SCHEME.equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("Only HTTPS Playwright bundle URIs are allowed: " + uri);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalStateException("Playwright bundle URI userinfo is not allowed: " + uri);
        }
        if (uri.getFragment() != null) {
            throw new IllegalStateException("Playwright bundle URI fragment is not allowed: " + uri);
        }
        int port = uri.getPort();
        if (port != -1 && port != HTTPS_PORT) {
            throw new IllegalStateException("Playwright bundle URI port is not allowed: " + uri);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Playwright bundle URI host is missing: " + uri);
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!HOST_MAVEN_APACHE.equals(normalizedHost) && !HOST_MAVEN_ORG.equals(normalizedHost)) {
            throw new IllegalStateException("Playwright bundle URI host is not trusted: " + normalizedHost);
        }
        return uri;
    }

    private void ensureSafeVersion(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("Playwright version is missing");
        }
        if (!SAFE_VERSION_PATTERN.matcher(version).matches()) {
            throw new IllegalStateException("Playwright version contains prohibited characters: " + version);
        }
    }

    private String normalizeVersion(String version) {
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        ensureSafeVersion(normalized);
        return normalized;
    }

    private int compareVersions(String left, String right) {
        Semver leftSemver = parseSemver(left);
        Semver rightSemver = parseSemver(right);

        if (leftSemver == null || rightSemver == null) {
            return left.compareTo(right);
        }
        if (leftSemver.major() != rightSemver.major()) {
            return Integer.compare(leftSemver.major(), rightSemver.major());
        }
        if (leftSemver.minor() != rightSemver.minor()) {
            return Integer.compare(leftSemver.minor(), rightSemver.minor());
        }
        if (leftSemver.patch() != rightSemver.patch()) {
            return Integer.compare(leftSemver.patch(), rightSemver.patch());
        }

        String leftPrerelease = leftSemver.prerelease();
        String rightPrerelease = rightSemver.prerelease();
        if (leftPrerelease == null && rightPrerelease == null) {
            return 0;
        }
        if (leftPrerelease == null) {
            return 1;
        }
        if (rightPrerelease == null) {
            return -1;
        }
        return leftPrerelease.compareTo(rightPrerelease);
    }

    private Semver parseSemver(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = SEMVER_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        return new Semver(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                matcher.group(4));
    }

    private record Semver(int major, int minor, int patch, String prerelease) {
    }
}
