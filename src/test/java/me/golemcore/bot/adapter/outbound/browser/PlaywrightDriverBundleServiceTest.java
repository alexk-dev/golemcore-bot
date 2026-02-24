package me.golemcore.bot.adapter.outbound.browser;

import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("PMD.CloseResource")
class PlaywrightDriverBundleServiceTest {

    private static final String PROPERTY_PLAYWRIGHT_CLI_DIR = "playwright.cli.dir";
    private static final String PLATFORM = "linux-arm64";

    @AfterEach
    void tearDown() {
        System.clearProperty(PROPERTY_PLAYWRIGHT_CLI_DIR);
    }

    @Test
    void shouldDownloadAndExtractDriverBundle(@TempDir Path tempDir) throws Exception {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueBinaryResponse(200, createBundleJar(PLATFORM));

        TestablePlaywrightDriverBundleService service = new TestablePlaywrightDriverBundleService(
                properties,
                httpClient,
                "1.49.0",
                PLATFORM);

        Path driverDir = service.ensureDriverReady();
        Path managedRoot = tempDir.resolve("playwright-driver");
        Path expectedDriverDir = managedRoot.resolve("installs").resolve("1.49.0").resolve(PLATFORM);
        Path expectedBundle = managedRoot.resolve("bundles").resolve("driver-bundle-1.49.0.jar");

        assertEquals(expectedDriverDir, driverDir);
        assertTrue(Files.exists(expectedBundle));
        assertTrue(Files.exists(expectedDriverDir.resolve("package").resolve("cli.js")));
        assertTrue(Files.exists(expectedDriverDir.resolve("node")));
        assertEquals(expectedDriverDir.toString(), System.getProperty(PROPERTY_PLAYWRIGHT_CLI_DIR));
        assertEquals(
                URI.create(
                        "https://repo.maven.apache.org/maven2/com/microsoft/playwright/driver-bundle/1.49.0/driver-bundle-1.49.0.jar"),
                httpClient.getRequestedUris().get(0));
    }

    @Test
    void shouldReuseInstalledDriverWithoutDownload(@TempDir Path tempDir) throws Exception {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());

        Path managedRoot = tempDir.resolve("playwright-driver");
        Path installed = managedRoot.resolve("installs").resolve("1.49.0").resolve(PLATFORM);
        Files.createDirectories(installed.resolve("package"));
        Files.writeString(installed.resolve("package").resolve("cli.js"), "console.log('ok');", StandardCharsets.UTF_8);
        Files.writeString(installed.resolve("node"), "#!/usr/bin/env node", StandardCharsets.UTF_8);

        StubHttpClient httpClient = new StubHttpClient();
        TestablePlaywrightDriverBundleService service = new TestablePlaywrightDriverBundleService(
                properties,
                httpClient,
                "1.49.0",
                PLATFORM);

        Path driverDir = service.ensureDriverReady();

