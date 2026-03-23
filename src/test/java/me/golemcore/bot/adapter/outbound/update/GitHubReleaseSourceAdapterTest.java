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
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            exchanges.addLast(new StubExchange(statusCode, body.getBytes(StandardCharsets.UTF_8)));
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
        public <T> java.net.http.HttpResponse<T> send(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            StubExchange exchange = exchanges.pollFirst();
            if (exchange == null) {
                throw new IOException("No stub exchange configured for " + request.uri());
            }
            byte[] payload = exchange.responseBody != null ? exchange.responseBody : new byte[0];
            java.net.http.HttpHeaders headers = java.net.http.HttpHeaders.of(
                    java.util.Map.of(), (name, value) -> true);
            java.net.http.HttpResponse.ResponseInfo info = new java.net.http.HttpResponse.ResponseInfo() {
                @Override
                public int statusCode() {
                    return exchange.statusCode;
                }

                @Override
                public java.net.http.HttpHeaders headers() {
                    return headers;
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
            java.net.http.HttpResponse.BodySubscriber<T> subscriber = responseBodyHandler.apply(info);
            subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                @Override
                public void request(long n) {
                }

                @Override
                public void cancel() {
                }
            });
            subscriber.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(payload)));
            subscriber.onComplete();
            T decodedBody = subscriber.getBody().toCompletableFuture().join();

            return new java.net.http.HttpResponse<>() {
                @Override
                public int statusCode() {
                    return exchange.statusCode;
                }

                @Override
                public java.net.http.HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<java.net.http.HttpResponse<T>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public java.net.http.HttpHeaders headers() {
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
        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) {
            try {
                return java.util.concurrent.CompletableFuture.completedFuture(send(request, responseBodyHandler));
            } catch (IOException | InterruptedException e) {
                java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> failed = new java.util.concurrent.CompletableFuture<>();
                failed.completeExceptionally(e);
                return failed;
            }
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler,
                java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }

        private record StubExchange(int statusCode, byte[] responseBody) {
        }
    }
}
