package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.UpdateActionResult;
import me.golemcore.bot.domain.model.UpdateHistoryItem;
import me.golemcore.bot.domain.model.UpdateIntent;
import me.golemcore.bot.domain.model.UpdateState;
import me.golemcore.bot.domain.model.UpdateStatus;
import me.golemcore.bot.domain.model.UpdateVersionInfo;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpdateService {

    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String SHA256_FILE_NAME = "sha256sums.txt";
    private static final String JARS_DIR_NAME = "jars";
    private static final String CURRENT_MARKER_NAME = "current.txt";
    private static final String STAGED_MARKER_NAME = "staged.txt";
    private static final int RESTART_EXIT_CODE = 42;
    private static final int DOWNLOAD_BUFFER_SIZE = 8192;
    private static final int MAX_HISTORY_ITEMS = 100;
    private static final long RESTART_DELAY_MILLIS = 500L;
    private static final int TOKEN_LENGTH = 6;
    private static final String TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern SEMVER_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$");
    private static final Pattern VERSION_EXTRACT_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?)");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final BotProperties botProperties;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final Clock clock;

    private final Object lock = new Object();

    private UpdateState transientState = UpdateState.IDLE;
    private Instant lastCheckAt;
    private String lastCheckError;
    private AvailableRelease availableRelease;
    private PendingIntent pendingIntent;
    private final List<UpdateHistoryItem> history = new ArrayList<>();

    public UpdateStatus getStatus() {
        synchronized (lock) {
            boolean enabled = isEnabled();
            UpdateVersionInfo current = resolveCurrentInfo();
            UpdateVersionInfo staged = resolveStagedInfo();
            UpdateVersionInfo available = resolveAvailableInfo();

            UpdateState effectiveState = resolveState(enabled, staged, available);

            return UpdateStatus.builder()
                    .state(effectiveState)
                    .enabled(enabled)
                    .current(current)
                    .staged(staged)
                    .available(available)
                    .lastCheckAt(lastCheckAt)
                    .lastError(lastCheckError)
                    .build();
        }
    }

    public UpdateActionResult check() {
        synchronized (lock) {
            ensureEnabled();
            transientState = UpdateState.CHECKING;
        }

        try {
            AvailableRelease latestRelease = fetchLatestRelease();
            Instant now = Instant.now(clock);
            synchronized (lock) {
                lastCheckAt = now;
                lastCheckError = null;

                if (latestRelease == null) {
                    availableRelease = null;
                    transientState = UpdateState.IDLE;
                    addHistory("check", null, "SUCCESS", "No releases available");
                    return UpdateActionResult.builder()
                            .success(true)
                            .message("No updates found")
                            .build();
                }

                String currentVersion = getCurrentVersion();
                if (!isRemoteVersionNewer(latestRelease.version(), currentVersion)) {
                    availableRelease = null;
                    transientState = UpdateState.IDLE;
                    addHistory("check", currentVersion, "SUCCESS", "Already on latest version");
                    return UpdateActionResult.builder()
                            .success(true)
                            .message("Already up to date")
                            .version(currentVersion)
                            .build();
                }

                if (!isPatchUpdate(currentVersion, latestRelease.version())) {
                    availableRelease = null;
                    transientState = UpdateState.IDLE;
                    addHistory("check", latestRelease.version(), "SUCCESS",
                            "Minor/major update requires docker image rollout");
                    return UpdateActionResult.builder()
                            .success(true)
                            .message("New major/minor version found. Use Docker image upgrade.")
                            .version(latestRelease.version())
                            .build();
                }

                availableRelease = latestRelease;
                transientState = UpdateState.IDLE;
                addHistory("check", latestRelease.version(), "SUCCESS", "Update is available");

                return UpdateActionResult.builder()
                        .success(true)
                        .message("Update available: " + latestRelease.version())
                        .version(latestRelease.version())
                        .build();
            }
        } catch (Exception e) {
            String message = "Failed to check updates: " + safeMessage(e);
            synchronized (lock) {
                lastCheckError = message;
                transientState = UpdateState.FAILED;
                addHistory("check", null, "FAILED", message);
            }
            throw new IllegalStateException(message, e);
        }
    }

    public UpdateActionResult prepare() {
        AvailableRelease release;
        synchronized (lock) {
            ensureEnabled();
            if (availableRelease == null) {
                throw new IllegalStateException("No available update. Run check first.");
            }
            transientState = UpdateState.PREPARING;
            release = availableRelease;
        }

        Path tempJar = null;
        try {
            validateAssetName(release.assetName());
            Path jarsDir = getJarsDir();
            Files.createDirectories(jarsDir);

            Path targetJar = resolveJarPath(release.assetName());
            tempJar = targetJar.resolveSibling(release.assetName() + ".tmp");

            downloadToFile(release.assetUrl(), tempJar);
            String checksumsText = downloadToString(release.sha256Url());
            String expectedHash = extractExpectedSha256(checksumsText, release.assetName());
            String actualHash = computeSha256(tempJar);

            if (!expectedHash.equalsIgnoreCase(actualHash)) {
                Files.deleteIfExists(tempJar);
                throw new IllegalStateException("Checksum mismatch for " + release.assetName());
            }

            moveAtomically(tempJar, targetJar);
            writeMarker(getStagedMarkerPath(), release.assetName());
            cleanupOldJars();

            synchronized (lock) {
                transientState = UpdateState.IDLE;
                addHistory("prepare", release.version(), "SUCCESS", "Update staged");
            }

            return UpdateActionResult.builder()
                    .success(true)
                    .message("Update staged: " + release.version())
                    .version(release.version())
                    .build();
        } catch (Exception e) {
            if (tempJar != null) {
                try {
                    Files.deleteIfExists(tempJar);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
            String message = "Failed to prepare update: " + safeMessage(e);
            synchronized (lock) {
                transientState = UpdateState.FAILED;
                lastCheckError = message;
                addHistory("prepare", release.version(), "FAILED", message);
            }
            throw new IllegalStateException(message, e);
        }
    }

    public UpdateIntent createApplyIntent() {
        synchronized (lock) {
            ensureEnabled();
            UpdateVersionInfo staged = resolveStagedInfo();
            if (staged == null) {
                throw new IllegalStateException("No staged update to apply");
            }

            PendingIntent intent = buildPendingIntent("apply", staged.getVersion());
            pendingIntent = intent;

            return UpdateIntent.builder()
                    .operation(intent.operation())
                    .targetVersion(intent.targetVersion())
                    .confirmToken(intent.confirmToken())
                    .expiresAt(intent.expiresAt())
                    .build();
        }
    }

    public UpdateActionResult apply(String confirmToken) {
        synchronized (lock) {
            ensureEnabled();
            validatePendingIntent("apply", confirmToken, null);

            UpdateVersionInfo staged = resolveStagedInfo();
            if (staged == null || staged.getAssetName() == null || staged.getAssetName().isBlank()) {
                throw new IllegalStateException("No staged update to apply");
            }

            writeMarker(getCurrentMarkerPath(), staged.getAssetName());
            deleteMarker(getStagedMarkerPath());
            pendingIntent = null;
            transientState = UpdateState.APPLYING;
            addHistory("apply", staged.getVersion(), "SUCCESS", "Scheduled JVM restart");

            requestRestartAsync();

            return UpdateActionResult.builder()
                    .success(true)
                    .message("Update " + staged.getVersion() + " is being applied. JVM restart scheduled.")
                    .version(staged.getVersion())
                    .build();
        }
    }

    public UpdateIntent createRollbackIntent(String requestedVersion) {
        synchronized (lock) {
            ensureEnabled();
            String normalizedVersion = normalizeOptionalVersion(requestedVersion);
            if (normalizedVersion != null) {
                findJarAssetNameByVersion(normalizedVersion);
            }

            PendingIntent intent = buildPendingIntent("rollback", normalizedVersion);
            pendingIntent = intent;

            return UpdateIntent.builder()
                    .operation(intent.operation())
                    .targetVersion(intent.targetVersion())
                    .confirmToken(intent.confirmToken())
                    .expiresAt(intent.expiresAt())
                    .build();
        }
    }

    public UpdateActionResult rollback(String confirmToken, String requestedVersion) {
        synchronized (lock) {
            ensureEnabled();
            String normalizedVersion = normalizeOptionalVersion(requestedVersion);
            validatePendingIntent("rollback", confirmToken, normalizedVersion);

            String targetVersion = pendingIntent.targetVersion();
            String versionToUse = targetVersion != null ? targetVersion : normalizedVersion;

            if (versionToUse == null) {
                deleteMarker(getCurrentMarkerPath());
                deleteMarker(getStagedMarkerPath());
                pendingIntent = null;
                transientState = UpdateState.ROLLED_BACK;
                addHistory("rollback", null, "SUCCESS", "Rolled back to image version");

                requestRestartAsync();
                return UpdateActionResult.builder()
                        .success(true)
                        .message("Rollback to image version scheduled. JVM restart in progress.")
                        .build();
            }

            String assetName = findJarAssetNameByVersion(versionToUse);
            writeMarker(getCurrentMarkerPath(), assetName);
            deleteMarker(getStagedMarkerPath());
            pendingIntent = null;
            transientState = UpdateState.ROLLED_BACK;
            addHistory("rollback", versionToUse, "SUCCESS", "Rolled back to cached jar " + assetName);

            requestRestartAsync();
            return UpdateActionResult.builder()
                    .success(true)
                    .message("Rollback to " + versionToUse + " scheduled. JVM restart in progress.")
                    .version(versionToUse)
                    .build();
        }
    }

    public List<UpdateHistoryItem> getHistory() {
        synchronized (lock) {
            return new ArrayList<>(history);
        }
    }

    public boolean isEnabled() {
        BotProperties.UpdateProperties config = botProperties.getUpdate();
        return config.isEnabled() && config.getGithubRepo() != null && !config.getGithubRepo().isBlank();
    }

    private UpdateState resolveState(boolean enabled, UpdateVersionInfo staged, UpdateVersionInfo available) {
        if (!enabled) {
            return UpdateState.DISABLED;
        }
        if (transientState == UpdateState.CHECKING
                || transientState == UpdateState.PREPARING
                || transientState == UpdateState.APPLYING
                || transientState == UpdateState.VERIFYING
                || transientState == UpdateState.ROLLED_BACK) {
            return transientState;
        }
        if (staged != null) {
            return UpdateState.STAGED;
        }
        if (available != null) {
            return UpdateState.AVAILABLE;
        }
        if (lastCheckError != null && !lastCheckError.isBlank()) {
            return UpdateState.FAILED;
        }
        return UpdateState.IDLE;
    }

    private UpdateVersionInfo resolveCurrentInfo() {
        String currentVersion = getCurrentVersion();
        String marker = readMarker(getCurrentMarkerPath());
        if (marker == null) {
            return UpdateVersionInfo.builder()
                    .version(currentVersion)
                    .source("image")
                    .build();
        }

        try {
            Path jarPath = resolveJarPath(marker);
            if (Files.exists(jarPath)) {
                String version = extractVersionFromAssetName(marker);
                return UpdateVersionInfo.builder()
                        .version(version != null ? version : currentVersion)
                        .source("jar")
                        .assetName(marker)
                        .build();
            }
        } catch (Exception e) {
            log.warn("[update] Invalid current marker value: {}", marker);
        }

        return UpdateVersionInfo.builder()
                .version(currentVersion)
                .source("image")
                .build();
    }

    private UpdateVersionInfo resolveStagedInfo() {
        String marker = readMarker(getStagedMarkerPath());
        if (marker == null) {
            return null;
        }

        try {
            Path jarPath = resolveJarPath(marker);
            if (!Files.exists(jarPath)) {
                return null;
            }
            Instant preparedAt = Files.getLastModifiedTime(getStagedMarkerPath()).toInstant();
            String version = extractVersionFromAssetName(marker);
            return UpdateVersionInfo.builder()
                    .version(version)
                    .assetName(marker)
                    .preparedAt(preparedAt)
                    .build();
        } catch (Exception e) {
            log.warn("[update] Invalid staged marker value: {}", marker);
            return null;
        }
    }

    private UpdateVersionInfo resolveAvailableInfo() {
        if (availableRelease == null) {
            return null;
        }
        return UpdateVersionInfo.builder()
                .version(availableRelease.version())
                .tag(availableRelease.tagName())
                .assetName(availableRelease.assetName())
                .publishedAt(availableRelease.publishedAt())
                .build();
    }

    private void ensureEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException("Update feature is disabled or github repo is not configured");
        }
    }

    private AvailableRelease fetchLatestRelease() throws IOException, InterruptedException {
        BotProperties.UpdateProperties config = botProperties.getUpdate();
        String repo = config.getGithubRepo();
        String apiUrl = GITHUB_API_BASE + "/repos/" + repo + "/releases/latest";

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiUrl))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "golemcore-bot-updater");

        if (config.getGithubToken() != null && !config.getGithubToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.getGithubToken().trim());
        }

        HttpResponse<String> response = buildHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();

        if (statusCode == 404) {
            return null;
        }
        if (statusCode >= 400) {
            throw new IllegalStateException("GitHub API returned " + statusCode);
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode assets = root.path("assets");
        if (!assets.isArray()) {
            throw new IllegalStateException("Release assets are missing");
        }

        String assetPattern = config.getAssetPattern() == null || config.getAssetPattern().isBlank()
                ? "bot-*.jar"
                : config.getAssetPattern();
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + assetPattern);

        JsonNode jarAssetNode = null;
        JsonNode shaAssetNode = null;
        for (JsonNode assetNode : assets) {
            String assetName = assetNode.path("name").asText("");
            if (matcher.matches(Path.of(assetName))) {
                jarAssetNode = assetNode;
            }
            if (SHA256_FILE_NAME.equals(assetName)) {
                shaAssetNode = assetNode;
            }
        }

        if (jarAssetNode == null) {
            return null;
        }
        if (shaAssetNode == null) {
            throw new IllegalStateException("sha256sums.txt not found in release assets");
        }

        String tagName = root.path("tag_name").asText("");
        String assetName = jarAssetNode.path("name").asText("");
        String version = extractVersion(tagName, assetName);
        String assetUrl = jarAssetNode.path("browser_download_url").asText("");
        String shaUrl = shaAssetNode.path("browser_download_url").asText("");
        Instant publishedAt = parseInstant(root.path("published_at").asText(null));

        if (assetUrl.isBlank() || shaUrl.isBlank()) {
            throw new IllegalStateException("Release assets contain empty download URL");
        }

        return new AvailableRelease(version, tagName, assetName, assetUrl, shaUrl, publishedAt);
    }

    private PendingIntent buildPendingIntent(String operation, String targetVersion) {
        Instant expiresAt = Instant.now(clock).plus(botProperties.getUpdate().getConfirmTtl());
        String token = generateToken();
        return new PendingIntent(operation, targetVersion, token, expiresAt);
    }

    private void validatePendingIntent(String expectedOperation, String confirmToken, String requestedVersion) {
        if (confirmToken == null || confirmToken.isBlank()) {
            throw new IllegalArgumentException("confirmToken is required");
        }
        if (pendingIntent == null) {
            throw new IllegalStateException("No pending confirmation intent");
        }
        if (!expectedOperation.equalsIgnoreCase(pendingIntent.operation())) {
            throw new IllegalStateException("Pending confirmation is for operation: " + pendingIntent.operation());
        }
        if (Instant.now(clock).isAfter(pendingIntent.expiresAt())) {
            pendingIntent = null;
            throw new IllegalStateException("Confirmation token has expired");
        }
        if (!pendingIntent.confirmToken().equals(confirmToken.trim())) {
            throw new IllegalArgumentException("Invalid confirmation token");
        }

        String pendingTargetVersion = pendingIntent.targetVersion();
        String normalizedRequested = normalizeOptionalVersion(requestedVersion);
        if (pendingTargetVersion != null && normalizedRequested != null
                && !pendingTargetVersion.equals(normalizedRequested)) {
            throw new IllegalArgumentException("Confirmation token was issued for another version");
        }
    }

    private String getCurrentVersion() {
        BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        if (buildProperties == null || buildProperties.getVersion() == null || buildProperties.getVersion().isBlank()) {
            return "dev";
        }
        return normalizeVersion(buildProperties.getVersion());
    }

    private Path getUpdatesDir() {
        String configuredPath = botProperties.getUpdate().getUpdatesPath();
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
        } catch (IOException e) {
            return null;
        }
    }

    private void writeMarker(Path markerPath, String value) {
        try {
            Files.createDirectories(markerPath.getParent());
            Files.writeString(
                    markerPath,
                    value + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write marker file: " + markerPath, e);
        }
    }

    private void deleteMarker(Path markerPath) {
        try {
            Files.deleteIfExists(markerPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete marker file: " + markerPath, e);
        }
    }

    private void cleanupOldJars() throws IOException {
        int maxKeptVersions = botProperties.getUpdate().getMaxKeptVersions();
        if (maxKeptVersions <= 0) {
            return;
        }

        Path jarsDir = getJarsDir();
        if (!Files.exists(jarsDir)) {
            return;
        }

        String currentAsset = readMarker(getCurrentMarkerPath());
        String stagedAsset = readMarker(getStagedMarkerPath());

        List<Path> jarFiles;
        try (java.util.stream.Stream<Path> stream = Files.list(jarsDir)) {
            jarFiles = stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .toList();
        }

        int kept = 0;
        for (Path file : jarFiles) {
            String fileName = file.getFileName().toString();
            boolean isProtected = fileName.equals(currentAsset) || fileName.equals(stagedAsset);
            if (isProtected || kept < maxKeptVersions) {
                kept++;
                continue;
            }
            Files.deleteIfExists(file);
        }
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void downloadToFile(String url, Path targetPath) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "golemcore-bot-updater");

        String githubToken = botProperties.getUpdate().getGithubToken();
        if (githubToken != null && !githubToken.isBlank()) {
            builder.header("Authorization", "Bearer " + githubToken.trim());
        }

        HttpResponse<InputStream> response = buildHttpClient().send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Download failed with HTTP " + response.statusCode());
        }

        Files.createDirectories(targetPath.getParent());
        try (InputStream inputStream = response.body()) {
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String downloadToString(String url) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "golemcore-bot-updater");

        String githubToken = botProperties.getUpdate().getGithubToken();
        if (githubToken != null && !githubToken.isBlank()) {
            builder.header("Authorization", "Bearer " + githubToken.trim());
        }

        HttpResponse<String> response = buildHttpClient().send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Download failed with HTTP " + response.statusCode());
        }
        return response.body();
    }

    private HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    private String extractExpectedSha256(String checksumsText, String assetName) {
        String[] lines = checksumsText.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            String candidateFile = parts[1];
            if (candidateFile.startsWith("*")) {
                candidateFile = candidateFile.substring(1);
            }
            if (assetName.equals(candidateFile)) {
                return parts[0].toLowerCase(Locale.ROOT);
            }
        }
        throw new IllegalStateException("Unable to find checksum for asset: " + assetName);
    }

    private String computeSha256(Path filePath) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }

        try (InputStream inputStream = Files.newInputStream(filePath, StandardOpenOption.READ)) {
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            byte[] hash = digest.digest();
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to compute SHA-256", e);
        }
    }

    private String generateToken() {
        StringBuilder builder = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            int index = SECURE_RANDOM.nextInt(TOKEN_ALPHABET.length());
            builder.append(TOKEN_ALPHABET.charAt(index));
        }
        return builder.toString();
    }

    private String extractVersion(String tagName, String assetName) {
        String normalizedTag = normalizeOptionalVersion(tagName);
        if (normalizedTag != null) {
            return normalizedTag;
        }

        String fromAsset = extractVersionFromAssetName(assetName);
        if (fromAsset != null) {
            return fromAsset;
        }

        return getCurrentVersion();
    }

    private String extractVersionFromAssetName(String assetName) {
        if (assetName == null) {
            return null;
        }
        Matcher matcher = VERSION_EXTRACT_PATTERN.matcher(assetName);
        if (!matcher.find()) {
            return null;
        }
        return normalizeVersion(matcher.group(1));
    }

    private String normalizeOptionalVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        return normalizeVersion(version);
    }

    private String normalizeVersion(String version) {
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private boolean isRemoteVersionNewer(String remoteVersion, String currentVersion) {
        int comparison = compareVersions(remoteVersion, currentVersion);
        return comparison > 0;
    }

    private boolean isPatchUpdate(String currentVersion, String remoteVersion) {
        Semver current = parseSemver(currentVersion);
        Semver remote = parseSemver(remoteVersion);

        if (current == null || remote == null) {
            return false;
        }

        return current.major == remote.major
                && current.minor == remote.minor
                && remote.patch > current.patch;
    }

    private int compareVersions(String left, String right) {
        Semver leftSemver = parseSemver(left);
        Semver rightSemver = parseSemver(right);

        if (leftSemver == null || rightSemver == null) {
            return normalizeVersion(left).compareTo(normalizeVersion(right));
        }

        if (leftSemver.major != rightSemver.major) {
            return Integer.compare(leftSemver.major, rightSemver.major);
        }
        if (leftSemver.minor != rightSemver.minor) {
            return Integer.compare(leftSemver.minor, rightSemver.minor);
        }
        if (leftSemver.patch != rightSemver.patch) {
            return Integer.compare(leftSemver.patch, rightSemver.patch);
        }

        String leftPrerelease = leftSemver.prerelease;
        String rightPrerelease = rightSemver.prerelease;
        if (leftPrerelease == null && rightPrerelease == null) {
            return 0;
        }
        if (leftPrerelease == null) {
            return 1;
        }
        if (rightPrerelease == null) {
            return -1;
        }

        return comparePrerelease(leftPrerelease, rightPrerelease);
    }

    private int comparePrerelease(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int max = Math.max(leftParts.length, rightParts.length);

        for (int i = 0; i < max; i++) {
            if (i >= leftParts.length) {
                return -1;
            }
            if (i >= rightParts.length) {
                return 1;
            }

            String leftPart = leftParts[i];
            String rightPart = rightParts[i];

            boolean leftNumeric = leftPart.matches("\\d+");
            boolean rightNumeric = rightPart.matches("\\d+");

            if (leftNumeric && rightNumeric) {
                int cmp = Integer.compare(Integer.parseInt(leftPart), Integer.parseInt(rightPart));
                if (cmp != 0) {
                    return cmp;
                }
                continue;
            }
            if (leftNumeric) {
                return -1;
            }
            if (rightNumeric) {
                return 1;
            }

            int cmp = leftPart.compareTo(rightPart);
            if (cmp != 0) {
                return cmp;
            }
        }

        return 0;
    }

    private Semver parseSemver(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }

        String normalized = normalizeVersion(version);
        int plusIndex = normalized.indexOf('+');
        if (plusIndex >= 0) {
            normalized = normalized.substring(0, plusIndex);
        }

        Matcher matcher = SEMVER_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }

        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = Integer.parseInt(matcher.group(3));
        String prerelease = matcher.group(4);

        return new Semver(major, minor, patch, prerelease);
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String findJarAssetNameByVersion(String targetVersion) {
        Path jarsDir = getJarsDir();
        if (!Files.exists(jarsDir)) {
            throw new IllegalArgumentException("No cached update jars found");
        }

        try (java.util.stream.Stream<Path> stream = Files.list(jarsDir)) {
            List<Path> files = stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                    .toList();

            for (Path path : files) {
                String fileName = path.getFileName().toString();
                String version = extractVersionFromAssetName(fileName);
                if (targetVersion.equals(version)) {
                    return fileName;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect cached jars", e);
        }

        throw new IllegalArgumentException("Version " + targetVersion + " is not cached locally");
    }

    private void addHistory(String operation, String version, String result, String message) {
        UpdateHistoryItem item = UpdateHistoryItem.builder()
                .operation(operation)
                .version(version)
                .timestamp(Instant.now(clock))
                .result(result)
                .message(message)
                .build();

        history.add(0, item);
        while (history.size() > MAX_HISTORY_ITEMS) {
            history.remove(history.size() - 1);
        }
    }

    private void requestRestartAsync() {
        Thread restartThread = new Thread(() -> {
            try {
                Thread.sleep(RESTART_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            synchronized (lock) {
                transientState = UpdateState.VERIFYING;
            }

            int exitCode = SpringApplication.exit(applicationContext, () -> RESTART_EXIT_CODE);
            System.exit(exitCode);
        }, "update-restart-thread");

        restartThread.setDaemon(false);
        restartThread.start();
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "unknown error";
        }
        return throwable.getMessage();
    }

    private record Semver(int major, int minor, int patch, String prerelease) {
    }

    private record AvailableRelease(
            String version,
            String tagName,
            String assetName,
            String assetUrl,
            String sha256Url,
            Instant publishedAt) {
    }

    private record PendingIntent(
            String operation,
            String targetVersion,
            String confirmToken,
            Instant expiresAt) {
    }
}