        assertEquals(installed, driverDir);
        assertTrue(httpClient.getRequestedUris().isEmpty());
        assertEquals(installed.toString(), System.getProperty(PROPERTY_PLAYWRIGHT_CLI_DIR));
    }

    @Test
    void shouldKeepOldDriverBundlesWhenInstallingNewVersion(@TempDir Path tempDir) throws Exception {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());

        Path managedRoot = tempDir.resolve("playwright-driver");
        Path bundlesDir = managedRoot.resolve("bundles");
        Files.createDirectories(bundlesDir);
        Path oldBundle = bundlesDir.resolve("driver-bundle-1.48.0.jar");
        Files.writeString(oldBundle, "old", StandardCharsets.UTF_8);

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueBinaryResponse(200, createBundleJar(PLATFORM));

        TestablePlaywrightDriverBundleService service = new TestablePlaywrightDriverBundleService(
                properties,
                httpClient,
                "1.50.0",
                PLATFORM);

        service.ensureDriverReady();

        Path newBundle = bundlesDir.resolve("driver-bundle-1.50.0.jar");
        assertTrue(Files.exists(oldBundle));
        assertTrue(Files.exists(newBundle));
        assertFalse(Files.isSameFile(oldBundle, newBundle));
    }

    private static byte[] createBundleJar(String platform) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            addJarEntry(jar, "driver/" + platform + "/package/cli.js",
                    "console.log('ok');".getBytes(StandardCharsets.UTF_8));
            addJarEntry(jar, "driver/" + platform + "/node", "#!/usr/bin/env node".getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static void addJarEntry(JarOutputStream jar, String name, byte[] content) throws IOException {
        JarEntry entry = new JarEntry(name);
        jar.putNextEntry(entry);
        jar.write(content);
        jar.closeEntry();
    }

    private static final class TestablePlaywrightDriverBundleService extends PlaywrightDriverBundleService {

        private final StubHttpClient stubHttpClient;
        private final String playwrightVersion;
        private final String platform;

        private TestablePlaywrightDriverBundleService(
                BotProperties botProperties,
                StubHttpClient stubHttpClient,
                String playwrightVersion,
                String platform) {
            super(botProperties);
            this.stubHttpClient = stubHttpClient;
            this.playwrightVersion = playwrightVersion;
            this.platform = platform;
        }

        @Override
        protected HttpClient buildHttpClient() {
            return stubHttpClient;
        }

        @Override
        protected String resolvePlaywrightVersion() {
            return playwrightVersion;
        }

        @Override
        protected String resolvePlatformClassifier() {
            return platform;
        }
    }

    private static final class StubHttpClient extends HttpClient {

        private final Deque<StubExchange> exchanges = new ArrayDeque<>();
        private final Deque<URI> requestedUris = new ArrayDeque<>();

        private void enqueueBinaryResponse(int statusCode, byte[] responseBody) {
            exchanges.addLast(new StubExchange(statusCode, responseBody, Map.of()));
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            requestedUris.add(request.uri());
            StubExchange exchange = exchanges.pollFirst();
            if (exchange == null) {
                throw new IllegalStateException("No stub exchange configured for request " + request.uri());
            }

            HttpHeaders headers = HttpHeaders.of(exchange.responseHeaders(), (name, value) -> true);
            HttpResponse.ResponseInfo responseInfo = new StubResponseInfo(exchange.statusCode(), headers);
            HttpResponse.BodySubscriber<T> subscriber = responseBodyHandler.apply(responseInfo);
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // no-op
                }

                @Override
                public void cancel() {
                    // no-op
                }
            });
            byte[] payload = exchange.responseBody();
            subscriber.onNext(List.of(ByteBuffer.wrap(payload)));
            subscriber.onComplete();
            T body = subscriber.getBody().toCompletableFuture().join();
            return new StubHttpResponse<>(request, exchange.statusCode(), body, headers);
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

        private List<URI> getRequestedUris() {
            return List.copyOf(requestedUris);
        }
    }

    private record StubExchange(
            int statusCode,
            byte[] responseBody,
            Map<String, List<String>> responseHeaders) {
    }

    private static final class StubResponseInfo implements HttpResponse.ResponseInfo {

        private final int statusCodeValue;
        private final HttpHeaders headersValue;

        private StubResponseInfo(int statusCode, HttpHeaders headers) {
            this.statusCodeValue = statusCode;
            this.headersValue = headers;
        }

        @Override
        public int statusCode() {
            return statusCodeValue;
        }

        @Override
        public HttpHeaders headers() {
            return headersValue;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class StubHttpResponse<T> implements HttpResponse<T> {

        private final HttpRequest requestValue;
        private final int statusCodeValue;
        private final T bodyValue;
        private final HttpHeaders headersValue;

        private StubHttpResponse(HttpRequest request, int statusCode, T body, HttpHeaders headers) {
            this.requestValue = request;
            this.statusCodeValue = statusCode;
            this.bodyValue = body;
            this.headersValue = headers;
        }

        @Override
        public int statusCode() {
            return statusCodeValue;
        }

        @Override
        public HttpRequest request() {
            return requestValue;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headersValue;
        }

        @Override
        public T body() {
            return bodyValue;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return requestValue.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
