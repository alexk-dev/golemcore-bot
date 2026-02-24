package me.golemcore.bot.adapter.outbound.browser;

import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import java.time.Duration;
import java.util.Arrays;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        StubDriverBundleService service = new StubDriverBundleService(
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
        StubDriverBundleService service = new StubDriverBundleService(
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

        StubDriverBundleService service = new StubDriverBundleService(
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

    @Test
    void shouldRejectArchiveWithPathTraversalEntry(@TempDir Path tempDir) {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueBinaryResponse(200, createPathTraversalBundleJar(PLATFORM));

        StubDriverBundleService service = new StubDriverBundleService(
                properties,
                httpClient,
                "1.51.0",
                PLATFORM);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::ensureDriverReady);
        assertTrue(exception.getMessage().contains("escapes target directory"));
    }

    @Test
    void shouldRejectArchiveWithSuspiciousCompressionRatio(@TempDir Path tempDir) {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueBinaryResponse(200, createSuspiciousCompressionBundleJar(PLATFORM));

        StubDriverBundleService service = new StubDriverBundleService(
                properties,
                httpClient,
                "1.52.0",
                PLATFORM);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::ensureDriverReady);
        assertTrue(exception.getMessage().contains("suspicious compression ratio"));
    }

    @Test
    void shouldUsePreconfiguredDriverDirWithoutDownload(@TempDir Path tempDir) throws Exception {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());

        Path preconfiguredDir = tempDir.resolve("external-driver");
        Files.createDirectories(preconfiguredDir.resolve("package"));
        Files.writeString(preconfiguredDir.resolve("package").resolve("cli.js"), "console.log('ok');",
                StandardCharsets.UTF_8);
        Files.writeString(preconfiguredDir.resolve("node"), "#!/usr/bin/env node", StandardCharsets.UTF_8);
        System.setProperty(PROPERTY_PLAYWRIGHT_CLI_DIR, preconfiguredDir.toString());

        StubHttpClient httpClient = new StubHttpClient();
        StubDriverBundleService service = new StubDriverBundleService(
                properties,
                httpClient,
                "1.53.0",
                PLATFORM);

        Path resolved = service.ensureDriverReady();

        assertEquals(preconfiguredDir.toAbsolutePath().normalize(), resolved);
        assertTrue(httpClient.getRequestedUris().isEmpty());
    }

    @Test
    void shouldIgnoreBrokenPreconfiguredDriverDirAndInstallManaged(@TempDir Path tempDir) throws Exception {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());

        Path brokenPreconfiguredDir = tempDir.resolve("broken-driver");
        Files.createDirectories(brokenPreconfiguredDir);
        System.setProperty(PROPERTY_PLAYWRIGHT_CLI_DIR, brokenPreconfiguredDir.toString());

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueBinaryResponse(200, createBundleJar(PLATFORM));

        StubDriverBundleService service = new StubDriverBundleService(
                properties,
                httpClient,
                "1.54.0",
                PLATFORM);

        Path resolved = service.ensureDriverReady();

        Path expectedManagedDir = tempDir.resolve("playwright-driver")
                .resolve("installs")
                .resolve("1.54.0")
                .resolve(PLATFORM);
        assertEquals(expectedManagedDir, resolved);
        assertEquals(expectedManagedDir.toString(), System.getProperty(PROPERTY_PLAYWRIGHT_CLI_DIR));
        assertEquals(1, httpClient.getRequestedUris().size());
    }

    @Test
    void shouldFallbackToSecondaryMavenRepositoryWhenPrimaryFails(@TempDir Path tempDir) throws Exception {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueBinaryResponse(404, new byte[0]);
        httpClient.enqueueBinaryResponse(200, createBundleJar(PLATFORM));

        StubDriverBundleService service = new StubDriverBundleService(
                properties,
                httpClient,
                "1.55.0",
                PLATFORM);

        Path resolved = service.ensureDriverReady();

        assertTrue(Files.exists(resolved.resolve("package").resolve("cli.js")));
        assertEquals(2, httpClient.getRequestedUris().size());
        assertEquals(
                URI.create(
                        "https://repo.maven.apache.org/maven2/com/microsoft/playwright/driver-bundle/1.55.0/driver-bundle-1.55.0.jar"),
                httpClient.getRequestedUris().get(0));
        assertEquals(
                URI.create(
                        "https://repo1.maven.org/maven2/com/microsoft/playwright/driver-bundle/1.55.0/driver-bundle-1.55.0.jar"),
                httpClient.getRequestedUris().get(1));
    }

    @Test
    void shouldRejectBundleWithoutCurrentPlatformAndCleanupTempDirectory(@TempDir Path tempDir) throws Exception {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueBinaryResponse(200, createBundleJar("linux"));

        StubDriverBundleService service = new StubDriverBundleService(
                properties,
                httpClient,
                "1.56.0",
                PLATFORM);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::ensureDriverReady);
        assertTrue(exception.getMessage().contains("is not present in Playwright bundle"));

        Path tempInstallDir = tempDir.resolve("playwright-driver")
                .resolve("installs")
                .resolve("1.56.0")
                .resolve(PLATFORM + ".tmp");
        assertFalse(Files.exists(tempInstallDir));
    }

    @Test
    void shouldRejectBundlePathWhenBundleIsDirectory(@TempDir Path tempDir) throws Exception {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());

        Path bundlePath = tempDir.resolve("playwright-driver")
                .resolve("bundles")
                .resolve("driver-bundle-1.57.0.jar");
        Files.createDirectories(bundlePath);

        StubHttpClient httpClient = new StubHttpClient();
        StubDriverBundleService service = new StubDriverBundleService(
                properties,
                httpClient,
                "1.57.0",
                PLATFORM);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::ensureDriverReady);
        assertTrue(exception.getMessage().contains("not a regular file"));
        assertTrue(httpClient.getRequestedUris().isEmpty());
    }

    @Test
    void shouldReplaceCorruptedInstallDirectory(@TempDir Path tempDir) throws Exception {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());

        Path installDir = tempDir.resolve("playwright-driver")
                .resolve("installs")
                .resolve("1.58.0")
                .resolve(PLATFORM);
        Files.createDirectories(installDir.resolve("package"));
        Files.writeString(installDir.resolve("package").resolve("cli.js"), "console.log('stale');",
                StandardCharsets.UTF_8);
        Files.writeString(installDir.resolve("stale.txt"), "stale", StandardCharsets.UTF_8);

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueBinaryResponse(200, createBundleJar(PLATFORM));

        StubDriverBundleService service = new StubDriverBundleService(
                properties,
                httpClient,
                "1.58.0",
                PLATFORM);

        Path resolved = service.ensureDriverReady();

        assertEquals(installDir, resolved);
        assertTrue(Files.exists(installDir.resolve("node")));
        assertTrue(Files.exists(installDir.resolve("package").resolve("cli.js")));
        assertFalse(Files.exists(installDir.resolve("stale.txt")));
        assertEquals(1, httpClient.getRequestedUris().size());
    }

    @Test
    void shouldFailWhenExtractedDriverIsIncomplete(@TempDir Path tempDir) {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueBinaryResponse(200, createCliOnlyBundleJar(PLATFORM));

        StubDriverBundleService service = new StubDriverBundleService(
                properties,
                httpClient,
                "1.59.0",
                PLATFORM);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::ensureDriverReady);
        assertTrue(exception.getMessage().contains("installation is incomplete"));
    }

    @Test
    void shouldResolveRealPlaywrightVersionFromClasspath(@TempDir Path tempDir) {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());

        PlatformProbeDriverService service = new PlatformProbeDriverService(properties);
        String version = service.resolvePlaywrightVersion();

        assertTrue(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+.*"));
    }

    @Test
    void shouldResolvePlatformClassifierBranches(@TempDir Path tempDir) {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());
        PlatformProbeDriverService service = new PlatformProbeDriverService(properties);

        String originalOsName = System.getProperty("os.name");
        String originalOsArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Linux");
            System.setProperty("os.arch", "amd64");
            assertEquals("linux", service.resolvePlatformClassifier());

            System.setProperty("os.name", "Linux");
            System.setProperty("os.arch", "aarch64");
            assertEquals("linux-arm64", service.resolvePlatformClassifier());

            System.setProperty("os.name", "Mac OS X");
            System.setProperty("os.arch", "arm64");
            assertEquals("mac-arm64", service.resolvePlatformClassifier());

            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "x86_64");
            assertEquals("win32_x64", service.resolvePlatformClassifier());
        } finally {
            restoreSystemProperty("os.name", originalOsName);
            restoreSystemProperty("os.arch", originalOsArch);
        }
    }

    @Test
    void shouldRejectUnsupportedPlatformClassifier(@TempDir Path tempDir) {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());
        PlatformProbeDriverService service = new PlatformProbeDriverService(properties);

        String originalOsName = System.getProperty("os.name");
        String originalOsArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "arm64");
            IllegalStateException unsupportedWindowsArch = assertThrows(
                    IllegalStateException.class, service::resolvePlatformClassifier);
            assertTrue(unsupportedWindowsArch.getMessage().contains("Unsupported Windows architecture"));

            System.setProperty("os.name", "Solaris");
            System.setProperty("os.arch", "x86_64");
            IllegalStateException unsupportedOs = assertThrows(
                    IllegalStateException.class, service::resolvePlatformClassifier);
            assertTrue(unsupportedOs.getMessage().contains("Unsupported OS for Playwright driver"));
        } finally {
            restoreSystemProperty("os.name", originalOsName);
            restoreSystemProperty("os.arch", originalOsArch);
        }
    }

    @Test
    void shouldFailAfterAllRepositoryDownloadAttempts(@TempDir Path tempDir) {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueBinaryResponse(404, new byte[0]);
        httpClient.enqueueBinaryResponse(404, new byte[0]);

        StubDriverBundleService service = new StubDriverBundleService(
                properties,
                httpClient,
                "1.60.0",
                PLATFORM);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::ensureDriverReady);
        assertTrue(exception.getMessage().contains("Failed to download Playwright driver bundle"));
        assertEquals(2, httpClient.getRequestedUris().size());
    }

    @Test
    void shouldValidateVersionNormalizationAndComparisonViaPrivateMethods(@TempDir Path tempDir) {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());
        PlatformProbeDriverService service = new PlatformProbeDriverService(properties);

        assertEquals("1.2.3", invokePrivate(service, "normalizeVersion", new Class<?>[] { String.class }, "v1.2.3"));

        IllegalStateException invalidVersion = assertThrows(
                IllegalStateException.class,
                () -> invokePrivate(service, "normalizeVersion", new Class<?>[] { String.class }, "../1.2.3"));
        assertTrue(invalidVersion.getMessage().contains("prohibited characters"));

        int semverComparison = (Integer) invokePrivate(
                service,
                "compareVersions",
                new Class<?>[] { String.class, String.class },
                "1.2.3",
                "1.2.3-alpha");
        assertTrue(semverComparison > 0);

        int lexicalFallback = (Integer) invokePrivate(
                service,
                "compareVersions",
                new Class<?>[] { String.class, String.class },
                "foo",
                "bar");
        assertTrue(lexicalFallback > 0);
    }

    @Test
    void shouldRejectUnsafeBundleUrisViaPrivateValidation(@TempDir Path tempDir) {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());
        PlatformProbeDriverService service = new PlatformProbeDriverService(properties);

        assertThrows(
                IllegalStateException.class,
                () -> invokePrivate(
                        service,
                        "requireTrustedMavenUri",
                        new Class<?>[] { URI.class },
                        URI.create("http://repo.maven.apache.org/maven2/a.jar")));

        assertThrows(
                IllegalStateException.class,
                () -> invokePrivate(
                        service,
                        "requireTrustedMavenUri",
                        new Class<?>[] { URI.class },
                        URI.create("https://repo.maven.apache.org:444/maven2/a.jar")));

        assertThrows(
                IllegalStateException.class,
                () -> invokePrivate(
                        service,
                        "requireTrustedMavenUri",
                        new Class<?>[] { URI.class },
                        URI.create("https://evil.example/maven2/a.jar")));
    }

    @Test
    void shouldRejectBundleFileValidationForEmptyFile(@TempDir Path tempDir) throws Exception {
        BotProperties properties = new BotProperties();
        properties.getUpdate().setUpdatesPath(tempDir.toString());
        PlatformProbeDriverService service = new PlatformProbeDriverService(properties);

        Path emptyBundle = tempDir.resolve("empty.jar");
        Files.createFile(emptyBundle);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> invokePrivate(
                        service,
                        "validateBundleJarFile",
                        new Class<?>[] { Path.class },
                        emptyBundle));
        assertTrue(exception.getMessage().contains("is empty"));
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

    private static byte[] createCliOnlyBundleJar(String platform) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            addJarEntryUnchecked(jar, "driver/" + platform + "/package/cli.js",
                    "console.log('ok');".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build cli-only bundle jar", e);
        }
        return output.toByteArray();
    }

    private static byte[] createPathTraversalBundleJar(String platform) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            addJarEntryUnchecked(jar, "driver/" + platform + "/../../escape.txt",
                    "bad".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build path traversal bundle jar", e);
        }
        return output.toByteArray();
    }

    private static byte[] createSuspiciousCompressionBundleJar(String platform) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            byte[] payload = new byte[2 * 1024 * 1024];
            Arrays.fill(payload, (byte) 0);
            addJarEntryUnchecked(jar, "driver/" + platform + "/package/payload.bin", payload);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build suspicious compression bundle jar", e);
        }
        return output.toByteArray();
    }

    private static void addJarEntry(JarOutputStream jar, String name, byte[] content) throws IOException {
        JarEntry entry = new JarEntry(name);
        jar.putNextEntry(entry);
        jar.write(content);
        jar.closeEntry();
    }

    private static void addJarEntryUnchecked(JarOutputStream jar, String name, byte[] content) {
        try {
            addJarEntry(jar, name, content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to add test jar entry: " + name, e);
        }
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static Object invokePrivate(
            Object target,
            String methodName,
            Class<?>[] parameterTypes,
            Object... args) {
        try {
            Method method = PlaywrightDriverBundleService.class.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Private invocation failed: " + methodName, cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke private method: " + methodName, e);
        }
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
            return;
        }
        System.setProperty(name, value);
    }

    private static final class PlatformProbeDriverService extends PlaywrightDriverBundleService {

        private PlatformProbeDriverService(BotProperties botProperties) {
            super(botProperties);
        }
    }

    private static final class StubDriverBundleService extends PlaywrightDriverBundleService {

        private final StubHttpClient stubHttpClient;
        private final String playwrightVersion;
        private final String platform;

        private StubDriverBundleService(
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
