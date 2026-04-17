package me.golemcore.bot.application.update;

import me.golemcore.bot.adapter.outbound.update.BuildPropertiesUpdateVersionAdapter;
import me.golemcore.bot.adapter.outbound.update.RuntimeConfigUpdateRuntimeAdapter;
import me.golemcore.bot.adapter.outbound.update.UpdateAutoUpdateLifecycle;
import me.golemcore.bot.domain.model.AvailableRelease;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.UpdateActionResult;
import me.golemcore.bot.domain.model.UpdateBlockedReason;
import me.golemcore.bot.domain.model.UpdateState;
import me.golemcore.bot.domain.model.UpdateStatus;
import me.golemcore.bot.domain.service.JvmExitService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UpdateActivityGate;
import me.golemcore.bot.domain.service.UpdateMaintenanceWindow;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.ReleaseSourcePort;
import me.golemcore.bot.port.outbound.UpdateArtifactStorePort;
import me.golemcore.bot.port.outbound.UpdateRestartPort;
import me.golemcore.bot.port.outbound.UpdateRuntimeConfigPort;
import me.golemcore.bot.port.outbound.UpdateSettingsPort;
import me.golemcore.bot.port.outbound.UpdateVersionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.CloseResource")
class UpdateServiceTest {

    private static final Instant BASE_TIME = Instant.parse("2026-02-22T12:00:00Z");
    private static final String VERSION_CURRENT = "0.4.0";
    private static final String VERSION_PATCH = "0.4.2";

    private BotProperties botProperties;
    private ObjectProvider<BuildProperties> buildPropertiesProvider;
    private JvmExitService jvmExitService;
    private MutableClock clock;
    private RuntimeConfigService runtimeConfigService;
    private UpdateActivityGate updateActivityGate;
    private UpdateMaintenanceWindow updateMaintenanceWindow;
    private UpdateArtifactStorePort updateArtifactStorePort;
    private AtomicReference<Optional<UpdateArtifactStorePort.StoredArtifact>> currentArtifactRef;
    private AtomicReference<Optional<UpdateArtifactStorePort.StoredArtifact>> stagedArtifactRef;

    @BeforeEach
    void setUp() {
        botProperties = new BotProperties();
        buildPropertiesProvider = mock(ObjectProvider.class);
        jvmExitService = mock(JvmExitService.class);
        clock = new MutableClock(BASE_TIME, ZoneOffset.UTC);
        runtimeConfigService = mock(RuntimeConfigService.class);
        updateActivityGate = mock(UpdateActivityGate.class);
        updateMaintenanceWindow = new UpdateMaintenanceWindow();
        updateArtifactStorePort = mock(UpdateArtifactStorePort.class);
        currentArtifactRef = new AtomicReference<>(Optional.empty());
        stagedArtifactRef = new AtomicReference<>(Optional.empty());
        when(updateArtifactStorePort.findCurrentArtifact()).thenAnswer(invocation -> currentArtifactRef.get());
        when(updateArtifactStorePort.findStagedArtifact()).thenAnswer(invocation -> stagedArtifactRef.get());
        when(updateArtifactStorePort.stageReleaseAsset(any(UpdateArtifactStorePort.StageArtifactRequest.class)))
                .thenAnswer(invocation -> {
                    UpdateArtifactStorePort.StageArtifactRequest request = invocation.getArgument(0);
                    UpdateArtifactStorePort.PreparedArtifact preparedArtifact = new UpdateArtifactStorePort.PreparedArtifact(
                            request.assetName(), BASE_TIME);
                    stagedArtifactRef.set(Optional.of(new UpdateArtifactStorePort.StoredArtifact(
                            request.assetName(), BASE_TIME)));
                    return preparedArtifact;
                });
        doAnswer(invocation -> {
            String assetName = invocation.getArgument(0);
            currentArtifactRef.set(Optional.of(new UpdateArtifactStorePort.StoredArtifact(assetName, BASE_TIME)));
            stagedArtifactRef.set(Optional.empty());
            return null;
        }).when(updateArtifactStorePort).activateStagedArtifact(any(String.class));
        doAnswer(invocation -> null).when(updateArtifactStorePort)
                .cleanupTempArtifact(any(String.class));

        Properties props = new Properties();
        props.setProperty("version", VERSION_CURRENT);
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(new BuildProperties(props));
        when(runtimeConfigService.isAutoUpdateEnabled()).thenReturn(true);
        when(runtimeConfigService.getUpdateCheckIntervalMinutes()).thenReturn(60);
        when(runtimeConfigService.isUpdateMaintenanceWindowEnabled()).thenReturn(false);
        when(runtimeConfigService.getUpdateMaintenanceWindowStartUtc()).thenReturn("00:00");
        when(runtimeConfigService.getUpdateMaintenanceWindowEndUtc()).thenReturn("00:00");
        when(updateActivityGate.getStatus()).thenReturn(UpdateActivityGate.Result.idle());
    }

    @Test
    void shouldReturnDisabledStateWhenUpdateFeatureIsOff() {
        botProperties.getUpdate().setEnabled(false);

        UpdateService service = createService(new StubReleaseSource());
        UpdateStatus status = service.getStatus();

        assertFalse(status.isEnabled());
        assertEquals(UpdateState.DISABLED, status.getState());
        assertNotNull(status.getCurrent());
        assertEquals(VERSION_CURRENT, status.getCurrent().getVersion());
        assertEquals("Updates disabled", status.getStageTitle());
    }

