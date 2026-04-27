package me.golemcore.bot.adapter.outbound.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AvailableRelease;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.ReleaseSourcePort;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Release source that discovers and downloads releases from Maven Central.
 *
 * <p>
 * Uses the Maven Central Search API to find the latest version and constructs
 * download URLs using the standard Maven repository layout.
 */
@Component
@Order(1)
@Slf4j
public class MavenCentralReleaseSourceAdapter implements ReleaseSourcePort {

    private static final String SOURCE_NAME = "maven-central";
    private static final String SEARCH_API_BASE = "https://search.maven.org/solrsearch/select";
    private static final String REPO_BASE = "https://repo1.maven.org/maven2";
    private static final String GROUP_ID = "me.golemcore";
    private static final String ARTIFACT_ID = "bot";
    private static final String GROUP_PATH = GROUP_ID.replace('.', '/');
    private static final String EXEC_JAR_SUFFIX = "-exec.jar";
    private static final String EXEC_JAR_SHA1_SUFFIX = EXEC_JAR_SUFFIX + ".sha1";
    private static final String USER_AGENT = "golemcore-bot-updater";
    private static final Duration API_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

    private final ObjectMapper objectMapper;
    private final BotProperties botProperties;

    public MavenCentralReleaseSourceAdapter(
            ObjectMapper objectMapper,
            BotProperties botProperties) {
        this.objectMapper = objectMapper;
        this.botProperties = botProperties;
    }

    @Override
    public String name() {
        return SOURCE_NAME;
    }

    @Override
    public Optional<AvailableRelease> fetchLatestRelease() throws IOException, InterruptedException {
        String searchUrl = SEARCH_API_BASE
                + "?q=g:" + GROUP_ID + "+AND+a:" + ARTIFACT_ID
                + "&rows=1&wt=json";

        HttpRequest request = HttpRequest.newBuilder(URI.create(searchUrl))
                .GET()
                .timeout(API_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build();

        HttpResponse<String> response = buildHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();

        if (statusCode >= 400) {
            log.warn("[update] Maven Central search API returned {}", statusCode);
            return Optional.empty();
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode docs = root.path("response").path("docs");
        if (!docs.isArray() || docs.isEmpty()) {
            log.debug("[update] No artifact found on Maven Central for {}:{}", GROUP_ID, ARTIFACT_ID);
            return Optional.empty();
        }

        JsonNode doc = docs.get(0);
        String latestVersion = doc.path("latestVersion").asText(null);
        if (latestVersion == null || latestVersion.isBlank()) {
            log.warn("[update] Maven Central response missing latestVersion field");
            return Optional.empty();
        }

        long timestamp = doc.path("timestamp").asLong(0);
        Instant publishedAt = timestamp > 0 ? Instant.ofEpochMilli(timestamp) : null;
        String assetName = ARTIFACT_ID + "-" + latestVersion + EXEC_JAR_SUFFIX;

        String downloadUrl = buildArtifactUrl(latestVersion, EXEC_JAR_SUFFIX);
        String checksumUrl = buildArtifactUrl(latestVersion, EXEC_JAR_SHA1_SUFFIX);

        return Optional.of(AvailableRelease.builder()
                .version(latestVersion)
                .tagName("v" + latestVersion)
                .assetName(assetName)
                .downloadUrl(downloadUrl)
                .checksumUrl(checksumUrl)
                .publishedAt(publishedAt)
                .source(SOURCE_NAME)
                .build());
    }

    @Override
    public InputStream downloadAsset(AvailableRelease release) throws IOException, InterruptedException {
        String url = release.getDownloadUrl();
        if (url == null || url.isBlank()) {
            url = buildArtifactUrl(release.getVersion(), EXEC_JAR_SUFFIX);
        }

        requireTrustedUri(URI.create(url));

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(DOWNLOAD_TIMEOUT)
                .header("Accept", "application/java-archive")
                .header("User-Agent", USER_AGENT)
                .build();

        HttpResponse<InputStream> response = buildHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            throw new IOException("Maven Central download failed with HTTP " + response.statusCode()
                    + " for " + url);
        }
        return response.body();
    }

    @Override
    public ChecksumInfo downloadChecksum(AvailableRelease release) throws IOException, InterruptedException {
        String url = release.getChecksumUrl();
        if (url == null || url.isBlank()) {
            url = buildArtifactUrl(release.getVersion(), EXEC_JAR_SHA1_SUFFIX);
        }

        requireTrustedUri(URI.create(url));

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(API_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .build();

        HttpResponse<String> response = buildHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("Maven Central checksum download failed with HTTP " + response.statusCode());
        }

        String hexDigest = response.body().trim().toLowerCase(Locale.ROOT);
        return new ChecksumInfo(hexDigest, "SHA-1", release.getAssetName());
    }

    @Override
    public boolean isEnabled() {
        return botProperties.getUpdate().isEnabled();
    }

    protected HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private String buildArtifactUrl(String version, String extension) {
        return REPO_BASE + "/" + GROUP_PATH + "/" + ARTIFACT_ID + "/" + version
                + "/" + ARTIFACT_ID + "-" + version + extension;
    }

    private void requireTrustedUri(URI uri) {
        if (uri == null) {
            throw new IllegalStateException("Download URI is missing");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("Only HTTPS download URIs are allowed: " + uri);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Download URI host is missing: " + uri);
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!"repo1.maven.org".equals(normalizedHost)
                && !"repo.maven.apache.org".equals(normalizedHost)
                && !"search.maven.org".equals(normalizedHost)) {
            throw new IllegalStateException("Download URI host is not trusted: " + normalizedHost);
        }
    }
}
