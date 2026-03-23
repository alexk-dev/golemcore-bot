package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.UpdateActionResult;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpdateService {

    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String GITHUB_API_HOST = "api.github.com";
    private static final String GITHUB_HOST = "github.com";
    private static final String GITHUB_USERCONTENT_SUFFIX = ".githubusercontent.com";
    private static final String HTTPS_SCHEME = "https";
    private static final int HTTPS_PORT = 443;
    private static final String RELEASE_REPOSITORY = "alexk-dev/golemcore-bot";
    private static final String RELEASE_ASSET_API_PATH = "/repos/" + RELEASE_REPOSITORY + "/releases/assets/";
    private static final String RELEASE_ASSET_PATTERN = "bot-*.jar";
    private static final String SHA256_FILE_NAME = "sha256sums.txt";
    private static final String JARS_DIR_NAME = "jars";
    private static final String CURRENT_MARKER_NAME = "current.txt";
    private static final String STAGED_MARKER_NAME = "staged.txt";
    private static final String UPDATE_PATH_ENV = "UPDATE_PATH";
    private static final int RESTART_EXIT_CODE = 42;
    private static final int DOWNLOAD_BUFFER_SIZE = 8192;
    private static final long RESTART_DELAY_MILLIS = 500L;
    private static final int AUTO_UPDATE_TICK_INTERVAL_SECONDS = 60;
    private static final Pattern SEMVER_PATTERN = Pattern
            .compile("^(\\d++)\\.(\\d++)\\.(\\d++)(?:-([0-9A-Za-z.-]++))?$");
    private static final Pattern SAFE_RELEASE_SEGMENT_PATTERN = Pattern.compile("^[0-9A-Za-z._-]+$");

    private final BotProperties botProperties;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final Clock clock;
    private final JvmExitService jvmExitService;
    private final RuntimeConfigService runtimeConfigService;
    private final UpdateActivityGate updateActivityGate;
    private final UpdateMaintenanceWindow updateMaintenanceWindow;

    private final Object lock = new Object();
    private ScheduledExecutorService autoUpdateScheduler;
    private ScheduledFuture<?> autoUpdateTask;

    private UpdateState transientState = UpdateState.IDLE;
    private Instant lastCheckAt;
    private String lastCheckError = "";
    private Optional<AvailableRelease> availableRelease = Optional.empty();
    private Optional<UpdateVersionInfo> activeTarget = Optional.empty();

    @PostConstruct
    void initAutoUpdateScheduler() {
        if (!isEnabled()) {
            return;
        }

        autoUpdateScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "update-auto-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        autoUpdateTask = autoUpdateScheduler.scheduleWithFixedDelay(
                this::runAutoUpdateCycleSafely,
                AUTO_UPDATE_TICK_INTERVAL_SECONDS,
                AUTO_UPDATE_TICK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdownAutoUpdateScheduler() {
        if (autoUpdateTask != null) {
            autoUpdateTask.cancel(false);
        }
        if (autoUpdateScheduler != null) {
            autoUpdateScheduler.shutdownNow();
        }
    }

    public UpdateStatus getStatus() {
        synchronized (lock) {
            boolean enabled = isEnabled();
            UpdateVersionInfo current = resolveCurrentInfo();
            UpdateVersionInfo staged = resolveStagedInfo();
            UpdateVersionInfo available = resolveAvailableInfo();
            UpdateState effectiveState = resolveState(enabled, staged, available);
            UpdateVersionInfo target = resolveTargetInfo(effectiveState, staged, available);
            StagePresentation stagePresentation = buildStagePresentation(effectiveState, current, target);
            UpdateActivityGate.Result activityStatus = updateActivityGate.getStatus();
            UpdateMaintenanceWindow.Status windowStatus = evaluateMaintenanceWindow();

            return UpdateStatus.builder()
                    .state(effectiveState)
                    .enabled(enabled)
                    .autoEnabled(runtimeConfigService.isAutoUpdateEnabled())
                    .maintenanceWindowEnabled(runtimeConfigService.isUpdateMaintenanceWindowEnabled())
                    .maintenanceWindowStartUtc(runtimeConfigService.getUpdateMaintenanceWindowStartUtc())
                    .maintenanceWindowEndUtc(runtimeConfigService.getUpdateMaintenanceWindowEndUtc())
                    .serverTimezone("UTC")
                    .windowOpen(windowStatus.open())
                    .busy(activityStatus.busy())
                    .blockedReason(activityStatus.blockedReason())
                    .nextEligibleAt(windowStatus.nextEligibleAt())
                    .current(current)
                    .target(target)
                    .staged(staged)
                    .available(available)
                    .lastCheckAt(lastCheckAt)
                    .lastError(lastCheckError == null || lastCheckError.isBlank() ? null : lastCheckError)
                    .progressPercent(stagePresentation.progressPercent())
                    .stageTitle(stagePresentation.title())
                    .stageDescription(stagePresentation.description())
                    .build();
        }
    }

    public UpdateActionResult check() {
        synchronized (lock) {
            ensureEnabled();
            ensureNoBusyOperation();
            activeTarget = Optional.empty();
            transientState = UpdateState.CHECKING;
        }

        try {
            CheckResolution resolution = resolveCheck();
            return UpdateActionResult.builder()
                    .success(true)
                    .message(resolution.message())
                    .version(resolution.version())
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw handleCheckFailure(e);
        } catch (IOException | RuntimeException e) {
            throw handleCheckFailure(e);
        }
    }

    public RuntimeConfig.UpdateConfig getConfig() {
        RuntimeConfig.UpdateConfig config = runtimeConfigService.getRuntimeConfigForApi().getUpdate();
        return config != null ? config : new RuntimeConfig.UpdateConfig();
    }

    public RuntimeConfig.UpdateConfig updateConfig(RuntimeConfig.UpdateConfig updateConfig) {
        RuntimeConfig current = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig copy = copyRuntimeConfig(current);
        copy.setUpdate(updateConfig != null ? updateConfig : new RuntimeConfig.UpdateConfig());
        runtimeConfigService.updateRuntimeConfig(copy);
        return getConfig();
    }

    private UpdateActionResult prepare() {
        AvailableRelease release;
        synchronized (lock) {
            ensureEnabled();
            release = availableRelease
                    .orElseThrow(() -> new IllegalStateException("No available update. Run check first."));
            activeTarget = Optional.of(toVersionInfo(release));
            transientState = UpdateState.PREPARING;
        }

        Path tempJar = null;
        try {
            validateAssetName(release.assetName());
            Path jarsDir = getJarsDir();
            ensureUpdateDirectoryWritable(jarsDir);

            Path targetJar = resolveJarPath(release.assetName());
            tempJar = targetJar.resolveSibling(release.assetName() + ".tmp");

            downloadReleaseAsset(release.assetId(), tempJar);
            String checksumsText = downloadReleaseChecksums(release.checksumAssetId());
            String expectedHash = extractExpectedSha256(checksumsText, release.assetName());
            String actualHash = computeSha256(tempJar);

            if (!expectedHash.equalsIgnoreCase(actualHash)) {
                Files.deleteIfExists(tempJar);
                throw new IllegalStateException("Checksum mismatch for " + release.assetName());
            }

            moveAtomically(tempJar, targetJar);
            writeMarker(getStagedMarkerPath(), release.assetName());

            synchronized (lock) {
                availableRelease = Optional.empty();
                activeTarget = Optional.of(UpdateVersionInfo.builder()
                        .version(release.version())
                        .tag(release.tagName())
                        .assetName(release.assetName())
                        .preparedAt(Files.getLastModifiedTime(getStagedMarkerPath()).toInstant())
                        .publishedAt(release.publishedAt())
                        .build());
                transientState = UpdateState.IDLE;
            }

            return UpdateActionResult.builder()
                    .success(true)
                    .message("Update staged: " + release.version())
                    .version(release.version())
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw handlePrepareFailure(tempJar, e);
        } catch (IOException | RuntimeException e) {
            throw handlePrepareFailure(tempJar, e);
        }
    }

    public UpdateActionResult updateNow() {
        Optional<UpdateVersionInfo> targetVersion = Optional.empty();
        synchronized (lock) {
            ensureEnabled();
            ensureNoBusyOperation();
            ensureNoRuntimeWorkInProgress();
            lastCheckError = "";

            UpdateVersionInfo staged = resolveStagedInfo();
            if (staged != null) {
                activeTarget = Optional.of(copyVersionInfo(staged));
                transientState = UpdateState.APPLYING;
                targetVersion = Optional.of(copyVersionInfo(staged));
            } else if (availableRelease.isPresent()) {
                AvailableRelease release = availableRelease.orElseThrow();
                UpdateVersionInfo availableTarget = toVersionInfo(release);
                activeTarget = Optional.of(availableTarget);
                transientState = UpdateState.PREPARING;
                targetVersion = Optional.of(copyVersionInfo(availableTarget));
            } else {
                activeTarget = Optional.empty();
                transientState = UpdateState.CHECKING;
            }
        }

        startUpdateTask(this::runUpdateNowWorkflow);

        String version = targetVersion.map(UpdateVersionInfo::getVersion).orElse(null);
        String message = version == null
                ? "Update workflow started. Checking the latest release."
                : "Update workflow started for " + version + ". Page will reload after restart.";
        return UpdateActionResult.builder()
                .success(true)
                .message(message)
                .version(version)
                .build();
    }

    private UpdateActionResult applyStagedUpdate() {
        synchronized (lock) {
            ensureEnabled();

            UpdateVersionInfo staged = resolveStagedInfo();
            if (staged == null || staged.getAssetName() == null || staged.getAssetName().isBlank()) {
                throw new IllegalStateException("No staged update to apply");
            }

            activeTarget = Optional.of(copyVersionInfo(staged));
            availableRelease = Optional.empty();
            writeMarker(getCurrentMarkerPath(), staged.getAssetName());
            deleteMarker(getStagedMarkerPath());
            transientState = UpdateState.APPLYING;

            requestRestartAsync();

            return UpdateActionResult.builder()
                    .success(true)
                    .message("Update " + staged.getVersion() + " is being applied. JVM restart scheduled.")
                    .version(staged.getVersion())
                    .build();
        }
    }

    public boolean isEnabled() {
        return botProperties.getUpdate().isEnabled();
    }

    private UpdateState resolveState(boolean enabled, UpdateVersionInfo staged, UpdateVersionInfo available) {
        if (!enabled) {
            return UpdateState.DISABLED;
        }
        if (transientState == UpdateState.FAILED) {
            return UpdateState.FAILED;
        }
        if (transientState == UpdateState.CHECKING
                || transientState == UpdateState.PREPARING
                || transientState == UpdateState.APPLYING
                || transientState == UpdateState.VERIFYING) {
            return transientState;
        }
        if ((transientState == UpdateState.WAITING_FOR_WINDOW || transientState == UpdateState.WAITING_FOR_IDLE)
                && (staged != null || activeTarget.isPresent())) {
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
        if (marker != null) {
            try {
                Path jarPath = resolveJarPath(marker);
                if (Files.exists(jarPath)) {
                    String markerVersion = extractVersionFromAssetName(marker);
                    if (markerVersion != null
                            && normalizeVersion(markerVersion).equals(normalizeVersion(currentVersion))) {
                        return UpdateVersionInfo.builder()
                                .version(currentVersion)
                                .source("jar")
                                .assetName(marker)
                                .build();
                    }
                }
            } catch (IllegalArgumentException e) {
                log.warn("[update] Invalid current marker value: {}", marker);
            }
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
        } catch (IOException | IllegalArgumentException e) {
            log.warn("[update] Invalid staged marker value: {}", marker);
            return null;
        }
    }

    private UpdateVersionInfo resolveAvailableInfo() {
        return availableRelease.map(release -> UpdateVersionInfo.builder()
                .version(release.version())
                .tag(release.tagName())
                .assetName(release.assetName())
                .publishedAt(release.publishedAt())
                .build())
                .orElse(null);
    }

    private void ensureEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException("Update feature is disabled");
        }
    }

    private void ensureNoBusyOperation() {
        if (transientState == UpdateState.CHECKING
                || transientState == UpdateState.PREPARING
                || transientState == UpdateState.APPLYING
                || transientState == UpdateState.VERIFYING) {
            throw new IllegalStateException("Another update operation is already in progress");
        }
    }

    private void ensureNoRuntimeWorkInProgress() {
        if (updateActivityGate.getStatus().busy()) {
            throw new IllegalStateException("Update is blocked while runtime work is still running");
        }
    }

    private CheckResolution resolveCheck() throws IOException, InterruptedException {
        AvailableRelease latestRelease = fetchLatestRelease();
        Instant now = Instant.now(clock);
        synchronized (lock) {
            lastCheckAt = now;
            lastCheckError = "";
            activeTarget = Optional.empty();

            if (latestRelease == null) {
                availableRelease = Optional.empty();
                transientState = UpdateState.IDLE;
                return new CheckResolution(null, "No updates found", null);
            }

            String currentVersion = getCurrentVersion();
            if (!isRemoteVersionNewer(latestRelease.version(), currentVersion)) {
                availableRelease = Optional.empty();
                transientState = UpdateState.IDLE;
                return new CheckResolution(null, "Already up to date", currentVersion);
            }

            if (!isSameMajorUpdate(currentVersion, latestRelease.version())) {
                availableRelease = Optional.empty();
                transientState = UpdateState.IDLE;
                return new CheckResolution(null, "New major version found. Use Docker image upgrade.",
                        latestRelease.version());
            }

            availableRelease = Optional.of(latestRelease);
            activeTarget = Optional.of(toVersionInfo(latestRelease));
            transientState = UpdateState.IDLE;
            return new CheckResolution(latestRelease, "Update available: " + latestRelease.version(),
                    latestRelease.version());
        }
    }

    private AvailableRelease fetchLatestRelease() throws IOException, InterruptedException {
        String apiUrl = GITHUB_API_BASE + "/repos/" + RELEASE_REPOSITORY + "/releases/latest";

        URI apiUri = requireTrustedApiUri(URI.create(apiUrl));
        HttpRequest.Builder builder = HttpRequest.newBuilder(apiUri)
                .GET()
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "golemcore-bot-updater");

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

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + RELEASE_ASSET_PATTERN);

        JsonNode jarAssetNode = null;
        Long jarAssetId = null;
        Long checksumAssetId = null;
        for (JsonNode assetNode : assets) {
            String assetName = assetNode.path("name").asText("");
            if (matcher.matches(Path.of(assetName))) {
                jarAssetNode = assetNode;
                jarAssetId = extractAssetId(assetNode, assetName);
            }
            if (SHA256_FILE_NAME.equals(assetName)) {
                checksumAssetId = extractAssetId(assetNode, assetName);
            }
        }

        if (jarAssetNode == null) {
            return null;
        }
        if (jarAssetId == null) {
            throw new IllegalStateException("Release asset id is missing");
        }
        if (checksumAssetId == null) {
            throw new IllegalStateException("sha256sums.txt not found in release assets");
        }

        String tagName = root.path("tag_name").asText("");
        String assetName = jarAssetNode.path("name").asText("");
        String version = extractVersion(tagName, assetName);
        Instant publishedAt = parseInstant(root.path("published_at").asText(null));

        validateReleaseTag(tagName);
        validateAssetName(assetName);
        return new AvailableRelease(version, tagName, assetName, jarAssetId, checksumAssetId, publishedAt);
    }

    private void validateReleaseTag(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            throw new IllegalStateException("Release tag is missing");
        }
        String normalizedTag = tagName.trim();
        if (!SAFE_RELEASE_SEGMENT_PATTERN.matcher(normalizedTag).matches()) {
            throw new IllegalStateException("Release tag contains prohibited characters: " + normalizedTag);
        }
    }

    private long extractAssetId(JsonNode assetNode, String assetName) {
        JsonNode idNode = assetNode.path("id");
        if (!idNode.isIntegralNumber()) {
            throw new IllegalStateException("Release asset id is missing for " + assetName);
        }
        long assetId = idNode.asLong();
        if (assetId <= 0) {
            throw new IllegalStateException("Release asset id is invalid for " + assetName);
        }
        return assetId;
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

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void downloadReleaseAsset(long assetId, Path targetPath)
            throws IOException, InterruptedException {
        URI downloadUri = requireTrustedApiUri(buildReleaseAssetApiUri(assetId));
        HttpRequest.Builder builder = HttpRequest.newBuilder(downloadUri)
                .GET()
                .timeout(Duration.ofMinutes(5))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "golemcore-bot-updater");

        HttpResponse<InputStream> response = buildHttpClient().send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        response = followTrustedRedirectForBinary(downloadUri, response, Duration.ofMinutes(5));
        int statusCode = response.statusCode();
        if (isRedirectStatus(statusCode) || statusCode >= 400) {
            throw new IllegalStateException("Download failed with HTTP " + statusCode);
        }

        Path parent = targetPath.getParent();
        if (parent == null) {
            throw new IllegalStateException("Failed to resolve target parent path: " + targetPath);
        }
        ensureUpdateDirectoryWritable(parent);
        try (InputStream inputStream = response.body()) {
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void ensureUpdateDirectoryWritable(Path directoryPath) {
        try {
            Files.createDirectories(directoryPath);
        } catch (IOException e) {
            throw new IllegalStateException("Update directory is not writable: " + directoryPath
                    + ". Configure " + UPDATE_PATH_ENV + " to a writable path.", e);
        }
    }

    private String downloadReleaseChecksums(long assetId) throws IOException, InterruptedException {
        URI downloadUri = requireTrustedApiUri(buildReleaseAssetApiUri(assetId));
        HttpRequest.Builder builder = HttpRequest.newBuilder(downloadUri)
                .GET()
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "golemcore-bot-updater");

        HttpResponse<String> response = buildHttpClient().send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        response = followTrustedRedirectForText(downloadUri, response, Duration.ofSeconds(30));
        int statusCode = response.statusCode();
        if (isRedirectStatus(statusCode) || statusCode >= 400) {
            throw new IllegalStateException("Download failed with HTTP " + statusCode);
        }
        return response.body();
    }

    private HttpResponse<InputStream> followTrustedRedirectForBinary(
            URI requestUri,
            HttpResponse<InputStream> response,
            Duration timeout) throws IOException, InterruptedException {
        if (!isRedirectStatus(response.statusCode())) {
            return response;
        }

        URI redirectUri = resolveTrustedRedirectUri(requestUri, response);
        try (InputStream ignored = response.body()) {
            // close the first response body before issuing the next request
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(redirectUri)
                .GET()
                .timeout(timeout)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "golemcore-bot-updater");
        return buildHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private HttpResponse<String> followTrustedRedirectForText(
            URI requestUri,
            HttpResponse<String> response,
            Duration timeout) throws IOException, InterruptedException {
        if (!isRedirectStatus(response.statusCode())) {
            return response;
        }

        URI redirectUri = resolveTrustedRedirectUri(requestUri, response);
        HttpRequest.Builder builder = HttpRequest.newBuilder(redirectUri)
                .GET()
                .timeout(timeout)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "golemcore-bot-updater");
        return buildHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI resolveTrustedRedirectUri(URI requestUri, HttpResponse<?> response) {
        String location = response.headers()
                .firstValue("Location")
                .orElseThrow(() -> new IllegalStateException("Download redirect is missing Location header"));
        URI resolved = requestUri.resolve(location).normalize();
        return requireTrustedDownloadUri(resolved);
    }

    private boolean isRedirectStatus(int statusCode) {
        return statusCode == 301
                || statusCode == 302
                || statusCode == 303
                || statusCode == 307
                || statusCode == 308;
    }

    protected HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    private URI buildReleaseAssetApiUri(long assetId) {
        if (assetId <= 0) {
            throw new IllegalArgumentException("Invalid release asset id: " + assetId);
        }
        return URI.create(GITHUB_API_BASE + RELEASE_ASSET_API_PATH + assetId);
    }

    private URI requireTrustedApiUri(URI uri) {
        return requireTrustedUri(uri, true);
    }

    private URI requireTrustedDownloadUri(URI uri) {
        return requireTrustedUri(uri, false);
    }

    private URI requireTrustedUri(URI uri, boolean requireApiHost) {
        if (uri == null) {
            throw new IllegalStateException("Download URI is missing");
        }
        if (!HTTPS_SCHEME.equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("Only HTTPS download URIs are allowed: " + uri);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalStateException("Download URI userinfo is not allowed: " + uri);
        }
        if (uri.getFragment() != null) {
            throw new IllegalStateException("Download URI fragment is not allowed: " + uri);
        }
        int port = uri.getPort();
        if (port != -1 && port != HTTPS_PORT) {
            throw new IllegalStateException("Download URI port is not allowed: " + uri);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Download URI host is missing: " + uri);
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        boolean trustedHost = requireApiHost
                ? GITHUB_API_HOST.equals(normalizedHost)
                : isTrustedDownloadHost(normalizedHost);
        if (!trustedHost) {
            throw new IllegalStateException("Download URI host is not trusted: " + normalizedHost);
        }
        return uri;
    }

    private boolean isTrustedDownloadHost(String host) {
        return GITHUB_API_HOST.equals(host)
                || GITHUB_HOST.equals(host)
                || host.endsWith(GITHUB_USERCONTENT_SUFFIX);
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
        } catch (IOException e) {
            throw new IllegalStateException("Failed to compute SHA-256", e);
        }
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
        if (assetName == null || assetName.isBlank()) {
            return null;
        }
        int length = assetName.length();
        for (int i = 0; i < length; i++) {
            char current = assetName.charAt(i);
            if (!isAsciiDigit(current)) {
                continue;
            }
            if (i > 0 && isAsciiDigit(assetName.charAt(i - 1))) {
                continue;
            }

            String candidate = readVersionCandidate(assetName, i);
            if (candidate != null) {
                return normalizeVersion(candidate);
            }
        }
        return null;
    }

    private String readVersionCandidate(String value, int start) {
        int length = value.length();
        int majorEnd = consumeDigits(value, start);
        if (majorEnd == start || majorEnd >= length || value.charAt(majorEnd) != '.') {
            return null;
        }

        int minorStart = majorEnd + 1;
        int minorEnd = consumeDigits(value, minorStart);
        if (minorEnd == minorStart || minorEnd >= length || value.charAt(minorEnd) != '.') {
            return null;
        }

        int patchStart = minorEnd + 1;
        int patchEnd = consumeDigits(value, patchStart);
        if (patchEnd == patchStart) {
            return null;
        }

        int versionEnd = patchEnd;
        if (patchEnd < length && value.charAt(patchEnd) == '-') {
            int prereleaseStart = patchEnd + 1;
            if (prereleaseStart < length && isSemverPrereleaseStartChar(value.charAt(prereleaseStart))) {
                int prereleaseEnd = consumePrereleaseChars(value, prereleaseStart);
                if (prereleaseEnd > prereleaseStart) {
                    versionEnd = prereleaseEnd;
                }
            }
        }

        return value.substring(start, versionEnd);
    }

    private int consumeDigits(String value, int start) {
        int cursor = start;
        int length = value.length();
        while (cursor < length && isAsciiDigit(value.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private int consumePrereleaseChars(String value, int start) {
        int cursor = start;
        int length = value.length();
        while (cursor < length && isSemverPrereleaseChar(value.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    private boolean isSemverPrereleaseChar(char value) {
        return isAsciiDigit(value) || isAsciiLetter(value) || value == '.' || value == '-';
    }

    private boolean isSemverPrereleaseStartChar(char value) {
        return isAsciiDigit(value) || isAsciiLetter(value);
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
        if (normalized.endsWith(".jar")) {
            normalized = normalized.substring(0, normalized.length() - ".jar".length());
        }
        if (normalized.endsWith("-exec")) {
            String withoutClassifier = normalized.substring(0, normalized.length() - "-exec".length());
            Matcher matcher = SEMVER_PATTERN.matcher(withoutClassifier);
            if (matcher.matches()) {
                normalized = withoutClassifier;
            }
        }
        return normalized;
    }

    private boolean isRemoteVersionNewer(String remoteVersion, String currentVersion) {
        int comparison = compareVersions(remoteVersion, currentVersion);
        return comparison > 0;
    }

    private boolean isSameMajorUpdate(String currentVersion, String remoteVersion) {
        Semver current = parseSemver(currentVersion);
        Semver remote = parseSemver(remoteVersion);

        if (current == null || remote == null) {
            return false;
        }

        return current.major == remote.major;
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

        String leftPrerelease = leftSemver.preRelease;
        String rightPrerelease = rightSemver.preRelease;
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
        String preRelease = matcher.group(4);

        return new Semver(major, minor, patch, preRelease);
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    protected void requestRestartAsync() {
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
            jvmExitService.exit(exitCode);
        }, "update-restart-thread");

        restartThread.setDaemon(false);
        restartThread.start();
    }

    protected void startUpdateTask(Runnable task) {
        Thread workerThread = new Thread(task, "update-workflow-thread");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private IllegalStateException handleCheckFailure(Throwable throwable) {
        String message = "Failed to check updates: " + safeMessage(throwable);
        synchronized (lock) {
            lastCheckError = message;
            activeTarget = Optional.empty();
            transientState = UpdateState.FAILED;
        }
        return new IllegalStateException(message, throwable);
    }

    private IllegalStateException handlePrepareFailure(Path tempJar, Throwable throwable) {
        cleanupTempJar(tempJar);
        String message = "Failed to prepare update: " + safeMessage(throwable);
        synchronized (lock) {
            transientState = UpdateState.FAILED;
            lastCheckError = message;
        }
        return new IllegalStateException(message, throwable);
    }

    private IllegalStateException handleApplyFailure(Throwable throwable) {
        String message = "Failed to apply update: " + safeMessage(throwable);
        synchronized (lock) {
            transientState = UpdateState.FAILED;
            lastCheckError = message;
        }
        return new IllegalStateException(message, throwable);
    }

    private void cleanupTempJar(Path tempJar) {
        if (tempJar == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempJar);
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "unknown error";
        }
        return throwable.getMessage();
    }

    private void runUpdateNowWorkflow() {
        try {
            UpdateVersionInfo staged;
            AvailableRelease release;
            synchronized (lock) {
                staged = resolveStagedInfo();
                release = availableRelease.orElse(null);
                if (staged != null) {
                    activeTarget = Optional.of(copyVersionInfo(staged));
                }
            }

            if (staged != null) {
                tryApplyStagedUpdate(true);
                return;
            }

            if (release == null) {
                CheckResolution resolution = resolveCheck();
                release = resolution.release();
                if (release == null) {
                    return;
                }
            }

            prepare();
            tryApplyStagedUpdate(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            UpdateState state = transientState;
            if (state == UpdateState.FAILED) {
                return;
            }
            if (state == UpdateState.APPLYING || state == UpdateState.VERIFYING) {
                handleApplyFailure(e);
                return;
            }
            if (state == UpdateState.CHECKING) {
                handleCheckFailure(e);
                return;
            }
            handlePrepareFailure(null, e);
        } catch (IOException | RuntimeException e) {
            UpdateState state = transientState;
            if (state == UpdateState.FAILED) {
                return;
            }
            if (state == UpdateState.CHECKING) {
                handleCheckFailure(e);
                return;
            }
            if (state == UpdateState.APPLYING || state == UpdateState.VERIFYING) {
                handleApplyFailure(e);
                return;
            }
            handlePrepareFailure(null, e);
        }
    }

    void runAutoUpdateCycle() {
        if (!isEnabled() || !runtimeConfigService.isAutoUpdateEnabled()) {
            return;
        }
        synchronized (lock) {
            if (transientState == UpdateState.CHECKING
                    || transientState == UpdateState.PREPARING
                    || transientState == UpdateState.APPLYING
                    || transientState == UpdateState.VERIFYING) {
                return;
            }
        }

        try {
            UpdateVersionInfo staged = resolveStagedInfo();
            if (staged != null) {
                synchronized (lock) {
                    activeTarget = Optional.of(copyVersionInfo(staged));
                }
                tryApplyStagedUpdate(false);
                return;
            }

            AvailableRelease release;
            synchronized (lock) {
                release = availableRelease.orElse(null);
            }
            if (release != null) {
                prepare();
                tryApplyStagedUpdate(false);
                return;
            }

            if (!shouldPerformAutoCheck()) {
                return;
            }

            CheckResolution resolution = resolveCheck();
            if (resolution.release() == null) {
                return;
            }
            prepare();
            tryApplyStagedUpdate(false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleAutoWorkflowFailure(e);
        } catch (IOException | RuntimeException e) {
            handleAutoWorkflowFailure(e);
        }
    }

    private void runAutoUpdateCycleSafely() {
        try {
            runAutoUpdateCycle();
        } catch (RuntimeException e) {
            log.warn("[update] auto update cycle failed: {}", safeMessage(e));
        }
    }

    private boolean shouldPerformAutoCheck() {
        Integer intervalMinutes = runtimeConfigService.getUpdateCheckIntervalMinutes();
        if (intervalMinutes == null || intervalMinutes < 1) {
            intervalMinutes = 60;
        }
        Instant previousCheck = lastCheckAt;
        if (previousCheck == null) {
            return true;
        }
        return !previousCheck.plus(Duration.ofMinutes(intervalMinutes)).isAfter(Instant.now(clock));
    }

    private UpdateMaintenanceWindow.Status evaluateMaintenanceWindow() {
        return updateMaintenanceWindow.evaluate(
                runtimeConfigService.isUpdateMaintenanceWindowEnabled(),
                runtimeConfigService.getUpdateMaintenanceWindowStartUtc(),
                runtimeConfigService.getUpdateMaintenanceWindowEndUtc(),
                Instant.now(clock));
    }

    private boolean tryApplyStagedUpdate(boolean bypassMaintenanceWindow) {
        UpdateVersionInfo staged = resolveStagedInfo();
        if (staged == null) {
            return false;
        }

        synchronized (lock) {
            activeTarget = Optional.of(copyVersionInfo(staged));
        }

        if (!bypassMaintenanceWindow) {
            UpdateMaintenanceWindow.Status windowStatus = evaluateMaintenanceWindow();
            if (!windowStatus.open()) {
                synchronized (lock) {
                    transientState = UpdateState.WAITING_FOR_WINDOW;
                }
                return false;
            }
        }

        UpdateActivityGate.Result activityStatus = updateActivityGate.getStatus();
        if (activityStatus.busy()) {
            synchronized (lock) {
                transientState = UpdateState.WAITING_FOR_IDLE;
            }
            return false;
        }

        applyStagedUpdate();
        return true;
    }

    private void handleAutoWorkflowFailure(Throwable throwable) {
        UpdateState state = transientState;
        if (state == UpdateState.FAILED) {
            return;
        }
        if (state == UpdateState.CHECKING) {
            handleCheckFailure(throwable);
            return;
        }
        if (state == UpdateState.APPLYING || state == UpdateState.VERIFYING) {
            handleApplyFailure(throwable);
            return;
        }
        handlePrepareFailure(null, throwable);
    }

    private UpdateVersionInfo resolveTargetInfo(UpdateState effectiveState, UpdateVersionInfo staged,
            UpdateVersionInfo available) {
        if (effectiveState == UpdateState.APPLYING
                || effectiveState == UpdateState.VERIFYING
                || effectiveState == UpdateState.PREPARING
                || effectiveState == UpdateState.WAITING_FOR_WINDOW
                || effectiveState == UpdateState.WAITING_FOR_IDLE
                || effectiveState == UpdateState.FAILED) {
            if (activeTarget.isPresent()) {
                return copyVersionInfo(activeTarget.orElseThrow());
            }
        }
        if (staged != null) {
            return copyVersionInfo(staged);
        }
        if (available != null) {
            return copyVersionInfo(available);
        }
        if (effectiveState == UpdateState.CHECKING && activeTarget.isPresent()) {
            return copyVersionInfo(activeTarget.orElseThrow());
        }
        return null;
    }

    private StagePresentation buildStagePresentation(
            UpdateState state,
            UpdateVersionInfo current,
            UpdateVersionInfo target) {
        String currentVersion = current != null ? current.getVersion() : null;
        String targetVersion = target != null ? target.getVersion() : null;
        return switch (state) {
        case DISABLED -> new StagePresentation(
                0,
                "Updates disabled",
                "Self-update is disabled in backend configuration.");
        case CHECKING -> new StagePresentation(
                10,
                "Checking latest release",
                "Looking up the newest compatible release before starting the update.");
        case AVAILABLE -> new StagePresentation(
                25,
                buildVersionTitle("Update available", targetVersion),
                "A compatible release is ready. Start update to download it and restart the service.");
        case PREPARING -> new StagePresentation(
                52,
                buildVersionTitle("Downloading and verifying", targetVersion),
                "The release package is being downloaded and validated before restart.");
        case STAGED -> new StagePresentation(
                72,
                buildVersionTitle("Update staged", targetVersion),
                "The release is ready locally. Restart the service to switch to the new version.");
        case WAITING_FOR_WINDOW -> new StagePresentation(
                76,
                buildVersionTitle("Waiting for maintenance window", targetVersion),
                "The release is staged and will be applied when the configured UTC maintenance window opens.");
        case WAITING_FOR_IDLE -> new StagePresentation(
                80,
                buildVersionTitle("Waiting for running work", targetVersion),
                "The release is staged and will be applied once active session or auto-mode work finishes.");
        case APPLYING -> new StagePresentation(
                88,
                buildVersionTitle("Scheduling restart", targetVersion),
                "The new package is selected. The service is now restarting into the updated runtime.");
        case VERIFYING -> new StagePresentation(
                96,
                buildVersionTitle("Waiting for service", targetVersion),
                "The backend is restarting. This page should reconnect and reload automatically.");
        case FAILED -> new StagePresentation(
                targetVersion != null ? 52 : 12,
                "Update failed",
                lastCheckError == null || lastCheckError.isBlank()
                        ? "The last update attempt failed."
                        : lastCheckError);
        case IDLE -> new StagePresentation(
                0,
                currentVersion == null ? "System ready" : "Running " + currentVersion,
                "No update workflow is running.");
        };
    }

    private String buildVersionTitle(String prefix, String version) {
        if (version == null || version.isBlank()) {
            return prefix;
        }
        return prefix + " " + version;
    }

    private RuntimeConfig copyRuntimeConfig(RuntimeConfig source) {
        try {
            String json = objectMapper.writeValueAsString(source);
            return objectMapper.readValue(json, RuntimeConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to copy runtime config", e);
        }
    }

    private UpdateVersionInfo toVersionInfo(AvailableRelease release) {
        return UpdateVersionInfo.builder()
                .version(release.version())
                .tag(release.tagName())
                .assetName(release.assetName())
                .publishedAt(release.publishedAt())
                .build();
    }

    private UpdateVersionInfo copyVersionInfo(UpdateVersionInfo value) {
        if (value == null) {
            return null;
        }
        return UpdateVersionInfo.builder()
                .version(value.getVersion())
                .source(value.getSource())
                .tag(value.getTag())
                .assetName(value.getAssetName())
                .preparedAt(value.getPreparedAt())
                .publishedAt(value.getPublishedAt())
                .build();
    }

    private record Semver(int major, int minor, int patch, String preRelease) {
    }

    private record CheckResolution(AvailableRelease release, String message, String version) {
    }

    private record StagePresentation(int progressPercent, String title, String description) {
    }

    private record AvailableRelease(
            String version,
            String tagName,
            String assetName,
            long assetId,
            long checksumAssetId,
            Instant publishedAt) {
    }
}
