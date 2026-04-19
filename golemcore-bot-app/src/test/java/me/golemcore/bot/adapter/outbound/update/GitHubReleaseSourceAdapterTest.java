package me.golemcore.bot.adapter.outbound.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.AvailableRelease;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.ReleaseSourcePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("PMD.CloseResource")
class GitHubReleaseSourceAdapterTest {

    private BotProperties botProperties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        botProperties = new BotProperties();
        botProperties.getUpdate().setEnabled(true);
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldReturnGitHubAsName() {
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, null);
        assertEquals("github", adapter.name());
    }

    @Test
    void shouldBeEnabledWhenUpdateIsEnabled() {
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, null);
        assertTrue(adapter.isEnabled());
    }

    @Test
    void shouldBeDisabledWhenUpdateIsDisabled() {
        botProperties.getUpdate().setEnabled(false);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, null);
        assertFalse(adapter.isEnabled());
    }

    @Test
    void shouldDiscoverLatestReleaseFromGitHubApi() throws Exception {
        String releaseJson = """
                {
                  "tag_name": "v0.5.0",
                  "published_at": "2026-02-22T10:00:00Z",
                  "assets": [
                    {
                      "id": 101,
                      "name": "bot-0.5.0.jar",
                      "browser_download_url": "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.5.0/bot-0.5.0.jar"
                    },
                    {
                      "id": 102,
                      "name": "sha256sums.txt",
                      "browser_download_url": "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.5.0/sha256sums.txt"
                    }
                  ]
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        Optional<AvailableRelease> result = adapter.fetchLatestRelease();

        assertTrue(result.isPresent());
        AvailableRelease release = result.get();
        assertEquals("0.5.0", release.getVersion());
        assertEquals("v0.5.0", release.getTagName());
        assertEquals("bot-0.5.0.jar", release.getAssetName());
        assertEquals("github", release.getSource());
        assertNotNull(release.getDownloadUrl());
        assertTrue(release.getDownloadUrl().contains("api.github.com"));
    }

    @Test
    void shouldReturnEmptyWhenGitHubReturns404() throws Exception {
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(404, "{}");
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        Optional<AvailableRelease> result = adapter.fetchLatestRelease();

        assertFalse(result.isPresent());
    }

    @Test
    void shouldThrowWhenGitHubReturns403() throws Exception {
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(403, "rate limited");
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        assertThrows(IOException.class, adapter::fetchLatestRelease);
    }

    @Test
    void shouldReturnEmptyWhenNoJarAssetFound() throws Exception {
        String releaseJson = """
                {
                  "tag_name": "v0.5.0",
                  "published_at": "2026-02-22T10:00:00Z",
                  "assets": [
                    {
                      "id": 102,
                      "name": "sha256sums.txt"
                    }
                  ]
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        Optional<AvailableRelease> result = adapter.fetchLatestRelease();

        assertFalse(result.isPresent());
    }

    @Test
    void shouldDownloadChecksumAndReturnSha256() throws Exception {
        String checksumContent = "abcdef1234567890 bot-0.5.0.jar\nfedcba0987654321 other-file.txt\n";

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, checksumContent);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .checksumUrl("https://api.github.com/repos/alexk-dev/golemcore-bot/releases/assets/102")
                .build();

        ReleaseSourcePort.ChecksumInfo checksumInfo = adapter.downloadChecksum(release);

        assertEquals("abcdef1234567890", checksumInfo.hexDigest());
        assertEquals("SHA-256", checksumInfo.algorithm());
        assertEquals("bot-0.5.0.jar", checksumInfo.assetName());
    }

    @Test
    void shouldRejectReleaseWithMissingTag() throws Exception {
        String releaseJson = """
                {
                  "tag_name": " ",
                  "published_at": "2026-02-22T10:00:00Z",
                  "assets": [
                    { "id": 101, "name": "bot-0.5.0.jar" },
                    { "id": 102, "name": "sha256sums.txt" }
                  ]
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        assertThrows(IllegalStateException.class, adapter::fetchLatestRelease);
    }

    @Test
    void shouldDownloadAssetFromGitHub() throws Exception {
        byte[] jarBytes = "fake-jar-content".getBytes(StandardCharsets.UTF_8);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueBinaryResponse(200, jarBytes);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("https://api.github.com/repos/alexk-dev/golemcore-bot/releases/assets/101")
                .build();

        try (InputStream stream = adapter.downloadAsset(release)) {
            byte[] downloaded = stream.readAllBytes();
            assertEquals("fake-jar-content", new String(downloaded, StandardCharsets.UTF_8));
        }
    }

    @Test
    void shouldFollowRedirectOnAssetDownload() throws Exception {
        byte[] jarBytes = "redirected-jar".getBytes(StandardCharsets.UTF_8);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueResponseWithHeaders(302, new byte[0],
                Map.of("Location", List.of("https://objects.githubusercontent.com/release-asset/bot-0.5.0.jar")));
        httpClient.enqueueBinaryResponse(200, jarBytes);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("https://api.github.com/repos/alexk-dev/golemcore-bot/releases/assets/101")
                .build();

        try (InputStream stream = adapter.downloadAsset(release)) {
            byte[] downloaded = stream.readAllBytes();
            assertEquals("redirected-jar", new String(downloaded, StandardCharsets.UTF_8));
        }
    }

    @Test
    void shouldThrowWhenDownloadFails() {
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(500, "Internal Server Error");
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("https://api.github.com/repos/alexk-dev/golemcore-bot/releases/assets/101")
                .build();

        assertThrows(IOException.class, () -> adapter.downloadAsset(release));
    }

    @Test
    void shouldThrowWhenChecksumDownloadFails() {
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(500, "error");
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .checksumUrl("https://api.github.com/repos/alexk-dev/golemcore-bot/releases/assets/102")
                .build();

        assertThrows(IOException.class, () -> adapter.downloadChecksum(release));
    }

    @Test
    void shouldFollowRedirectOnChecksumDownload() throws Exception {
        String checksumContent = "abcdef1234567890 bot-0.5.0.jar\n";
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueResponseWithHeaders(302, new byte[0],
                Map.of("Location", List.of("https://objects.githubusercontent.com/sha256sums.txt")));
        httpClient.enqueueStringResponse(200, checksumContent);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .checksumUrl("https://api.github.com/repos/alexk-dev/golemcore-bot/releases/assets/102")
                .build();

        ReleaseSourcePort.ChecksumInfo info = adapter.downloadChecksum(release);
        assertEquals("abcdef1234567890", info.hexDigest());
    }

    @Test
    void shouldRejectUntrustedDownloadUri() {
        StubHttpClient httpClient = new StubHttpClient();
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("https://evil.com/bot-0.5.0.jar")
                .build();

        assertThrows(IllegalStateException.class, () -> adapter.downloadAsset(release));
    }

    @Test
    void shouldRejectHttpDownloadUri() {
        StubHttpClient httpClient = new StubHttpClient();
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("http://api.github.com/repos/alexk-dev/golemcore-bot/releases/assets/101")
                .build();

        assertThrows(IllegalStateException.class, () -> adapter.downloadAsset(release));
    }

    @Test
    void shouldRejectUriWithUserInfo() {
        StubHttpClient httpClient = new StubHttpClient();
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("https://user:pass@api.github.com/repos/alexk-dev/golemcore-bot/releases/assets/101")
                .build();

        assertThrows(IllegalStateException.class, () -> adapter.downloadAsset(release));
    }

    @Test
    void shouldRejectUriWithNonStandardPort() {
        StubHttpClient httpClient = new StubHttpClient();
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("https://api.github.com:8080/repos/alexk-dev/golemcore-bot/releases/assets/101")
                .build();

        assertThrows(IllegalStateException.class, () -> adapter.downloadAsset(release));
    }

    @Test
    void shouldRejectRedirectToUntrustedHost() {
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueResponseWithHeaders(302, new byte[0],
                Map.of("Location", List.of("https://evil.com/malicious.jar")));
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("https://api.github.com/repos/alexk-dev/golemcore-bot/releases/assets/101")
                .build();

        assertThrows(IllegalStateException.class, () -> adapter.downloadAsset(release));
    }

    @Test
    void shouldRejectReleaseTagWithProhibitedCharacters() throws Exception {
        String releaseJson = """
                {
                  "tag_name": "v0.5.0; rm -rf /",
                  "assets": [
                    { "id": 101, "name": "bot-0.5.0.jar" },
                    { "id": 102, "name": "sha256sums.txt" }
                  ]
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        assertThrows(IllegalStateException.class, adapter::fetchLatestRelease);
    }

    @Test
    void shouldReturnEmptyWhenAssetNameDoesNotMatchGlob() throws Exception {
        String releaseJson = """
                {
                  "tag_name": "v0.5.0",
                  "assets": [
                    { "id": 101, "name": "bot-../../../etc/passwd.jar" },
                    { "id": 102, "name": "sha256sums.txt" }
                  ]
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        Optional<AvailableRelease> result = adapter.fetchLatestRelease();
        assertFalse(result.isPresent());
    }

    @Test
    void shouldThrowWhenMissingAssetsArray() throws Exception {
        String releaseJson = """
                {
                  "tag_name": "v0.5.0",
                  "published_at": "2026-02-22T10:00:00Z"
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        assertThrows(IOException.class, adapter::fetchLatestRelease);
    }

    @Test
    void shouldThrowWhenChecksumFileNotFoundInRelease() throws Exception {
        String releaseJson = """
                {
                  "tag_name": "v0.5.0",
                  "assets": [
                    { "id": 101, "name": "bot-0.5.0.jar" }
                  ]
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        assertThrows(IOException.class, adapter::fetchLatestRelease);
    }

    @Test
    void shouldExtractVersionFromTagWithoutPrefix() throws Exception {
        String releaseJson = """
                {
                  "tag_name": "1.2.3",
                  "assets": [
                    { "id": 101, "name": "bot-1.2.3.jar" },
                    { "id": 102, "name": "sha256sums.txt" }
                  ]
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        Optional<AvailableRelease> result = adapter.fetchLatestRelease();
        assertTrue(result.isPresent());
        assertEquals("1.2.3", result.get().getVersion());
    }

    @Test
    void shouldUseTagAsVersionWhenTagIsNonSemverAndNoVersionInAsset() throws Exception {
        String releaseJson = """
                {
                  "tag_name": "release-build",
                  "assets": [
                    { "id": 101, "name": "bot-release.jar" },
                    { "id": 102, "name": "sha256sums.txt" }
                  ]
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        Optional<AvailableRelease> result = adapter.fetchLatestRelease();
        assertTrue(result.isPresent());
        assertEquals("release-build", result.get().getVersion());
    }

    @Test
    void shouldExtractVersionFromTagWithUppercaseV() throws Exception {
        String releaseJson = """
                {
                  "tag_name": "V3.0.1",
                  "assets": [
                    { "id": 101, "name": "bot-3.0.1.jar" },
                    { "id": 102, "name": "sha256sums.txt" }
                  ]
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        Optional<AvailableRelease> result = adapter.fetchLatestRelease();
        assertTrue(result.isPresent());
        assertEquals("3.0.1", result.get().getVersion());
    }

    @Test
    void shouldHandleMissingPublishedAt() throws Exception {
        String releaseJson = """
                {
                  "tag_name": "v0.5.0",
                  "assets": [
                    { "id": 101, "name": "bot-0.5.0.jar" },
                    { "id": 102, "name": "sha256sums.txt" }
                  ]
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        Optional<AvailableRelease> result = adapter.fetchLatestRelease();
        assertTrue(result.isPresent());
        assertNotNull(result.get().getVersion());
    }

    @Test
    void shouldHandleInvalidPublishedAtGracefully() throws Exception {
        String releaseJson = """
                {
                  "tag_name": "v0.5.0",
                  "published_at": "not-a-date",
                  "assets": [
                    { "id": 101, "name": "bot-0.5.0.jar" },
                    { "id": 102, "name": "sha256sums.txt" }
                  ]
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        Optional<AvailableRelease> result = adapter.fetchLatestRelease();
        assertTrue(result.isPresent());
    }

    @Test
    void shouldHandleChecksumWithStarPrefix() throws Exception {
        String checksumContent = "abcdef1234567890 *bot-0.5.0.jar\n";

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, checksumContent);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .checksumUrl("https://api.github.com/repos/alexk-dev/golemcore-bot/releases/assets/102")
                .build();

        ReleaseSourcePort.ChecksumInfo info = adapter.downloadChecksum(release);
        assertEquals("abcdef1234567890", info.hexDigest());
    }

    @Test
    void shouldThrowWhenChecksumNotFoundForAsset() throws Exception {
        String checksumContent = "abcdef1234567890 other-file.jar\n";

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, checksumContent);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .checksumUrl("https://api.github.com/repos/alexk-dev/golemcore-bot/releases/assets/102")
                .build();

        assertThrows(IllegalStateException.class, () -> adapter.downloadChecksum(release));
    }

    @Test
    void shouldThrowWhenAssetIdIsMissing() throws Exception {
        String releaseJson = """
                {
                  "tag_name": "v0.5.0",
                  "assets": [
                    { "name": "bot-0.5.0.jar" },
                    { "id": 102, "name": "sha256sums.txt" }
                  ]
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        assertThrows(IllegalStateException.class, adapter::fetchLatestRelease);
    }

    @Test
    void shouldThrowWhenAssetIdIsNegative() throws Exception {
        String releaseJson = """
                {
                  "tag_name": "v0.5.0",
                  "assets": [
                    { "id": -1, "name": "bot-0.5.0.jar" },
                    { "id": 102, "name": "sha256sums.txt" }
                  ]
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        assertThrows(IllegalStateException.class, adapter::fetchLatestRelease);
    }

    @Test
    void shouldRejectUriWithFragment() {
        StubHttpClient httpClient = new StubHttpClient();
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("https://api.github.com/repos/alexk-dev/golemcore-bot/releases/assets/101#frag")
                .build();

        assertThrows(IllegalStateException.class, () -> adapter.downloadAsset(release));
    }

    @Test
    void shouldAcceptPort443() throws Exception {
        byte[] jarBytes = "jar-443".getBytes(StandardCharsets.UTF_8);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueBinaryResponse(200, jarBytes);
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("https://api.github.com:443/repos/alexk-dev/golemcore-bot/releases/assets/101")
                .build();

        try (InputStream stream = adapter.downloadAsset(release)) {
            assertNotNull(stream);
        }
    }

    @Test
    void shouldThrowWhenRedirectIsMissingLocationHeader() {
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueResponseWithHeaders(302, new byte[0], Map.of());
        StubGitHubAdapter adapter = new StubGitHubAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("https://api.github.com/repos/alexk-dev/golemcore-bot/releases/assets/101")
                .build();

        assertThrows(IllegalStateException.class, () -> adapter.downloadAsset(release));
    }

    @SuppressWarnings("PMD.TestClassWithoutTestCases")
    private static class StubGitHubAdapter extends GitHubReleaseSourceAdapter {

        private final StubHttpClient stubClient;

        StubGitHubAdapter(ObjectMapper objectMapper, BotProperties botProperties, StubHttpClient stubClient) {
            super(objectMapper, botProperties);
            this.stubClient = stubClient;
        }

        @Override
        protected HttpClient buildHttpClient() {
            return stubClient;
        }
    }

    private static class StubHttpClient extends HttpClient {

        private final java.util.Deque<StubExchange> exchanges = new java.util.ArrayDeque<>();

        void enqueueStringResponse(int statusCode, String body) {
            exchanges.addLast(new StubExchange(statusCode, body.getBytes(StandardCharsets.UTF_8), Map.of()));
        }

        void enqueueBinaryResponse(int statusCode, byte[] body) {
            exchanges.addLast(new StubExchange(statusCode, body, Map.of()));
        }

        void enqueueResponseWithHeaders(int statusCode, byte[] body, Map<String, List<String>> headers) {
            exchanges.addLast(new StubExchange(statusCode, body, headers));
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<java.time.Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<java.net.ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            return null;
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return new javax.net.ssl.SSLParameters();
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            StubExchange exchange = exchanges.pollFirst();
            if (exchange == null) {
                throw new IOException("No stub exchange configured for " + request.uri());
            }
            byte[] payload = exchange.responseBody != null ? exchange.responseBody : new byte[0];
            Map<String, List<String>> headerMap = new HashMap<>(exchange.headers);
            HttpHeaders headers = HttpHeaders.of(headerMap, (name, value) -> true);
            HttpResponse.ResponseInfo info = new HttpResponse.ResponseInfo() {
                @Override
                public int statusCode() {
                    return exchange.statusCode;
                }

                @Override
                public HttpHeaders headers() {
                    return headers;
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
            HttpResponse.BodySubscriber<T> subscriber = responseBodyHandler.apply(info);
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                }

                @Override
                public void cancel() {
                }
            });
            if (payload.length > 0) {
                subscriber.onNext(List.of(ByteBuffer.wrap(payload)));
            }
            subscriber.onComplete();
            T decodedBody = subscriber.getBody().toCompletableFuture().join();

            return new HttpResponse<>() {
                @Override
                public int statusCode() {
                    return exchange.statusCode;
                }

                @Override
                public HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<HttpResponse<T>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return headers;
                }

                @Override
                public T body() {
                    return decodedBody;
                }

                @Override
                public Optional<javax.net.ssl.SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public java.net.URI uri() {
                    return request.uri();
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            try {
                return CompletableFuture.completedFuture(send(request, responseBodyHandler));
            } catch (IOException | InterruptedException e) {
                CompletableFuture<HttpResponse<T>> failed = new CompletableFuture<>();
                failed.completeExceptionally(e);
                return failed;
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }

        private record StubExchange(int statusCode, byte[] responseBody, Map<String, List<String>> headers) {
        }
    }
}
