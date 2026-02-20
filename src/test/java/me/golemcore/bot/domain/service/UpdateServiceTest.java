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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpdateServiceTest {

    private static final Instant BASE_TIME = Instant.parse("2026-02-19T12:00:00Z");
    private static final String VERSION_CURRENT = "0.3.0";
    private static final String VERSION_PATCH = "0.3.1";

    private BotProperties botProperties;
    private ObjectProvider<BuildProperties> buildPropertiesProvider;
    private ApplicationContext applicationContext;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        botProperties = new BotProperties();
        buildPropertiesProvider = mock(ObjectProvider.class);
        applicationContext = mock(ApplicationContext.class);
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
                clock);

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

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.apply("WRONG1"));

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

    private UpdateService createService() {
        return new UpdateService(
                botProperties,
                buildPropertiesProvider,
                new ObjectMapper(),
                applicationContext,
                clock);
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

    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
