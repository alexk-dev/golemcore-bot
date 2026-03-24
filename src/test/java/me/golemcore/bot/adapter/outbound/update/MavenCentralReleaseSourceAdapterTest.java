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

@SuppressWarnings("PMD.CloseResource")
class MavenCentralReleaseSourceAdapterTest {

    private BotProperties botProperties;
    private ObjectMapper objectMapper;
    private StubMavenCentralAdapter adapter;

    @BeforeEach
    void setUp() {
        botProperties = new BotProperties();
        botProperties.getUpdate().setEnabled(true);
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldReturnMavenCentralAsName() {
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, null);
        assertEquals("maven-central", adapter.name());
    }

    @Test
    void shouldBeEnabledWhenUpdateIsEnabled() {
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, null);
        assertTrue(adapter.isEnabled());
    }

    @Test
    void shouldBeDisabledWhenUpdateIsDisabled() {
        botProperties.getUpdate().setEnabled(false);
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, null);
        assertFalse(adapter.isEnabled());
    }

    @Test
    void shouldDiscoverLatestReleaseFromSearchApi() throws Exception {
        String searchResponse = """
                {
                  "response": {
                    "numFound": 1,
                    "docs": [
                      {
                        "g": "me.golemcore",
                        "a": "bot",
                        "latestVersion": "0.5.0",
                        "timestamp": 1740000000000
                      }
                    ]
                  }
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, searchResponse);
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, httpClient);

        Optional<AvailableRelease> result = adapter.fetchLatestRelease();

        assertTrue(result.isPresent());
        AvailableRelease release = result.get();
        assertEquals("0.5.0", release.getVersion());
        assertEquals("v0.5.0", release.getTagName());
        assertEquals("bot-0.5.0.jar", release.getAssetName());
        assertEquals("maven-central", release.getSource());
        assertNotNull(release.getDownloadUrl());
        assertTrue(release.getDownloadUrl().contains("repo1.maven.org"));
        assertTrue(release.getDownloadUrl().contains("me/golemcore/bot/0.5.0/bot-0.5.0.jar"));
    }

    @Test
    void shouldReturnEmptyWhenNoArtifactFound() throws Exception {
        String searchResponse = """
                {
                  "response": {
                    "numFound": 0,
                    "docs": []
                  }
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, searchResponse);
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, httpClient);

        Optional<AvailableRelease> result = adapter.fetchLatestRelease();

        assertFalse(result.isPresent());
    }

    @Test
    void shouldReturnEmptyWhenApiReturnsError() throws Exception {
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(503, "Service Unavailable");
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, httpClient);

        Optional<AvailableRelease> result = adapter.fetchLatestRelease();

        assertFalse(result.isPresent());
    }

    @Test
    void shouldDownloadAssetFromMavenCentral() throws Exception {
        byte[] jarContent = "fake-jar-content".getBytes(StandardCharsets.UTF_8);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueBinaryResponse(200, jarContent);
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("https://repo1.maven.org/maven2/me/golemcore/bot/0.5.0/bot-0.5.0.jar")
                .build();

        try (InputStream stream = adapter.downloadAsset(release)) {
            byte[] downloaded = stream.readAllBytes();
            assertEquals("fake-jar-content", new String(downloaded, StandardCharsets.UTF_8));
        }
    }

    @Test
    void shouldThrowWhenDownloadFails() throws Exception {
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(404, "Not Found");
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("https://repo1.maven.org/maven2/me/golemcore/bot/0.5.0/bot-0.5.0.jar")
                .build();

        assertThrows(IOException.class, () -> adapter.downloadAsset(release));
    }

    @Test
    void shouldDownloadSha1Checksum() throws Exception {
        String sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, sha1);
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .checksumUrl("https://repo1.maven.org/maven2/me/golemcore/bot/0.5.0/bot-0.5.0.jar.sha1")
                .build();

        ReleaseSourcePort.ChecksumInfo checksumInfo = adapter.downloadChecksum(release);

        assertEquals(sha1, checksumInfo.hexDigest());
        assertEquals("SHA-1", checksumInfo.algorithm());
        assertEquals("bot-0.5.0.jar", checksumInfo.assetName());
    }

    @Test
    void shouldRejectUntrustedUri() {
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, new StubHttpClient());

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("https://evil.com/bot-0.5.0.jar")
                .build();

        assertThrows(IllegalStateException.class, () -> adapter.downloadAsset(release));
    }

    @Test
    void shouldRejectHttpUri() {
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, new StubHttpClient());

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("http://repo1.maven.org/maven2/me/golemcore/bot/0.5.0/bot-0.5.0.jar")
                .build();

        assertThrows(IllegalStateException.class, () -> adapter.downloadAsset(release));
    }

    @Test
    void shouldRejectNullDownloadUri() {
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, new StubHttpClient());

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl(null)
                .build();

        assertThrows(Exception.class, () -> adapter.downloadAsset(release));
    }

    @Test
    void shouldBuildFallbackUrlWhenDownloadUrlIsBlank() throws Exception {
        byte[] jarContent = "fallback-jar".getBytes(StandardCharsets.UTF_8);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueBinaryResponse(200, jarContent);
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .downloadUrl("")
                .build();

        try (InputStream stream = adapter.downloadAsset(release)) {
            byte[] downloaded = stream.readAllBytes();
            assertEquals("fallback-jar", new String(downloaded, StandardCharsets.UTF_8));
        }
    }

    @Test
    void shouldBuildFallbackChecksumUrlWhenBlank() throws Exception {
        String sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, sha1);
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .checksumUrl("")
                .build();

        ReleaseSourcePort.ChecksumInfo info = adapter.downloadChecksum(release);
        assertEquals(sha1, info.hexDigest());
    }

    @Test
    void shouldThrowWhenChecksumDownloadFails() {
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(404, "Not Found");
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, httpClient);

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .checksumUrl("https://repo1.maven.org/maven2/me/golemcore/bot/0.5.0/bot-0.5.0.jar.sha1")
                .build();

        assertThrows(IOException.class, () -> adapter.downloadChecksum(release));
    }

    @Test
    void shouldReturnEmptyWhenVersionIsBlank() throws Exception {
        String searchResponse = """
                {
                  "response": {
                    "numFound": 1,
                    "docs": [
                      {
                        "g": "me.golemcore",
                        "a": "bot",
                        "latestVersion": "  "
                      }
                    ]
                  }
                }
                """;

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, searchResponse);
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, httpClient);

        Optional<AvailableRelease> result = adapter.fetchLatestRelease();
        assertFalse(result.isPresent());
    }

    @Test
    void shouldRejectUntrustedChecksumUri() {
        adapter = new StubMavenCentralAdapter(objectMapper, botProperties, new StubHttpClient());

        AvailableRelease release = AvailableRelease.builder()
                .version("0.5.0")
                .assetName("bot-0.5.0.jar")
                .checksumUrl("https://evil.com/bot-0.5.0.jar.sha1")
                .build();

        assertThrows(IllegalStateException.class, () -> adapter.downloadChecksum(release));
    }

    @SuppressWarnings("PMD.TestClassWithoutTestCases")
    private static class StubMavenCentralAdapter extends MavenCentralReleaseSourceAdapter {

        private final StubHttpClient stubClient;

        StubMavenCentralAdapter(ObjectMapper objectMapper, BotProperties botProperties, StubHttpClient stubClient) {
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

        void enqueueBinaryResponse(int statusCode, byte[] body) {
            exchanges.addLast(new StubExchange(statusCode, body));
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
            return Redirect.NORMAL;
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