    @Test
    void shouldUseDevVersionWhenBuildPropertiesAreUnavailable() {
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(null);

        UpdateService service = createService(new StubReleaseSource());
        UpdateStatus status = service.getStatus();

        assertEquals("dev", status.getCurrent().getVersion());
        assertEquals("image", status.getCurrent().getSource());
    }

    @Test
    void shouldRejectCheckWhenUpdateFeatureIsDisabled() {
        botProperties.getUpdate().setEnabled(false);
        UpdateService service = createService(new StubReleaseSource());

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::check);

        assertEquals("Update feature is disabled", exception.getMessage());
    }

    @Test
    void shouldRejectUpdateNowWhenUpdateFeatureIsDisabled() {
        botProperties.getUpdate().setEnabled(false);
        UpdateService service = createService(new StubReleaseSource());

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::updateNow);

        assertEquals("Update feature is disabled", exception.getMessage());
    }

    @Test
    void shouldRejectUpdateNowWhenNoAvailableUpdateExists(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubReleaseSource source = new StubReleaseSource();
        source.enqueueEmpty();
        TestableUpdateService service = createTestableService(source);

        assertEquals("Update workflow started. Checking the latest release.", service.updateNow().getMessage());
        assertEquals(UpdateState.IDLE, service.getStatus().getState());
        assertNull(service.getStatus().getAvailable());
    }

    @Test
    void shouldReturnNoUpdatesWhenReleaseApiReturnsNotFound(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubReleaseSource source = new StubReleaseSource();
        source.enqueueEmpty();
        TestableUpdateService service = createTestableService(source);

        assertEquals("No updates found", service.check().getMessage());
        assertEquals(UpdateState.IDLE, service.getStatus().getState());
        assertNull(service.getStatus().getAvailable());
    }

    @Test
    void shouldReportAlreadyUpToDateWhenRemoteVersionIsNotNewer(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.0", "bot-0.4.0.jar", "2026-02-22T10:00:00Z");
        TestableUpdateService service = createTestableService(source);

        assertEquals("Already up to date", service.check().getMessage());
        assertNull(service.getStatus().getAvailable());
    }

    @Test
    void shouldTreatExecClassifierAsTheSameVersion(@TempDir Path tempDir) {
        enableUpdates(tempDir);

        Properties props = new Properties();
        props.setProperty("version", VERSION_PATCH + "-exec");
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(new BuildProperties(props));

        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.2", "bot-0.4.2-exec.jar", "2026-02-22T10:00:00Z");
        TestableUpdateService service = createTestableService(source);

        assertEquals("Already up to date", service.check().getMessage());
        assertNull(service.getStatus().getAvailable());
        assertEquals(VERSION_PATCH, service.getStatus().getCurrent().getVersion());
    }

    @Test
    void shouldDiscoverMajorUpdateWhenRemoteVersionIsNewer(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("1.0.0", "bot-1.0.0.jar", "2026-02-22T10:00:00Z");
        TestableUpdateService service = createTestableService(source);

        assertEquals("Update available: 1.0.0", service.check().getMessage());
        assertEquals(UpdateState.AVAILABLE, service.getStatus().getState());
        assertEquals("1.0.0", service.getStatus().getAvailable().getVersion());
    }

    @Test
    void shouldDiscoverMinorUpdateWhenMajorVersionIsUnchanged(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.5.0", "bot-0.5.0.jar", "2026-02-22T10:00:00Z");
        TestableUpdateService service = createTestableService(source);

        assertEquals("Update available: 0.5.0", service.check().getMessage());
        assertEquals(UpdateState.AVAILABLE, service.getStatus().getState());
        assertEquals("0.5.0", service.getStatus().getAvailable().getVersion());
    }

    @Test
    void shouldDiscoverPatchUpdate(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.2", "bot-0.4.2.jar", "2026-02-22T10:00:00Z");
        TestableUpdateService service = createTestableService(source);

        assertEquals("Update available: 0.4.2", service.check().getMessage());
        assertEquals(UpdateState.AVAILABLE, service.getStatus().getState());
        assertEquals(VERSION_PATCH, service.getStatus().getAvailable().getVersion());
    }

    @Test
    void shouldExposeTargetProgressAndStageMetadataWhenUpdateIsAvailable(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.2", "bot-0.4.2.jar", "2026-02-22T10:00:00Z");
        TestableUpdateService service = createTestableService(source);

        service.check();
        UpdateStatus status = service.getStatus();

        assertNotNull(status.getTarget());
        assertEquals(VERSION_PATCH, status.getTarget().getVersion());
        assertEquals(Integer.valueOf(25), status.getProgressPercent());
        assertEquals("Update available 0.4.2", status.getStageTitle());
        assertTrue(status.getStageDescription().contains("compatible release"));
    }

    @Test
    void shouldPrepareAndApplyUpdateNow(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        byte[] jarBytes = "new-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256Hex(jarBytes);

        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.2", "bot-0.4.2.jar", "2026-02-22T10:00:00Z");
        source.enqueueAsset(jarBytes);
        source.enqueueChecksum(checksum, "SHA-256", "bot-0.4.2.jar");
        TestableUpdateService service = createTestableService(source);

        assertEquals("Update available: 0.4.2", service.check().getMessage());
        assertEquals("Update workflow started for 0.4.2. Page will reload after restart.",
                service.updateNow().getMessage());

        assertTrue(service.isRestartRequested());
        assertEquals(UpdateState.APPLYING, service.getStatus().getState());
        assertEquals("Scheduling restart 0.4.2", service.getStatus().getStageTitle());
        assertTrue(stagedArtifactRef.get().isEmpty());
        assertTrue(currentArtifactRef.get().isPresent());
        assertEquals("bot-0.4.2.jar", currentArtifactRef.get().orElseThrow().assetName());
    }

    @Test
    void shouldCheckAndPrepareWhenUpdateNowCalledWithoutPriorCheck(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        byte[] jarBytes = "new-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256Hex(jarBytes);

        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.2", "bot-0.4.2.jar", "2026-02-22T10:00:00Z");
        source.enqueueAsset(jarBytes);
        source.enqueueChecksum(checksum, "SHA-256", "bot-0.4.2.jar");
        TestableUpdateService service = createTestableService(source);

        assertEquals("Update workflow started. Checking the latest release.", service.updateNow().getMessage());
        assertTrue(service.isRestartRequested());
        assertTrue(currentArtifactRef.get().isPresent());
    }

    @Test
    void shouldRecoverWhenReleaseExistsButStagedJarIsMissing(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        byte[] jarBytes = "new-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256Hex(jarBytes);

        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.2", "bot-0.4.2.jar", "2026-02-22T10:00:00Z");
        source.enqueueAsset(jarBytes);
        source.enqueueChecksum(checksum, "SHA-256", "bot-0.4.2.jar");
        TestableUpdateService service = createTestableService(source);

        assertEquals("Update workflow started. Checking the latest release.", service.updateNow().getMessage());
        assertTrue(service.isRestartRequested());
        assertTrue(currentArtifactRef.get().isPresent());
    }

    @Test
    void shouldCreateUpdatesDirectoryWhenItDoesNotExist(@TempDir Path tempDir) {
        Path missingUpdatesDir = tempDir.resolve("missing").resolve("updates");
        assertFalse(Files.exists(missingUpdatesDir));
        enableUpdates(missingUpdatesDir);
        byte[] jarBytes = "new-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256Hex(jarBytes);

        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.2", "bot-0.4.2.jar", "2026-02-22T10:00:00Z");
        source.enqueueAsset(jarBytes);
        source.enqueueChecksum(checksum, "SHA-256", "bot-0.4.2.jar");
        TestableUpdateService service = createTestableService(source);

        service.check();
        service.updateNow();

        assertTrue(currentArtifactRef.get().isPresent());
    }

    @Test
    void shouldFailUpdateNowWhenChecksumDoesNotMatchAndCleanTempJar(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.2", "bot-0.4.2.jar", "2026-02-22T10:00:00Z");
        source.enqueueAsset("jar-content".getBytes(StandardCharsets.UTF_8));
        source.enqueueChecksum("deadbeef", "SHA-256", "bot-0.4.2.jar");
        when(updateArtifactStorePort.findStagedArtifact()).thenReturn(Optional.empty());
        when(updateArtifactStorePort.stageReleaseAsset(any(UpdateArtifactStorePort.StageArtifactRequest.class)))
                .thenThrow(new IllegalStateException("Checksum mismatch for bot-0.4.2.jar"));
        TestableUpdateService service = createTestableService(source);
        service.check();

        assertEquals("Update workflow started for 0.4.2. Page will reload after restart.",
                service.updateNow().getMessage());
        assertEquals(UpdateState.FAILED, service.getStatus().getState());
        assertTrue(service.getStatus().getLastError().contains("Failed to prepare update"));
        assertTrue(service.getStatus().getLastError().contains("Checksum mismatch"));
        assertEquals("Update failed", service.getStatus().getStageTitle());
        verify(updateArtifactStorePort).cleanupTempArtifact("bot-0.4.2.jar");
    }

    @Test
    void shouldPreserveTargetAndProgressWhenPrepareFails(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.2", "bot-0.4.2.jar", "2026-02-22T10:00:00Z");
        source.enqueueAsset("jar-content".getBytes(StandardCharsets.UTF_8));
        source.enqueueChecksum("deadbeef", "SHA-256", "bot-0.4.2.jar");
        when(updateArtifactStorePort.findStagedArtifact()).thenReturn(Optional.empty());
        when(updateArtifactStorePort.stageReleaseAsset(any(UpdateArtifactStorePort.StageArtifactRequest.class)))
                .thenThrow(new IllegalStateException("Checksum mismatch for bot-0.4.2.jar"));
        TestableUpdateService service = createTestableService(source);

        service.check();
        service.updateNow();
        UpdateStatus status = service.getStatus();

        assertEquals(UpdateState.FAILED, status.getState());
        assertNotNull(status.getTarget());
        assertEquals(VERSION_PATCH, status.getTarget().getVersion());
        assertEquals(Integer.valueOf(52), status.getProgressPercent());
        assertEquals("Update failed", status.getStageTitle());
        assertTrue(status.getStageDescription().contains("Checksum mismatch"));
    }

    @Test
    void shouldFailUpdateNowWithHelpfulMessageWhenUpdatesPathIsNotWritable(@TempDir Path tempDir) throws Exception {
        Path blockedPath = tempDir.resolve("updates-file");
        Files.writeString(blockedPath, "blocked", StandardCharsets.UTF_8);
        enableUpdates(blockedPath);

        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.2", "bot-0.4.2.jar", "2026-02-22T10:00:00Z");
        source.enqueueAsset("jar-content".getBytes(StandardCharsets.UTF_8));
        source.enqueueChecksum("ignored", "SHA-256", "bot-0.4.2.jar");
        when(updateArtifactStorePort.findStagedArtifact()).thenReturn(Optional.empty());
        when(updateArtifactStorePort.stageReleaseAsset(any(UpdateArtifactStorePort.StageArtifactRequest.class)))
                .thenThrow(new IllegalStateException(
                        "Update directory is not writable: " + blockedPath
                                + ". Configure UPDATE_PATH to a writable path."));
        TestableUpdateService service = createTestableService(source);
        service.check();

        assertEquals("Update workflow started for 0.4.2. Page will reload after restart.",
                service.updateNow().getMessage());
        assertTrue(service.getStatus().getLastError().contains("Update directory is not writable"));
        assertTrue(service.getStatus().getLastError().contains("Configure UPDATE_PATH"));
        assertEquals(UpdateState.FAILED, service.getStatus().getState());
    }

    @Test
    void shouldHandleInterruptedCheckAndPreserveInterruptFlag(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        StubReleaseSource source = new StubReleaseSource();
        source.enqueueInterrupted(new InterruptedException("simulated interruption"));
        TestableUpdateService service = createTestableService(source);

        IllegalStateException error = assertThrows(IllegalStateException.class, service::check);

        assertTrue(error.getMessage().contains("simulated interruption"));
        assertTrue(Thread.currentThread().isInterrupted());
        assertTrue(Thread.interrupted());
    }

    @Test
    void shouldRejectConcurrentOperationsWhileWorkflowIsRunning(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        DeferredUpdateService service = createDeferredUpdateService(new StubReleaseSource());

        assertEquals("Update workflow started. Checking the latest release.", service.updateNow().getMessage());
        assertEquals(UpdateState.CHECKING, service.getStatus().getState());

        IllegalStateException checkError = assertThrows(IllegalStateException.class, service::check);
        IllegalStateException updateError = assertThrows(IllegalStateException.class, service::updateNow);

        assertEquals("Another update operation is already in progress", checkError.getMessage());
        assertEquals("Another update operation is already in progress", updateError.getMessage());
    }

    @Test
    void shouldRejectUpdateNowWhenRuntimeWorkIsActive(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        when(updateActivityGate.getStatus()).thenReturn(
                UpdateActivityGate.Result.busy(UpdateBlockedReason.SESSION_WORK_RUNNING));

        UpdateService service = createService(new StubReleaseSource());

        IllegalStateException error = assertThrows(IllegalStateException.class, service::updateNow);

        assertEquals("Update is blocked while runtime work is still running", error.getMessage());
    }

    @Test
    void shouldForceApplyStagedUpdateWhenRuntimeWorkIsActive(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        when(updateActivityGate.getStatus()).thenReturn(
                UpdateActivityGate.Result.busy(UpdateBlockedReason.SESSION_WORK_RUNNING));
        when(updateArtifactStorePort.findStagedArtifact())
                .thenReturn(Optional.of(new UpdateArtifactStorePort.StoredArtifact("bot-0.4.2.jar", BASE_TIME)));

        TestableUpdateService service = createTestableService(new StubReleaseSource());

        UpdateActionResult result = service.forceInstallStagedUpdate();

        assertEquals("Update 0.4.2 is being applied. JVM restart scheduled.", result.getMessage());
        assertEquals("0.4.2", result.getVersion());
        assertTrue(service.isRestartRequested());
        assertEquals(UpdateState.APPLYING, service.getStatus().getState());
    }

    @Test
    void shouldRejectForceInstallWhenNoStagedUpdateExists(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        when(updateActivityGate.getStatus()).thenReturn(
                UpdateActivityGate.Result.busy(UpdateBlockedReason.SESSION_WORK_RUNNING));

        UpdateService service = createService(new StubReleaseSource());

        IllegalStateException error = assertThrows(IllegalStateException.class, service::forceInstallStagedUpdate);

        assertEquals("No staged update to force install", error.getMessage());
    }

    @Test
    void shouldNotInitializeAutoUpdateSchedulerWhenFeatureIsDisabled() {
        botProperties.getUpdate().setEnabled(false);
        UpdateService service = createService(new StubReleaseSource());
        UpdateAutoUpdateLifecycle lifecycle = new UpdateAutoUpdateLifecycle(service);

        invokeNoArgMethod(lifecycle, "start");

        assertNull(readPrivateField(lifecycle, "autoUpdateScheduler", ScheduledExecutorService.class));
        assertNull(readPrivateField(lifecycle, "autoUpdateTask", ScheduledFuture.class));
    }

    @Test
    void shouldInitializeAndShutdownAutoUpdateScheduler(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        UpdateService service = createService(new StubReleaseSource());
        UpdateAutoUpdateLifecycle lifecycle = new UpdateAutoUpdateLifecycle(service);

        invokeNoArgMethod(lifecycle, "start");
        ScheduledExecutorService scheduler = readPrivateField(lifecycle, "autoUpdateScheduler",
                ScheduledExecutorService.class);
        ScheduledFuture<?> task = readPrivateField(lifecycle, "autoUpdateTask", ScheduledFuture.class);

        assertNotNull(scheduler);
        assertNotNull(task);
        assertFalse(scheduler.isShutdown());

        invokeNoArgMethod(lifecycle, "stop");

        assertTrue(task.isCancelled());
        assertTrue(scheduler.isShutdown());
    }

    @Test
    void shouldReturnDefaultUpdateConfigWhenApiConfigIsMissing() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder().build();
        runtimeConfig.setUpdate(null);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        UpdateService service = createService(new StubReleaseSource());
        RuntimeConfig.UpdateConfig config = service.getConfig();

        assertTrue(config.getAutoEnabled());
        assertEquals(60, config.getCheckIntervalMinutes());
        assertFalse(config.getMaintenanceWindowEnabled());
        assertEquals("00:00", config.getMaintenanceWindowStartUtc());
        assertEquals("00:00", config.getMaintenanceWindowEndUtc());
    }

    @Test
    void shouldPersistUpdatedConfigAndReturnSavedValue() {
        AtomicReference<RuntimeConfig> storedConfig = new AtomicReference<>(RuntimeConfig.builder().build());
        when(runtimeConfigService.getRuntimeConfig()).thenAnswer(invocation -> storedConfig.get());
        when(runtimeConfigService.getRuntimeConfigForApi()).thenAnswer(invocation -> storedConfig.get());
        when(runtimeConfigService.copyRuntimeConfig(any(RuntimeConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            storedConfig.set(invocation.getArgument(0));
            return null;
        }).when(runtimeConfigService).updateRuntimeConfig(any(RuntimeConfig.class));

        UpdateService service = createService(new StubReleaseSource());
        RuntimeConfig.UpdateConfig request = RuntimeConfig.UpdateConfig.builder()
                .autoEnabled(false)
                .checkIntervalMinutes(15)
                .maintenanceWindowEnabled(true)
                .maintenanceWindowStartUtc("01:30")
                .maintenanceWindowEndUtc("03:00")
                .build();

        RuntimeConfig.UpdateConfig saved = service.updateConfig(request);

        assertFalse(saved.getAutoEnabled());
        assertEquals(15, saved.getCheckIntervalMinutes());
        assertTrue(saved.getMaintenanceWindowEnabled());
        assertEquals("01:30", saved.getMaintenanceWindowStartUtc());
        assertEquals("03:00", saved.getMaintenanceWindowEndUtc());
    }

    @Test
    void shouldPersistDefaultUpdateConfigWhenNullConfigIsProvided() {
        AtomicReference<RuntimeConfig> storedConfig = new AtomicReference<>(RuntimeConfig.builder().build());
        when(runtimeConfigService.getRuntimeConfig()).thenAnswer(invocation -> storedConfig.get());
        when(runtimeConfigService.getRuntimeConfigForApi()).thenAnswer(invocation -> storedConfig.get());
        when(runtimeConfigService.copyRuntimeConfig(any(RuntimeConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            storedConfig.set(invocation.getArgument(0));
            return null;
        }).when(runtimeConfigService).updateRuntimeConfig(any(RuntimeConfig.class));

        UpdateService service = createService(new StubReleaseSource());

        RuntimeConfig.UpdateConfig saved = service.updateConfig(null);

        assertTrue(saved.getAutoEnabled());
        assertEquals(60, saved.getCheckIntervalMinutes());
        assertFalse(saved.getMaintenanceWindowEnabled());
        assertEquals("00:00", saved.getMaintenanceWindowStartUtc());
        assertEquals("00:00", saved.getMaintenanceWindowEndUtc());
    }

    @Test
    void shouldAutoPrepareAndWaitForMaintenanceWindowWhenClosed(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        when(runtimeConfigService.isUpdateMaintenanceWindowEnabled()).thenReturn(true);
        when(runtimeConfigService.getUpdateMaintenanceWindowStartUtc()).thenReturn("01:00");
        when(runtimeConfigService.getUpdateMaintenanceWindowEndUtc()).thenReturn("02:00");

        byte[] jarBytes = "new-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256Hex(jarBytes);

        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.2", "bot-0.4.2.jar", "2026-02-22T10:00:00Z");
        source.enqueueAsset(jarBytes);
        source.enqueueChecksum(checksum, "SHA-256", "bot-0.4.2.jar");
        TestableUpdateService service = createTestableService(source);

        service.runAutoUpdateCycle();

        assertEquals(UpdateState.WAITING_FOR_WINDOW, service.getStatus().getState());
        assertFalse(service.isRestartRequested());
    }

    @Test
    void shouldAutoPrepareAndWaitForIdleWhenRuntimeWorkIsActive(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        when(updateActivityGate.getStatus()).thenReturn(
                UpdateActivityGate.Result.busy(UpdateBlockedReason.SESSION_WORK_RUNNING));

        byte[] jarBytes = "new-binary".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256Hex(jarBytes);

        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.2", "bot-0.4.2.jar", "2026-02-22T10:00:00Z");
        source.enqueueAsset(jarBytes);
        source.enqueueChecksum(checksum, "SHA-256", "bot-0.4.2.jar");
        TestableUpdateService service = createTestableService(source);

        service.runAutoUpdateCycle();

        assertEquals(UpdateState.WAITING_FOR_IDLE, service.getStatus().getState());
        assertFalse(service.isRestartRequested());
    }

    @Test
    void shouldAutoApplyStagedUpdateWhenWindowIsOpenAndRuntimeIsIdle(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        when(updateArtifactStorePort.findStagedArtifact())
                .thenReturn(Optional.of(new UpdateArtifactStorePort.StoredArtifact("bot-0.4.2.jar", BASE_TIME)));

        TestableUpdateService service = createTestableService(new StubReleaseSource());

        service.runAutoUpdateCycle();

        assertTrue(service.isRestartRequested());
        assertEquals(UpdateState.APPLYING, service.getStatus().getState());
    }

    @Test
    void shouldFallbackToImageSourceWhenCurrentMarkerIsInvalid(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        when(updateArtifactStorePort.findCurrentArtifact())
                .thenReturn(Optional.of(new UpdateArtifactStorePort.StoredArtifact("../bot-0.4.2.jar", BASE_TIME)));

        UpdateService service = createService(new StubReleaseSource());
        UpdateStatus status = service.getStatus();

        assertEquals("image", status.getCurrent().getSource());
        assertEquals(VERSION_CURRENT, status.getCurrent().getVersion());
    }

    @Test
    void shouldIgnoreStagedMarkerWhenJarIsMissing(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        when(updateArtifactStorePort.findStagedArtifact()).thenReturn(Optional.empty());

        UpdateService service = createService(new StubReleaseSource());
        UpdateStatus status = service.getStatus();

        assertNull(status.getStaged());
        assertEquals(UpdateState.IDLE, status.getState());
    }

    @Test
    void shouldRejectDangerousAssetNameDuringUpdateNow(@TempDir Path tempDir) {
        enableUpdates(tempDir);

        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.2", "../bot-0.4.2.jar", "2026-02-22T10:00:00Z");
        when(updateArtifactStorePort.stageReleaseAsset(any(UpdateArtifactStorePort.StageArtifactRequest.class)))
                .thenThrow(new IllegalArgumentException("Asset name contains prohibited path characters"));
        TestableUpdateService service = createTestableService(source);

        UpdateActionResult action = service.updateNow();
        UpdateStatus status = service.getStatus();

        assertEquals("Update workflow started. Checking the latest release.", action.getMessage());
        assertEquals(UpdateState.FAILED, status.getState());
        assertTrue(status.getLastError().contains("Failed to prepare update"));
        assertFalse(service.isRestartRequested());
    }

    @Test
    void shouldSkipAutoCheckWhenIntervalHasNotElapsed(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        when(runtimeConfigService.getUpdateCheckIntervalMinutes()).thenReturn(120);

        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.2", "bot-0.4.2.jar", "2026-02-22T10:00:00Z");
        TestableUpdateService service = createTestableService(source);
        writePrivateField(service, "lastCheckAt", BASE_TIME.minus(Duration.ofMinutes(30)));

        service.runAutoUpdateCycle();

        assertEquals(UpdateState.IDLE, service.getStatus().getState());
        assertNull(service.getStatus().getAvailable());
    }

    @Test
    void shouldCoverVersionComparisonAndChecksumHelpersViaReflection(@TempDir Path tempDir) throws Exception {
        enableUpdates(tempDir);
        UpdateService service = createService(new StubReleaseSource());

        assertTrue(invokeCompareVersions(service, "1.2.4", "1.2.3") > 0);
        assertTrue(invokeCompareVersions(service, "1.2.3-rc.1", "1.2.3-rc.2") < 0);
        assertTrue(invokeCompareVersions(service, "1.2.3", "1.2.3-rc.1") > 0);
        assertTrue(invokeCompareVersions(service, "1.2.3-alpha", "1.2.3-1") > 0);
        assertTrue(invokeCompareVersions(service, "1.2.3-1", "1.2.3-alpha") < 0);
        assertTrue(invokeCompareVersions(service, "release-2", "release-10") > 0);
        assertEquals(0, invokeCompareVersions(service, "v1.2.3+build.1", "1.2.3"));
        assertEquals("1.2.3", service.extractVersionFromAssetName("bot-1.2.3.jar"));
        assertEquals("1.2.3-rc.1", service.extractVersionFromAssetName("release-v1.2.3-rc.1.jar"));
        assertEquals("1.2.3", service.extractVersionFromAssetName("bot-1.2.3-exec.jar"));
        assertEquals("2.3.4-beta-2", service.extractVersionFromAssetName("release-V2.3.4-beta-2.jar"));
        assertNull(service.extractVersionFromAssetName("bot-latest.jar"));
        assertNull(service.extractVersionFromAssetName("bot-1.2.jar"));
    }

    @Test
    void shouldFallbackToGitHubWhenMavenCentralFails(@TempDir Path tempDir) {
        enableUpdates(tempDir);

        StubReleaseSource mavenCentral = new StubReleaseSource("maven-central");
        mavenCentral.enqueueIOException(new IOException("Maven Central unavailable"));

        StubReleaseSource github = new StubReleaseSource("github");
        github.enqueueRelease("0.4.2", "bot-0.4.2.jar", "2026-02-22T10:00:00Z");

        TestableUpdateService service = createTestableServiceMultiSource(List.of(mavenCentral, github));

        assertEquals("Update available: 0.4.2", service.check().getMessage());
        assertEquals(UpdateState.AVAILABLE, service.getStatus().getState());
    }

    @Test
    void shouldUseMavenCentralWhenAvailable(@TempDir Path tempDir) {
        enableUpdates(tempDir);

        StubReleaseSource mavenCentral = new StubReleaseSource("maven-central");
        mavenCentral.enqueueRelease("0.4.2", "bot-0.4.2.jar", "2026-02-22T10:00:00Z");

        StubReleaseSource github = new StubReleaseSource("github");
        github.enqueueRelease("0.4.1", "bot-0.4.1.jar", "2026-02-21T10:00:00Z");

        TestableUpdateService service = createTestableServiceMultiSource(List.of(mavenCentral, github));

        assertEquals("Update available: 0.4.2", service.check().getMessage());
        assertEquals("0.4.2", service.getStatus().getAvailable().getVersion());
    }

    @Test
    void shouldFailWhenAllSourcesFail(@TempDir Path tempDir) {
        enableUpdates(tempDir);

        StubReleaseSource mavenCentral = new StubReleaseSource("maven-central");
        mavenCentral.enqueueIOException(new IOException("Maven Central unavailable"));

        StubReleaseSource github = new StubReleaseSource("github");
        github.enqueueIOException(new IOException("GitHub rate limited"));

        TestableUpdateService service = createTestableServiceMultiSource(List.of(mavenCentral, github));

        IllegalStateException error = assertThrows(IllegalStateException.class, service::check);
        assertTrue(error.getMessage().contains("All release sources failed"));
    }

    @Test
    void shouldVerifyWithSha1WhenSourceProvidesSha1(@TempDir Path tempDir) {
        enableUpdates(tempDir);
        byte[] jarBytes = "new-binary".getBytes(StandardCharsets.UTF_8);
        String sha1Checksum = sha1Hex(jarBytes);

        StubReleaseSource source = new StubReleaseSource();
        source.enqueueRelease("0.4.2", "bot-0.4.2.jar", "2026-02-22T10:00:00Z");
        source.enqueueAsset(jarBytes);
        source.enqueueChecksum(sha1Checksum, "SHA-1", "bot-0.4.2.jar");
        TestableUpdateService service = createTestableService(source);

        service.check();
        service.updateNow();

        assertTrue(service.isRestartRequested());
        assertTrue(currentArtifactRef.get().isPresent());
    }

    private UpdateService createService(StubReleaseSource source) {
        return new UpdateService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                createVersionPort(),
                createRuntimeConfigPort(),
                createRestartPort(),
                clock,
                updateActivityGate,
                updateMaintenanceWindow,
                List.of(source),
                updateArtifactStorePort);
    }

    private TestableUpdateService createTestableService(StubReleaseSource source) {
        return new TestableUpdateService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                createVersionPort(),
                createRuntimeConfigPort(),
                createRestartPort(),
                clock,
                updateActivityGate,
                updateMaintenanceWindow,
                List.of(source),
                updateArtifactStorePort);
    }

    private TestableUpdateService createTestableServiceMultiSource(List<ReleaseSourcePort> sources) {
        return new TestableUpdateService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                createVersionPort(),
                createRuntimeConfigPort(),
                createRestartPort(),
                clock,
                updateActivityGate,
                updateMaintenanceWindow,
                sources,
                updateArtifactStorePort);
    }

    private DeferredUpdateService createDeferredUpdateService(StubReleaseSource source) {
        return new DeferredUpdateService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                createVersionPort(),
                createRuntimeConfigPort(),
                createRestartPort(),
                clock,
                updateActivityGate,
                updateMaintenanceWindow,
                List.of(source),
                updateArtifactStorePort);
    }

    private UpdateVersionPort createVersionPort() {
        return new BuildPropertiesUpdateVersionAdapter(buildPropertiesProvider);
    }

    private UpdateRuntimeConfigPort createRuntimeConfigPort() {
        return new RuntimeConfigUpdateRuntimeAdapter(runtimeConfigService);
    }

    private UpdateRestartPort createRestartPort() {
        return jvmExitService::exit;
    }

    private void enableUpdates(Path updatesPath) {
        botProperties.getUpdate().setEnabled(true);
        botProperties.getUpdate().setUpdatesPath(updatesPath.toString());
    }

    private static String sha256Hex(byte[] bytes) {
        return digestHex(bytes, "SHA-256");
    }

    private static String sha1Hex(byte[] bytes) {
        return digestHex(bytes, "SHA-1");
    }

    private static String digestHex(byte[] bytes, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int invokeCompareVersions(UpdateService service, String left, String right) {
        Method method = getDeclaredMethod("compareVersions", String.class, String.class);
        return (int) invokeReflective(method, new UpdateVersionSupport(), left, right);
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static Method getDeclaredMethod(String methodName, Class<?>... parameterTypes) {
        Class<?> owner = "compareVersions".equals(methodName)
                ? UpdateVersionSupport.class
                : UpdateService.class;
        Method method = findDeclaredMethod(owner, methodName, parameterTypes);
        if (method == null) {
            throw new IllegalStateException("Method not found: " + methodName);
        }
        method.setAccessible(true);
        return method;
    }

    private static Method findDeclaredMethod(Class<?> owner, String methodName, Class<?>... parameterTypes) {
        Class<?> current = owner;
        while (current != null) {
            try {
                // compareVersions now lives in shared RuntimeVersionSupport, so the
                // compatibility wrapper test must walk inherited declarations too.
                return current.getDeclaredMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Object invokeReflective(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Failed to access reflective method: " + method.getName(), exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Reflective invocation failed: " + method.getName(), cause);
        }
    }

    @SuppressWarnings({ "PMD.AvoidAccessibilityAlteration", "unchecked" })
    private static <T> T readPrivateField(Object target, String fieldName, Class<T> fieldType) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (NoSuchFieldException | IllegalAccessException exception) {
            throw new IllegalStateException("Failed to read field: " + fieldName, exception);
        }
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static void writePrivateField(Object target, String fieldName, Object value) {
        try {
            Field field = UpdateService.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException exception) {
            throw new IllegalStateException("Failed to write field: " + fieldName, exception);
        }
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static void invokeNoArgMethod(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to invoke method: " + methodName, exception);
        }
    }

    @SuppressWarnings("PMD.TestClassWithoutTestCases")
    private static final class TestableUpdateService extends UpdateService {

        private boolean restartRequested;

        private TestableUpdateService(
                UpdateSettingsPort settingsPort,
                UpdateVersionPort updateVersionPort,
                UpdateRuntimeConfigPort updateRuntimeConfigPort,
                UpdateRestartPort updateRestartPort,
                Clock clock,
                UpdateActivityGate updateActivityGate,
                UpdateMaintenanceWindow updateMaintenanceWindow,
                List<ReleaseSourcePort> releaseSources,
                UpdateArtifactStorePort updateArtifactStorePort) {
            super(
                    settingsPort,
                    updateVersionPort,
                    updateRuntimeConfigPort,
                    updateRestartPort,
                    clock,
                    updateActivityGate,
                    updateMaintenanceWindow,
                    releaseSources,
                    updateArtifactStorePort);
        }

        @Override
        protected void requestRestartAsync() {
            restartRequested = true;
        }

        @Override
        protected void startUpdateTask(Runnable task) {
            task.run();
        }

        private boolean isRestartRequested() {
            return restartRequested;
        }
    }

    @SuppressWarnings("PMD.TestClassWithoutTestCases")
    private static final class DeferredUpdateService extends UpdateService {

        private DeferredUpdateService(
                UpdateSettingsPort settingsPort,
                UpdateVersionPort updateVersionPort,
                UpdateRuntimeConfigPort updateRuntimeConfigPort,
                UpdateRestartPort updateRestartPort,
                Clock clock,
                UpdateActivityGate updateActivityGate,
                UpdateMaintenanceWindow updateMaintenanceWindow,
                List<ReleaseSourcePort> releaseSources,
                UpdateArtifactStorePort updateArtifactStorePort) {
            super(
                    settingsPort,
                    updateVersionPort,
                    updateRuntimeConfigPort,
                    updateRestartPort,
                    clock,
                    updateActivityGate,
                    updateMaintenanceWindow,
                    releaseSources,
                    updateArtifactStorePort);
        }

        @Override
        protected void startUpdateTask(Runnable task) {
            // Keep the workflow in a busy transient state so tests can verify
            // duplicate-request guards.
        }

        @Override
        protected void requestRestartAsync() {
            // no-op
        }
    }

    private static final class StubReleaseSource implements ReleaseSourcePort {

        private final String sourceName;
        private final Deque<StubAction> actions = new ArrayDeque<>();

        private StubReleaseSource() {
            this("stub");
        }

        private StubReleaseSource(String sourceName) {
            this.sourceName = sourceName;
        }

        @Override
        public String name() {
            return sourceName;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public Optional<AvailableRelease> fetchLatestRelease() throws IOException, InterruptedException {
            StubAction action = actions.pollFirst();
            if (action == null) {
                return Optional.empty();
            }
            if (action.interruptedException != null) {
                throw action.interruptedException;
            }
            if (action.ioException != null) {
                throw action.ioException;
            }
            return Optional.ofNullable(action.release);
        }

        @Override
        public InputStream downloadAsset(AvailableRelease release) throws IOException, InterruptedException {
            StubAction action = actions.pollFirst();
            if (action == null) {
                throw new IOException("No stub asset configured");
            }
            if (action.ioException != null) {
                throw action.ioException;
            }
            return new ByteArrayInputStream(action.assetBytes);
        }

        @Override
        public ChecksumInfo downloadChecksum(AvailableRelease release) throws IOException, InterruptedException {
            StubAction action = actions.pollFirst();
            if (action == null) {
                throw new IOException("No stub checksum configured");
            }
            if (action.ioException != null) {
                throw action.ioException;
            }
            return action.checksumInfo;
        }

        void enqueueRelease(String version, String assetName, String publishedAt) {
            StubAction action = new StubAction();
            action.release = AvailableRelease.builder()
                    .version(version)
                    .tagName("v" + version)
                    .assetName(assetName)
                    .downloadUrl("https://example.com/download/" + assetName)
                    .checksumUrl("https://example.com/checksum/" + assetName)
                    .publishedAt(Instant.parse(publishedAt))
                    .source(sourceName)
                    .build();
            actions.addLast(action);
        }

        void enqueueEmpty() {
            actions.addLast(new StubAction());
        }

        void enqueueAsset(byte[] bytes) {
            StubAction action = new StubAction();
            action.assetBytes = bytes;
            actions.addLast(action);
        }

        void enqueueChecksum(String hexDigest, String algorithm, String assetName) {
            StubAction action = new StubAction();
            action.checksumInfo = new ChecksumInfo(hexDigest, algorithm, assetName);
            actions.addLast(action);
        }

        void enqueueIOException(IOException exception) {
            StubAction action = new StubAction();
            action.ioException = exception;
            actions.addLast(action);
        }

        void enqueueInterrupted(InterruptedException exception) {
            StubAction action = new StubAction();
            action.interruptedException = exception;
            actions.addLast(action);
        }
    }

    private static final class StubAction {
        private AvailableRelease release;
        private byte[] assetBytes;
        private ReleaseSourcePort.ChecksumInfo checksumInfo;
        private IOException ioException;
        private InterruptedException interruptedException;
    }

    private static final class MutableClock extends Clock {

        private final Instant currentInstant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.currentInstant = instant;
            this.zone = zone;
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
