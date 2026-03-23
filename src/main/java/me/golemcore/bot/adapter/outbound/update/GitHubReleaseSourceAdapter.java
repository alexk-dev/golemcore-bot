package me.golemcore.bot.adapter.outbound.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Release source that discovers and downloads releases from GitHub Releases
 * API.
 *
 * <p>
 * Acts as a fallback when Maven Central is unavailable.
 */
@Component
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class GitHubReleaseSourceAdapter implements ReleaseSourcePort {

    private static final String SOURCE_NAME = "github";
    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String GITHUB_API_HOST = "api.github.com";
    private static final String GITHUB_HOST = "github.com";
    private static final String GITHUB_USERCONTENT_SUFFIX = ".githubusercontent.com";
    private static final String RELEASE_REPOSITORY = "alexk-dev/golemcore-bot";
    private static final String RELEASE_ASSET_API_PATH = "/repos/" + RELEASE_REPOSITORY + "/releases/assets/";
    private static final String RELEASE_ASSET_PATTERN = "bot-*.jar";
    private static final String SHA256_FILE_NAME = "sha256sums.txt";
    private static final String USER_AGENT = "golemcore-bot-updater";
    private static final Duration API_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final Pattern SAFE_RELEASE_SEGMENT_PATTERN = Pattern.compile("^[0-9A-Za-z._-]+$");

    private final ObjectMapper objectMapper;
    private final BotProperties botProperties;

    @Override
    public String name() {
        return SOURCE_NAME;
    }

    @Override
    public Optional<AvailableRelease> fetchLatestRelease() throws IOException, InterruptedException {
        String apiUrl = GITHUB_API_BASE + "/repos/" + RELEASE_REPOSITORY + "/releases/latest";

        URI apiUri = requireTrustedApiUri(URI.create(apiUrl));
        HttpRequest request = HttpRequest.newBuilder(apiUri)
                .GET()
                .timeout(API_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build();

        HttpResponse<String> response = buildHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();

        if (statusCode == 404) {
            return Optional.empty();
        }
        if (statusCode >= 400) {
            throw new IOException("GitHub API returned " + statusCode);
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode assets = root.path("assets");
        if (!assets.isArray()) {
            throw new IOException("Release assets are missing");
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
            return Optional.empty();
        }
        if (jarAssetId == null) {
            throw new IOException("Release asset id is missing");
        }
        if (checksumAssetId == null) {
            throw new IOException("sha256sums.txt not found in release assets");
        }

        String tagName = root.path("tag_name").asText("");
        String assetName = jarAssetNode.path("name").asText("");
        Instant publishedAt = parseInstant(root.path("published_at").asText(null));

        validateReleaseTag(tagName);
        validateAssetName(assetName);

        String downloadUrl = GITHUB_API_BASE + RELEASE_ASSET_API_PATH + jarAssetId;
        String checksumUrl = GITHUB_API_BASE + RELEASE_ASSET_API_PATH + checksumAssetId;

        return Optional.of(AvailableRelease.builder()
                .version(extractVersion(tagName))
                .tagName(tagName)
                .assetName(assetName)
                .downloadUrl(downloadUrl)
                .checksumUrl(checksumUrl)
                .publishedAt(publishedAt)
                .source(SOURCE_NAME)
                .build());
    }

    @Override
    public InputStream downloadAsset(AvailableRelease release) throws IOException, InterruptedException {
        URI downloadUri = requireTrustedApiUri(URI.create(release.getDownloadUrl()));
        HttpRequest request = HttpRequest.newBuilder(downloadUri)
                .GET()
                .timeout(DOWNLOAD_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", USER_AGENT)
                .build();

        HttpResponse<InputStream> response = buildHttpClient().send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        response = followTrustedRedirectForBinary(downloadUri, response);
        int statusCode = response.statusCode();
        if (isRedirectStatus(statusCode) || statusCode >= 400) {
            throw new IOException("GitHub download failed with HTTP " + statusCode);
        }
        return response.body();
    }

    @Override
    public ChecksumInfo downloadChecksum(AvailableRelease release) throws IOException, InterruptedException {
        URI downloadUri = requireTrustedApiUri(URI.create(release.getChecksumUrl()));
        HttpRequest request = HttpRequest.newBuilder(downloadUri)
                .GET()
                .timeout(API_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", USER_AGENT)
                .build();

        HttpResponse<String> response = buildHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        response = followTrustedRedirectForText(downloadUri, response);
        int statusCode = response.statusCode();
        if (isRedirectStatus(statusCode) || statusCode >= 400) {
            throw new IOException("GitHub checksum download failed with HTTP " + statusCode);
        }

        String checksumText = response.body();
        String hexDigest = extractExpectedSha256(checksumText, release.getAssetName());
        return new ChecksumInfo(hexDigest, "SHA-256", release.getAssetName());
    }

    @Override
    public boolean isEnabled() {
        return botProperties.getUpdate().isEnabled();
    }

    protected HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
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

    private void validateReleaseTag(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            throw new IllegalStateException("Release tag is missing");
        }
        String normalizedTag = tagName.trim();
        if (!SAFE_RELEASE_SEGMENT_PATTERN.matcher(normalizedTag).matches()) {
            throw new IllegalStateException("Release tag contains prohibited characters: " + normalizedTag);
        }
    }

    private void validateAssetName(String assetName) {
        if (assetName == null || assetName.isBlank()) {
            throw new IllegalArgumentException("Invalid asset name");
        }
        if (assetName.contains("/") || assetName.contains("\\") || assetName.contains("..")) {
            throw new IllegalArgumentException("Asset name contains prohibited path characters");
        }
    }

    private String extractVersion(String tagName) {
        String normalized = tagName.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized;
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

    private HttpResponse<InputStream> followTrustedRedirectForBinary(
            URI requestUri,
            HttpResponse<InputStream> response) throws IOException, InterruptedException {
        if (!isRedirectStatus(response.statusCode())) {
            return response;
        }

        URI redirectUri = resolveTrustedRedirectUri(requestUri, response);
        try (InputStream ignored = response.body()) {
            // close the first response body
        }

        HttpRequest request = HttpRequest.newBuilder(redirectUri)
                .GET()
                .timeout(DOWNLOAD_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", USER_AGENT)
                .build();
        return buildHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private HttpResponse<String> followTrustedRedirectForText(
            URI requestUri,
            HttpResponse<String> response) throws IOException, InterruptedException {
        if (!isRedirectStatus(response.statusCode())) {
            return response;
        }

        URI redirectUri = resolveTrustedRedirectUri(requestUri, response);
        HttpRequest request = HttpRequest.newBuilder(redirectUri)
                .GET()
                .timeout(API_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", USER_AGENT)
                .build();
        return buildHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("Only HTTPS download URIs are allowed: " + uri);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalStateException("Download URI userinfo is not allowed: " + uri);
        }
        if (uri.getFragment() != null) {
            throw new IllegalStateException("Download URI fragment is not allowed: " + uri);
        }
        int port = uri.getPort();
        if (port != -1 && port != 443) {
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
}
