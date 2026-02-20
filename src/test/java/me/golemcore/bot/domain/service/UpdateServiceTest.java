package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.UpdateIntent;
import me.golemcore.bot.domain.model.UpdateState;
import me.golemcore.bot.domain.model.UpdateStatus;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.CloseResource")
class UpdateServiceTest {

    private static final Instant BASE_TIME = Instant.parse("2026-02-19T12:00:00Z");
    private static final String VERSION_CURRENT = "0.3.0";
    private static final String VERSION_PATCH = "0.3.1";
    private static final long DEFAULT_JAR_ASSET_ID = 101L;
    private static final long DEFAULT_SHA_ASSET_ID = 102L;

    private BotProperties botProperties;
    private ObjectProvider<BuildProperties> buildPropertiesProvider;
    private ApplicationContext applicationContext;
    private JvmExitService jvmExitService;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        botProperties = new BotProperties();
        buildPropertiesProvider = mock(ObjectProvider.class);
        applicationContext = mock(ApplicationContext.class);
        jvmExitService = mock(JvmExitService.class);
        clock = new MutableClock(BASE_TIME, ZoneOffset.UTC);

        Properties props = new Properties();
        props.setProperty("version", VERSION_CURRENT);
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(new BuildProperties(props));
    }

    @Test
    void shouldReturnDisabledStateWhenUpdateFeatureIsOff() {
        botProperties.getUpdate().setEnabled(false);

        UpdateService service = new UpdateService(
                botProperties,
                buildPropertiesProvider,
                new ObjectMapper(),
                applicationContext,
                clock,
                jvmExitService);

        UpdateStatus status = service.getStatus();

        assertFalse(status.isEnabled());
        assertEquals(UpdateState.DISABLED, status.getState());
        assertNotNull(status.getCurrent());
        assertEquals(VERSION_CURRENT, status.getCurrent().getVersion());
    }

    @Test
    void shouldUseDevVersionWhenBuildPropertiesAreUnavailable() {
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(null);

        UpdateService service = createService();
        UpdateStatus status = service.getStatus();

        assertEquals("dev", status.getCurrent().getVersion());
        assertEquals("image", status.getCurrent().getSource());
    }

    @Test
    void shouldRejectCheckWhenUpdateFeatureIsDisabled() {
        botProperties.getUpdate().setEnabled(false);
        UpdateService service = createService();

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::check);

        assertEquals("Update feature is disabled", exception.getMessage());
    }

    @Test
    void shouldRejectPrepareWhenUpdateFeatureIsDisabled() {
        botProperties.getUpdate().setEnabled(false);
        UpdateService service = createService();

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::prepare);

        assertEquals("Update feature is disabled", exception.getMessage());
    }

    @Test
    void shouldRejectPrepareWhenNoAvailableUpdateExists(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        UpdateService service = createService();

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::prepare);

        assertEquals("No available update. Run check first.", exception.getMessage());
    }

    @Test
    void shouldRejectCreateApplyIntentWhenNoStagedUpdate(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        UpdateService service = createService();

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::createApplyIntent);

        assertEquals("No staged update to apply", exception.getMessage());
    }

    @Test
    void shouldCreateApplyIntentWhenStagedMarkerExists(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        stageJar(tempDir, "bot-" + VERSION_PATCH + ".jar");

        UpdateService service = createService();

        UpdateIntent intent = service.createApplyIntent();

        assertEquals("apply", intent.getOperation());
        assertEquals(VERSION_PATCH, intent.getTargetVersion());
        assertNotNull(intent.getConfirmToken());
        assertEquals(6, intent.getConfirmToken().length());
        assertEquals(BASE_TIME.plus(Duration.ofMinutes(2)), intent.getExpiresAt());
    }

    @Test
    void shouldRejectApplyWhenConfirmTokenIsBlank(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        stageJar(tempDir, "bot-" + VERSION_PATCH + ".jar");
        UpdateService service = createService();
        service.createApplyIntent();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.apply("   "));

        assertEquals("confirmToken is required", exception.getMessage());
    }

    @Test
    void shouldRejectApplyWhenNoPendingIntentExists(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        stageJar(tempDir, "bot-" + VERSION_PATCH + ".jar");
        UpdateService service = createService();

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.apply("ABC123"));

        assertEquals("No pending confirmation intent", exception.getMessage());
    }

    @Test
    void shouldRejectApplyWhenPendingIntentIsForRollback(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        stageJar(tempDir, "bot-" + VERSION_PATCH + ".jar");
        cacheJar(tempDir, "0.2.9");
        UpdateService service = createService();
        UpdateIntent rollbackIntent = service.createRollbackIntent("0.2.9");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.apply(rollbackIntent.getConfirmToken()));

        assertEquals("Pending confirmation is for operation: rollback", exception.getMessage());
    }

    @Test
    void shouldRejectApplyWhenConfirmTokenIsInvalid(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        stageJar(tempDir, "bot-" + VERSION_PATCH + ".jar");
        UpdateService service = createService();

        service.createApplyIntent();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.apply("WRONG1"));

        assertEquals("Invalid confirmation token", exception.getMessage());
    }

    @Test
    void shouldRejectApplyWhenTokenExpiresExactlyAtBoundary(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        stageJar(tempDir, "bot-" + VERSION_PATCH + ".jar");
        UpdateService service = createService();
        UpdateIntent intent = service.createApplyIntent();

        Files.deleteIfExists(tempDir.resolve("staged.txt"));
        clock.setInstant(intent.getExpiresAt());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.apply(intent.getConfirmToken()));

        assertEquals("Confirmation token has expired", exception.getMessage());
    }

    @Test
    void shouldRejectApplyWhenStagedArtifactIsMissingAfterIntentCreation(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        stageJar(tempDir, "bot-" + VERSION_PATCH + ".jar");
        UpdateService service = createService();
        UpdateIntent intent = service.createApplyIntent();

        Files.deleteIfExists(tempDir.resolve("staged.txt"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.apply(intent.getConfirmToken()));

        assertEquals("No staged update to apply", exception.getMessage());
    }

    @Test
    void shouldCreateRollbackIntentForCachedVersion(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        cacheJar(tempDir, "0.2.9");
        UpdateService service = createService();

        UpdateIntent intent = service.createRollbackIntent("0.2.9");

        assertEquals("rollback", intent.getOperation());
        assertEquals("0.2.9", intent.getTargetVersion());
        assertNotNull(intent.getConfirmToken());
    }

    @Test
    void shouldCreateRollbackIntentWithoutVersionForImageRollback(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        UpdateService service = createService();

        UpdateIntent intent = service.createRollbackIntent(null);

        assertEquals("rollback", intent.getOperation());
        assertNull(intent.getTargetVersion());
        assertNotNull(intent.getConfirmToken());
    }

    @Test
    void shouldRejectRollbackIntentWhenVersionIsNotCached(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        UpdateService service = createService();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.createRollbackIntent("0.1.9"));

        assertEquals("No cached update jars found", exception.getMessage());
    }

    @Test
    void shouldRejectRollbackWhenNoPendingIntentExists(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        UpdateService service = createService();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.rollback("ABC123", null));

        assertEquals("No pending confirmation intent", exception.getMessage());
    }

    @Test
    void shouldRejectRollbackWhenConfirmTokenIsBlank(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        UpdateService service = createService();
        service.createRollbackIntent(null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.rollback(" ", null));

        assertEquals("confirmToken is required", exception.getMessage());
    }

    @Test
    void shouldRejectRollbackWhenVersionDoesNotMatchIssuedIntent(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        cacheJar(tempDir, "0.2.9");
        UpdateService service = createService();
        UpdateIntent intent = service.createRollbackIntent("0.2.9");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.rollback(intent.getConfirmToken(), "0.2.8"));

        assertEquals("Confirmation token was issued for another version", exception.getMessage());
    }

    @Test
    void shouldRejectRollbackWhenTokenExpiresExactlyAtBoundary(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        cacheJar(tempDir, "0.2.9");
        UpdateService service = createService();
        UpdateIntent intent = service.createRollbackIntent("0.2.9");

        clock.setInstant(intent.getExpiresAt());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.rollback(intent.getConfirmToken(), "0.2.9"));

        assertEquals("Confirmation token has expired", exception.getMessage());
    }

    @Test
    void shouldAcceptTrimmedRollbackTokenAndContinueValidation(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        UpdateService service = createService();
        UpdateIntent intent = service.createRollbackIntent(null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.rollback(" " + intent.getConfirmToken() + " ", "9.9.9"));

        assertTrue(exception.getMessage().contains("cached"));
    }

    @Test
    void shouldFallbackToImageSourceWhenStagedMarkerContainsTraversalPath(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        Files.writeString(tempDir.resolve("staged.txt"), "../outside.jar", StandardCharsets.UTF_8);
        UpdateService service = createService();

        UpdateStatus status = service.getStatus();

        assertNull(status.getStaged());
        assertEquals("image", status.getCurrent().getSource());
    }

    @Test
    void shouldReturnNoUpdatesWhenReleaseApiReturnsNotFound(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(404, "{}");
        TestableUpdateService service = createTestableService(httpClient);

        assertEquals("No updates found", service.check().getMessage());
        assertEquals(UpdateState.IDLE, service.getStatus().getState());
        assertNull(service.getStatus().getAvailable());
        assertEquals("check", service.getHistory().getFirst().getOperation());
        assertEquals("SUCCESS", service.getHistory().getFirst().getResult());
    }

    @Test
    void shouldReportAlreadyUpToDateWhenRemoteVersionIsNotNewer(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson(
                "v0.3.0",
                "bot-0.3.0.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.0/bot-0.3.0.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.0/sha256sums.txt",
                "2026-02-19T11:00:00Z"));
        TestableUpdateService service = createTestableService(httpClient);

        assertEquals("Already up to date", service.check().getMessage());
        assertNull(service.getStatus().getAvailable());
    }

    @Test
    void shouldSuggestDockerRolloutWhenMinorOrMajorReleaseIsFound(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson(
                "v0.4.0",
                "bot-0.4.0.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.4.0/bot-0.4.0.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.4.0/sha256sums.txt",
                "2026-02-19T11:00:00Z"));
        TestableUpdateService service = createTestableService(httpClient);

        assertTrue(service.check().getMessage().contains("Docker image upgrade"));
        assertNull(service.getStatus().getAvailable());
    }

    @Test
    void shouldDiscoverPatchUpdateAndPrepareStagedJar(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        botProperties.getUpdate().setMaxKeptVersions(1);
        cacheJar(tempDir, "0.2.9");
        cacheJar(tempDir, "0.2.8");

        byte[] jarBytes = "new-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256Hex(jarBytes);

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson(
                "v0.3.1",
                "bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/sha256sums.txt",
                "2026-02-19T11:00:00Z"));
        httpClient.enqueueBinaryResponse(200, jarBytes);
        httpClient.enqueueStringResponse(200, checksum + "  bot-0.3.1.jar\n");
        TestableUpdateService service = createTestableService(httpClient);

        assertEquals("Update available: 0.3.1", service.check().getMessage());
        assertEquals("Update staged: 0.3.1", service.prepare().getMessage());

        String stagedMarker = Files.readString(tempDir.resolve("staged.txt"), StandardCharsets.UTF_8).trim();
        assertEquals("bot-0.3.1.jar", stagedMarker);
        assertTrue(Files.exists(tempDir.resolve("jars").resolve("bot-0.3.1.jar")));
        try (java.util.stream.Stream<Path> stream = Files.list(tempDir.resolve("jars"))) {
            assertTrue(stream.count() <= 2);
        }
        assertEquals(
                URI.create("https://api.github.com/repos/alexk-dev/golemcore-bot/releases/latest"),
                httpClient.getRequestedUris().get(0));
        assertEquals(
                URI.create("https://api.github.com/repos/alexk-dev/golemcore-bot/releases/assets/"
                        + DEFAULT_JAR_ASSET_ID),
                httpClient.getRequestedUris().get(1));
        assertEquals(
                URI.create("https://api.github.com/repos/alexk-dev/golemcore-bot/releases/assets/"
                        + DEFAULT_SHA_ASSET_ID),
                httpClient.getRequestedUris().get(2));
        assertEquals(UpdateState.STAGED, service.getStatus().getState());
    }

    @Test
    void shouldFailPrepareWhenChecksumDoesNotMatchAndCleanTempJar(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson(
                "v0.3.1",
                "bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/sha256sums.txt",
                "2026-02-19T11:00:00Z"));
        httpClient.enqueueBinaryResponse(200, "jar-content".getBytes(StandardCharsets.UTF_8));
        httpClient.enqueueStringResponse(200, "deadbeef bot-0.3.1.jar\n");
        TestableUpdateService service = createTestableService(httpClient);
        service.check();

        IllegalStateException error = assertThrows(IllegalStateException.class, service::prepare);

        assertTrue(error.getMessage().contains("Checksum mismatch"));
        assertFalse(Files.exists(tempDir.resolve("jars").resolve("bot-0.3.1.jar.tmp")));
        assertEquals(UpdateState.AVAILABLE, service.getStatus().getState());
        assertTrue(service.getStatus().getLastError().contains("Failed to prepare update"));
    }

    @Test
    void shouldFailPrepareWhenDownloadRedirectHostIsUntrusted(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson(
                "v0.3.1",
                "bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/sha256sums.txt",
                "2026-02-19T11:00:00Z"));
        httpClient.enqueueRedirectResponse(302, "https://evil.example.com/bot-0.3.1.jar");
        TestableUpdateService service = createTestableService(httpClient);
        service.check();

        IllegalStateException error = assertThrows(IllegalStateException.class, service::prepare);

        assertTrue(error.getMessage().contains("host is not trusted"));
    }

    @Test
    void shouldFailPrepareWhenDownloadRedirectDoesNotContainLocation(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson(
                "v0.3.1",
                "bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/sha256sums.txt",
                "2026-02-19T11:00:00Z"));
        httpClient.enqueueBinaryResponse(302, new byte[0]);
        TestableUpdateService service = createTestableService(httpClient);
        service.check();

        IllegalStateException error = assertThrows(IllegalStateException.class, service::prepare);

        assertTrue(error.getMessage().contains("missing Location header"));
    }

    @Test
    void shouldPrepareWhenDownloadRedirectTargetsTrustedGithubusercontentHost(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        byte[] jarBytes = "redirected-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256Hex(jarBytes);

        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson(
                "v0.3.1",
                "bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/sha256sums.txt",
                "2026-02-19T11:00:00Z"));
        httpClient.enqueueRedirectResponse(302, "https://objects.githubusercontent.com/releases/bot-0.3.1.jar");
        httpClient.enqueueBinaryResponse(200, jarBytes);
        httpClient.enqueueRedirectResponse(302, "https://objects.githubusercontent.com/releases/sha256sums.txt");
        httpClient.enqueueStringResponse(200, checksum + "  bot-0.3.1.jar\n");
        TestableUpdateService service = createTestableService(httpClient);

        assertEquals("Update available: 0.3.1", service.check().getMessage());
        assertEquals("Update staged: 0.3.1", service.prepare().getMessage());
    }

    @Test
    void shouldIgnoreUntrustedBrowserDownloadHost(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson(
                "v0.3.1",
                "bot-0.3.1.jar",
                "https://evil.example.com/bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/sha256sums.txt",
                "2026-02-19T11:00:00Z"));
        TestableUpdateService service = createTestableService(httpClient);

        assertEquals("Update available: 0.3.1", service.check().getMessage());
        assertEquals(UpdateState.AVAILABLE, service.getStatus().getState());
    }

    @Test
    void shouldIgnoreUntrustedBrowserDownloadScheme(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson(
                "v0.3.1",
                "bot-0.3.1.jar",
                "http://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/sha256sums.txt",
                "2026-02-19T11:00:00Z"));
        TestableUpdateService service = createTestableService(httpClient);

        assertEquals("Update available: 0.3.1", service.check().getMessage());
        assertEquals(UpdateState.AVAILABLE, service.getStatus().getState());
    }

    @Test
    void shouldRejectReleaseWhenTagIsMissing(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson(
                "   ",
                "bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/sha256sums.txt",
                "2026-02-19T11:00:00Z"));
        TestableUpdateService service = createTestableService(httpClient);

        IllegalStateException error = assertThrows(IllegalStateException.class, service::check);

        assertTrue(error.getMessage().contains("Release tag is missing"));
    }

    @Test
    void shouldRejectReleaseWhenTagContainsProhibitedCharacters(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJson(
                "release/0.3.1",
                "bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/sha256sums.txt",
                "2026-02-19T11:00:00Z"));
        TestableUpdateService service = createTestableService(httpClient);

        IllegalStateException error = assertThrows(IllegalStateException.class, service::check);

        assertTrue(error.getMessage().contains("Release tag contains prohibited characters"));
    }

    @Test
    void shouldRejectReleaseWhenJarAssetIdIsInvalid(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueStringResponse(200, releaseJsonWithAssetIds(
                "v0.3.1",
                "bot-0.3.1.jar",
                0L,
                DEFAULT_SHA_ASSET_ID,
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/bot-0.3.1.jar",
                "https://github.com/alexk-dev/golemcore-bot/releases/download/v0.3.1/sha256sums.txt",
                "2026-02-19T11:00:00Z"));
        TestableUpdateService service = createTestableService(httpClient);

        IllegalStateException error = assertThrows(IllegalStateException.class, service::check);

        assertTrue(error.getMessage().contains("Release asset id is invalid"));
    }

    @Test
    void shouldHandleInterruptedCheckAndPreserveInterruptFlag(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueueInterrupted(new InterruptedException("simulated interruption"));
        TestableUpdateService service = createTestableService(httpClient);

        IllegalStateException error = assertThrows(IllegalStateException.class, service::check);

        assertTrue(error.getMessage().contains("simulated interruption"));
        assertTrue(Thread.currentThread().isInterrupted());
        assertTrue(Thread.interrupted());
    }

    @Test
    void shouldApplyStagedVersionAndRequestRestart(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        stageJar(tempDir, "bot-" + VERSION_PATCH + ".jar");
        TestableUpdateService service = createTestableService(new StubHttpClient());

        UpdateIntent intent = service.createApplyIntent();

        assertTrue(service.apply(intent.getConfirmToken()).isSuccess());
        assertTrue(service.isRestartRequested());
        assertEquals("bot-" + VERSION_PATCH + ".jar",
                Files.readString(tempDir.resolve("current.txt"), StandardCharsets.UTF_8).trim());
        assertFalse(Files.exists(tempDir.resolve("staged.txt")));
        assertEquals(UpdateState.APPLYING, service.getStatus().getState());
    }

    @Test
    void shouldRollbackToImageAndRequestRestart(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        Files.writeString(tempDir.resolve("current.txt"), "bot-" + VERSION_PATCH + ".jar", StandardCharsets.UTF_8);
        TestableUpdateService service = createTestableService(new StubHttpClient());
        UpdateIntent intent = service.createRollbackIntent(null);

        assertTrue(service.rollback(intent.getConfirmToken(), null).isSuccess());
        assertTrue(service.isRestartRequested());
        assertFalse(Files.exists(tempDir.resolve("current.txt")));
        assertEquals(UpdateState.ROLLED_BACK, service.getStatus().getState());
    }

    @Test
    void shouldRollbackToCachedVersionAndUpdateCurrentMarker(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        cacheJar(tempDir, "0.2.9");
        TestableUpdateService service = createTestableService(new StubHttpClient());
        UpdateIntent intent = service.createRollbackIntent("0.2.9");

        assertTrue(service.rollback(intent.getConfirmToken(), "0.2.9").isSuccess());
        assertTrue(service.isRestartRequested());
        assertEquals("bot-0.2.9.jar", Files.readString(tempDir.resolve("current.txt"), StandardCharsets.UTF_8).trim());
    }

    @Test
    void shouldCoverVersionComparisonAndChecksumHelpersViaReflection(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        UpdateService service = createService();

        assertTrue(invokeCompareVersions(service, "1.2.4", "1.2.3") > 0);
        assertTrue(invokeCompareVersions(service, "1.2.3-rc.1", "1.2.3-rc.2") < 0);
        assertTrue(invokeCompareVersions(service, "1.2.3-alpha", "1.2.3-1") > 0);
        assertTrue(invokeCompareVersions(service, "1.2.3-1", "1.2.3-alpha") < 0);
        assertEquals(0, invokeCompareVersions(service, "v1.2.3+build.1", "1.2.3"));
        assertTrue(invokeCompareVersions(service, "snapshot", "1.0.0") > 0);

        String checksumText = """
                ignored line
                abcdef123456 *bot-1.0.0.jar
                """;
        assertEquals("abcdef123456", invokeExtractExpectedSha256(service, checksumText, "bot-1.0.0.jar"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> invokeExtractExpectedSha256(service, "deadbeef other.jar", "bot-1.0.0.jar"));
        assertTrue(error.getMessage().contains("Unable to find checksum"));

        Path file = tempDir.resolve("payload.bin");
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        Files.write(file, payload);
        assertEquals(sha256Hex(payload), invokeComputeSha256(service, file));
    }

    @Test
    void shouldCapHistoryToConfiguredMaximum() throws Exception {
        UpdateService service = createService();

        for (int i = 0; i < 105; i++) {
            invokeAddHistory(service, "check-" + i);
        }

        assertEquals(100, service.getHistory().size());
        assertEquals("check-104", service.getHistory().getFirst().getOperation());
        assertEquals("check-5", service.getHistory().getLast().getOperation());
    }

    private UpdateService createService() {
        return new UpdateService(
                botProperties,
                buildPropertiesProvider,
                new ObjectMapper(),
                applicationContext,
                clock,
                jvmExitService);
    }

    private TestableUpdateService createTestableService(StubHttpClient stubHttpClient) {
        return new TestableUpdateService(
                botProperties,
                buildPropertiesProvider,
                new ObjectMapper(),
                applicationContext,
                clock,
                jvmExitService,
                stubHttpClient);
    }

    private void enableUpdates(Path updatesPath) {
        botProperties.getUpdate().setEnabled(true);
        botProperties.getUpdate().setUpdatesPath(updatesPath.toString());
        botProperties.getUpdate().setConfirmTtl(Duration.ofMinutes(2));
    }

    private void stageJar(Path updatesPath, String assetName) throws Exception {
        Path jarsDir = updatesPath.resolve("jars");
        Files.createDirectories(jarsDir);
        Files.writeString(jarsDir.resolve(assetName), "jar", StandardCharsets.UTF_8);
        Files.writeString(updatesPath.resolve("staged.txt"), assetName, StandardCharsets.UTF_8);
    }

    private void cacheJar(Path updatesPath, String version) throws Exception {
        Path jarsDir = updatesPath.resolve("jars");
        Files.createDirectories(jarsDir);
        Files.writeString(jarsDir.resolve("bot-" + version + ".jar"), "jar", StandardCharsets.UTF_8);
    }

    private static String releaseJson(
            String tagName,
            String assetName,
            String assetUrl,
            String shaUrl,
            String publishedAt) {
        return releaseJsonWithAssetIds(
                tagName,
                assetName,
                DEFAULT_JAR_ASSET_ID,
                DEFAULT_SHA_ASSET_ID,
                assetUrl,
                shaUrl,
                publishedAt);
    }

    private static String releaseJsonWithAssetIds(
            String tagName,
            String assetName,
            long assetId,
            long shaAssetId,
            String assetUrl,
            String shaUrl,
            String publishedAt) {
        return """
                {
                  "tag_name": "%s",
                  "published_at": "%s",
                  "assets": [
                    {
                      "id": %d,
                      "name": "%s",
                      "browser_download_url": "%s"
                    },
                    {
                      "id": %d,
                      "name": "sha256sums.txt",
                      "browser_download_url": "%s"
                    }
                  ]
                }
                """.formatted(tagName, publishedAt, assetId, assetName, assetUrl, shaAssetId, shaUrl);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int invokeCompareVersions(UpdateService service, String left, String right) {
        Method method = getDeclaredMethod("compareVersions", String.class, String.class);
        return (int) invokeReflective(method, service, left, right);
    }

    private static String invokeExtractExpectedSha256(UpdateService service, String checksumsText, String assetName) {
        Method method = getDeclaredMethod("extractExpectedSha256", String.class, String.class);
        return (String) invokeReflective(method, service, checksumsText, assetName);
    }

    private static String invokeComputeSha256(UpdateService service, Path filePath) {
        Method method = getDeclaredMethod("computeSha256", Path.class);
        return (String) invokeReflective(method, service, filePath);
    }

    private static void invokeAddHistory(UpdateService service, String operation) {
        Method method = getDeclaredMethod("addHistory", String.class, String.class, String.class, String.class);
        invokeReflective(method, service, operation, null, "SUCCESS", "ok");
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static Method getDeclaredMethod(String methodName, Class<?>... parameterTypes) {
        try {
            Method method = UpdateService.class.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Method not found: " + methodName, e);
        }
    }

    private static Object invokeReflective(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to access reflective method: " + method.getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Reflective invocation failed: " + method.getName(), cause);
        }
    }

    @SuppressWarnings("PMD.TestClassWithoutTestCases")
    private static final class TestableUpdateService extends UpdateService {

        private final StubHttpClient stubHttpClient;
        private boolean restartRequested;

        private TestableUpdateService(
                BotProperties botProperties,
                ObjectProvider<BuildProperties> buildPropertiesProvider,
                ObjectMapper objectMapper,
                ApplicationContext applicationContext,
                Clock clock,
                JvmExitService jvmExitService,
                StubHttpClient stubHttpClient) {
            super(botProperties, buildPropertiesProvider, objectMapper, applicationContext, clock, jvmExitService);
            this.stubHttpClient = stubHttpClient;
        }

        @Override
        protected HttpClient buildHttpClient() {
            return stubHttpClient;
        }

        @Override
        protected void requestRestartAsync() {
            restartRequested = true;
        }

        private boolean isRestartRequested() {
            return restartRequested;
        }
    }

    private static final class StubHttpClient extends HttpClient {

        private final Deque<StubExchange> exchanges = new ArrayDeque<>();
        private final List<URI> requestedUris = new ArrayList<>();

        private void enqueueStringResponse(int statusCode, String responseBody) {
            exchanges
                    .addLast(new StubExchange(statusCode, responseBody.getBytes(StandardCharsets.UTF_8), Map.of(), null,
                            null));
        }

        private void enqueueBinaryResponse(int statusCode, byte[] responseBody) {
            exchanges.addLast(new StubExchange(statusCode, responseBody, Map.of(), null, null));
        }

        private void enqueueRedirectResponse(int statusCode, String location) {
            exchanges.addLast(new StubExchange(
                    statusCode,
                    new byte[0],
                    Map.of("Location", List.of(location)),
                    null,
                    null));
        }

        private void enqueueInterrupted(InterruptedException exception) {
            exchanges.addLast(new StubExchange(0, null, Map.of(), null, exception));
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
            if (exchange.interruptedException() != null) {
                throw exchange.interruptedException();
            }
            if (exchange.ioException() != null) {
                throw exchange.ioException();
            }

            byte[] payload = exchange.responseBody() != null ? exchange.responseBody() : new byte[0];
            HttpHeaders headers = HttpHeaders.of(exchange.responseHeaders(), (name, value) -> true);
            HttpResponse.ResponseInfo info = new StubResponseInfo(exchange.statusCode(), headers);
            HttpResponse.BodySubscriber<T> subscriber = responseBodyHandler.apply(info);
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
            subscriber.onNext(List.of(ByteBuffer.wrap(payload)));
            subscriber.onComplete();
            T decodedBody = subscriber.getBody().toCompletableFuture().join();
            return new StubHttpResponse<>(request, exchange.statusCode(), decodedBody, headers);
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
            Map<String, List<String>> responseHeaders,
            IOException ioException,
            InterruptedException interruptedException) {
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

    private static final class MutableClock extends Clock {

        private Instant currentInstant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.currentInstant = instant;
            this.zone = zone;
        }

        private void setInstant(Instant instant) {
            this.currentInstant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(currentInstant, zone);
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
