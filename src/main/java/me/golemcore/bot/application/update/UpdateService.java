package me.golemcore.bot.application.update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AvailableRelease;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.UpdateActionResult;
import me.golemcore.bot.domain.model.UpdateState;
import me.golemcore.bot.domain.model.UpdateStatus;
import me.golemcore.bot.domain.model.UpdateVersionInfo;
import me.golemcore.bot.domain.service.UpdateActivityGate;
import me.golemcore.bot.domain.service.UpdateMaintenanceWindow;
import me.golemcore.bot.port.outbound.ReleaseSourcePort;
import me.golemcore.bot.port.outbound.UpdateArtifactStorePort;
import me.golemcore.bot.port.outbound.UpdateRestartPort;
import me.golemcore.bot.port.outbound.UpdateRuntimeConfigPort;
import me.golemcore.bot.port.outbound.UpdateSettingsPort;
import me.golemcore.bot.port.outbound.UpdateVersionPort;

@Slf4j
public class UpdateService {

    private static final int RESTART_EXIT_CODE = 42;
    private static final long RESTART_DELAY_MILLIS = 500L;

    private final UpdateSettingsPort settingsPort;
    private final UpdateVersionPort updateVersionPort;
    private final UpdateRuntimeConfigPort updateRuntimeConfigPort;
    private final UpdateRestartPort updateRestartPort;
    private final java.time.Clock clock;
    private final UpdateActivityGate updateActivityGate;
    private final UpdateMaintenanceWindow updateMaintenanceWindow;
    private final List<ReleaseSourcePort> releaseSources;
    private final UpdateArtifactStorePort updateArtifactStorePort;
    private final UpdateVersionSupport updateVersionSupport = new UpdateVersionSupport();

    private final Object lock = new Object();

    private UpdateState transientState = UpdateState.IDLE;
    private Instant lastCheckAt;
    private String lastCheckError = "";
    private Optional<AvailableRelease> availableRelease = Optional.empty();
    private Optional<UpdateVersionInfo> activeTarget = Optional.empty();

    public UpdateService(
            UpdateSettingsPort settingsPort,
            UpdateVersionPort updateVersionPort,
            UpdateRuntimeConfigPort updateRuntimeConfigPort,
            UpdateRestartPort updateRestartPort,
            java.time.Clock clock,
            UpdateActivityGate updateActivityGate,
            UpdateMaintenanceWindow updateMaintenanceWindow,
            List<ReleaseSourcePort> releaseSources,
            UpdateArtifactStorePort updateArtifactStorePort) {
        this.settingsPort = settingsPort;
        this.updateVersionPort = updateVersionPort;
        this.updateRuntimeConfigPort = updateRuntimeConfigPort;
        this.updateRestartPort = updateRestartPort;
        this.clock = clock;
        this.updateActivityGate = updateActivityGate;
        this.updateMaintenanceWindow = updateMaintenanceWindow;
        this.releaseSources = releaseSources;
        this.updateArtifactStorePort = updateArtifactStorePort;
    }

    public UpdateStatus getStatus() {
        synchronized (lock) {
            boolean enabled = isEnabled();
            UpdateVersionInfo current = resolveCurrentInfo();
            UpdateVersionInfo staged = resolveStagedInfo();
            UpdateVersionInfo available = resolveAvailableInfo();
            UpdateState effectiveState = resolveState(enabled, staged, available);
            UpdateVersionInfo target = resolveTargetInfo(effectiveState, staged, available);
            StagePresentation stagePresentation = buildStagePresentation(effectiveState, current, target);
            UpdateActivityGate.Result activityStatus = updateActivityGate.getStatus();
            UpdateMaintenanceWindow.Status windowStatus = evaluateMaintenanceWindow();

            return UpdateStatus.builder()
                    .state(effectiveState)
                    .enabled(enabled)
                    .autoEnabled(updateRuntimeConfigPort.isAutoUpdateEnabled())
                    .maintenanceWindowEnabled(updateRuntimeConfigPort.isUpdateMaintenanceWindowEnabled())
                    .maintenanceWindowStartUtc(updateRuntimeConfigPort.getUpdateMaintenanceWindowStartUtc())
                    .maintenanceWindowEndUtc(updateRuntimeConfigPort.getUpdateMaintenanceWindowEndUtc())
                    .serverTimezone("UTC")
                    .windowOpen(windowStatus.open())
                    .busy(activityStatus.busy())
                    .blockedReason(activityStatus.blockedReason())
                    .nextEligibleAt(windowStatus.nextEligibleAt())
                    .current(current)
                    .target(target)
                    .staged(staged)
                    .available(available)
                    .lastCheckAt(lastCheckAt)
                    .lastError(lastCheckError == null || lastCheckError.isBlank() ? null : lastCheckError)
                    .progressPercent(stagePresentation.progressPercent())
                    .stageTitle(stagePresentation.title())
                    .stageDescription(stagePresentation.description())
                    .build();
        }
    }

    public UpdateActionResult check() {
        synchronized (lock) {
            ensureEnabled();
            ensureNoBusyOperation();
            activeTarget = Optional.empty();
            transientState = UpdateState.CHECKING;
        }

        try {
            CheckResolution resolution = resolveCheck();
            return UpdateActionResult.builder()
                    .success(true)
                    .message(resolution.message())
                    .version(resolution.version())
                    .build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw handleCheckFailure(exception);
        } catch (java.io.IOException | RuntimeException exception) {
            throw handleCheckFailure(exception);
        }
    }

    public RuntimeConfig.UpdateConfig getConfig() {
        return updateRuntimeConfigPort.getUpdateConfig();
    }

    public RuntimeConfig.UpdateConfig updateConfig(RuntimeConfig.UpdateConfig updateConfig) {
        return updateRuntimeConfigPort.updateUpdateConfig(updateConfig);
    }

    private UpdateActionResult prepare() {
        AvailableRelease release;
        synchronized (lock) {
            ensureEnabled();
            release = availableRelease
                    .orElseThrow(() -> new IllegalStateException("No available update. Run check first."));
            activeTarget = Optional.of(toVersionInfo(release));
            transientState = UpdateState.PREPARING;
        }

        try {
            UpdateArtifactStorePort.PreparedArtifact preparedArtifact = updateArtifactStorePort.stageReleaseAsset(
                    new UpdateArtifactStorePort.StageArtifactRequest(
                            release.getAssetName(),
                            findSourcePort(release.getSource()).downloadAsset(release),
                            findSourcePort(release.getSource()).downloadChecksum(release)));

            synchronized (lock) {
                availableRelease = Optional.empty();
                activeTarget = Optional.of(UpdateVersionInfo.builder()
                        .version(release.getVersion())
                        .tag(release.getTagName())
                        .assetName(release.getAssetName())
                        .preparedAt(preparedArtifact.preparedAt())
                        .publishedAt(release.getPublishedAt())
                        .build());
                transientState = UpdateState.IDLE;
            }

            return UpdateActionResult.builder()
                    .success(true)
                    .message("Update staged: " + release.getVersion())
                    .version(release.getVersion())
                    .build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw handlePrepareFailure(release.getAssetName(), exception);
        } catch (java.io.IOException | RuntimeException exception) {
            throw handlePrepareFailure(release.getAssetName(), exception);
        }
    }

    public UpdateActionResult updateNow() {
        Optional<UpdateVersionInfo> targetVersion = Optional.empty();
        synchronized (lock) {
            ensureEnabled();
            ensureNoBusyOperation();
            ensureNoRuntimeWorkInProgress();
            lastCheckError = "";

            UpdateVersionInfo staged = resolveStagedInfo();
            if (staged != null) {
                activeTarget = Optional.of(copyVersionInfo(staged));
                transientState = UpdateState.APPLYING;
                targetVersion = Optional.of(copyVersionInfo(staged));
            } else if (availableRelease.isPresent()) {
                AvailableRelease release = availableRelease.orElseThrow();
                UpdateVersionInfo availableTarget = toVersionInfo(release);
                activeTarget = Optional.of(availableTarget);
                transientState = UpdateState.PREPARING;
                targetVersion = Optional.of(copyVersionInfo(availableTarget));
            } else {
                activeTarget = Optional.empty();
                transientState = UpdateState.CHECKING;
            }
        }

        startUpdateTask(this::runUpdateNowWorkflow);

        String version = targetVersion.map(UpdateVersionInfo::getVersion).orElse(null);
        String message = version == null
                ? "Update workflow started. Checking the latest release."
                : "Update workflow started for " + version + ". Page will reload after restart.";
        return UpdateActionResult.builder()
                .success(true)
                .message(message)
                .version(version)
                .build();
    }

    private UpdateActionResult applyStagedUpdate() {
        synchronized (lock) {
            ensureEnabled();

            UpdateVersionInfo staged = resolveStagedInfo();
            if (staged == null || staged.getAssetName() == null || staged.getAssetName().isBlank()) {
                throw new IllegalStateException("No staged update to apply");
            }

            activeTarget = Optional.of(copyVersionInfo(staged));
            availableRelease = Optional.empty();
            updateArtifactStorePort.activateStagedArtifact(staged.getAssetName());
            transientState = UpdateState.APPLYING;

            requestRestartAsync();

            return UpdateActionResult.builder()
                    .success(true)
                    .message("Update " + staged.getVersion() + " is being applied. JVM restart scheduled.")
                    .version(staged.getVersion())
                    .build();
        }
    }

    public boolean isEnabled() {
        return settingsPort.update().enabled();
    }

    private AvailableRelease fetchLatestRelease() throws java.io.IOException, InterruptedException {
        java.io.IOException lastException = null;

        for (ReleaseSourcePort source : releaseSources) {
            if (!source.isEnabled()) {
                continue;
            }
            try {
                Optional<AvailableRelease> result = source.fetchLatestRelease();
                if (result.isPresent()) {
                    log.info("[update] Release discovered via {}: {}", source.name(), result.get().getVersion());
                    return result.get();
                }
                log.debug("[update] No release found via {}", source.name());
            } catch (java.io.IOException exception) {
                log.warn("[update] Release source {} failed: {}", source.name(), exception.getMessage());
                lastException = exception;
            }
        }

        if (lastException != null) {
            throw new java.io.IOException("All release sources failed", lastException);
        }
        return null;
    }

    private ReleaseSourcePort findSourcePort(String sourceName) {
        if (sourceName != null) {
            for (ReleaseSourcePort source : releaseSources) {
                if (source.name().equals(sourceName)) {
                    return source;
                }
            }
        }
        return releaseSources.stream()
                .filter(ReleaseSourcePort::isEnabled)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No release source available"));
    }

    private UpdateState resolveState(boolean enabled, UpdateVersionInfo staged, UpdateVersionInfo available) {
        if (!enabled) {
            return UpdateState.DISABLED;
        }
        if (transientState == UpdateState.FAILED) {
            return UpdateState.FAILED;
        }
        if (transientState == UpdateState.CHECKING
                || transientState == UpdateState.PREPARING
                || transientState == UpdateState.APPLYING
                || transientState == UpdateState.VERIFYING) {
            return transientState;
        }
        if ((transientState == UpdateState.WAITING_FOR_WINDOW || transientState == UpdateState.WAITING_FOR_IDLE)
                && (staged != null || activeTarget.isPresent())) {
            return transientState;
        }
        if (staged != null) {
            return UpdateState.STAGED;
        }
        if (available != null) {
            return UpdateState.AVAILABLE;
        }
        if (lastCheckError != null && !lastCheckError.isBlank()) {
            return UpdateState.FAILED;
        }
        return UpdateState.IDLE;
    }

    private UpdateVersionInfo resolveCurrentInfo() {
        String currentVersion = getCurrentVersion();
        Optional<UpdateArtifactStorePort.StoredArtifact> currentArtifact = updateArtifactStorePort
                .findCurrentArtifact();
        if (currentArtifact.isPresent()) {
            String assetName = currentArtifact.orElseThrow().assetName();
            try {
                String markerVersion = updateVersionSupport.extractVersionFromAssetName(assetName);
                if (markerVersion != null
                        && updateVersionSupport.normalizeVersion(markerVersion)
                                .equals(updateVersionSupport.normalizeVersion(currentVersion))) {
                    return UpdateVersionInfo.builder()
                            .version(currentVersion)
                            .source("jar")
                            .assetName(assetName)
                            .build();
                }
            } catch (IllegalArgumentException exception) {
                log.warn("[update] Invalid current marker value: {}", assetName);
            }
        }

        return UpdateVersionInfo.builder()
                .version(currentVersion)
                .source("image")
                .build();
    }

    private UpdateVersionInfo resolveStagedInfo() {
        Optional<UpdateArtifactStorePort.StoredArtifact> stagedArtifact = updateArtifactStorePort.findStagedArtifact();
        if (stagedArtifact.isEmpty()) {
            return null;
        }
        try {
            String assetName = stagedArtifact.orElseThrow().assetName();
            String version = updateVersionSupport.extractVersionFromAssetName(assetName);
            return UpdateVersionInfo.builder()
                    .version(version)
                    .assetName(assetName)
                    .preparedAt(stagedArtifact.orElseThrow().modifiedAt())
                    .build();
        } catch (IllegalArgumentException exception) {
            log.warn("[update] Invalid staged marker value: {}", stagedArtifact.orElseThrow().assetName());
            return null;
        }
    }

    private UpdateVersionInfo resolveAvailableInfo() {
        return availableRelease.map(release -> UpdateVersionInfo.builder()
                .version(release.getVersion())
                .tag(release.getTagName())
                .assetName(release.getAssetName())
                .publishedAt(release.getPublishedAt())
                .build())
                .orElse(null);
    }

    private void ensureEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException("Update feature is disabled");
        }
    }

    private void ensureNoBusyOperation() {
        if (transientState == UpdateState.CHECKING
                || transientState == UpdateState.PREPARING
                || transientState == UpdateState.APPLYING
                || transientState == UpdateState.VERIFYING) {
            throw new IllegalStateException("Another update operation is already in progress");
        }
    }

    private void ensureNoRuntimeWorkInProgress() {
        if (updateActivityGate.getStatus().busy()) {
            throw new IllegalStateException("Update is blocked while runtime work is still running");
        }
    }

    private CheckResolution resolveCheck() throws java.io.IOException, InterruptedException {
        AvailableRelease latestRelease = fetchLatestRelease();
        Instant now = Instant.now(clock);
        synchronized (lock) {
            lastCheckAt = now;
            lastCheckError = "";
            activeTarget = Optional.empty();

            if (latestRelease == null) {
                availableRelease = Optional.empty();
                transientState = UpdateState.IDLE;
                return new CheckResolution(null, "No updates found", null);
            }

            String currentVersion = getCurrentVersion();
            if (!updateVersionSupport.isRemoteVersionNewer(latestRelease.getVersion(), currentVersion)) {
                availableRelease = Optional.empty();
                transientState = UpdateState.IDLE;
                return new CheckResolution(null, "Already up to date", currentVersion);
            }

            if (!updateVersionSupport.isSameMajorUpdate(currentVersion, latestRelease.getVersion())) {
                availableRelease = Optional.empty();
                transientState = UpdateState.IDLE;
                return new CheckResolution(null, "New major version found. Use Docker image upgrade.",
                        latestRelease.getVersion());
            }

            availableRelease = Optional.of(latestRelease);
            activeTarget = Optional.of(toVersionInfo(latestRelease));
            transientState = UpdateState.IDLE;
            return new CheckResolution(latestRelease, "Update available: " + latestRelease.getVersion(),
                    latestRelease.getVersion());
        }
    }

    private String getCurrentVersion() {
        return updateVersionSupport.normalizeVersion(updateVersionPort.currentVersion());
    }

    String extractVersionFromAssetName(String assetName) {
        return updateVersionSupport.extractVersionFromAssetName(assetName);
    }

    protected void requestRestartAsync() {
        Thread restartThread = new Thread(() -> {
            try {
                Thread.sleep(RESTART_DELAY_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }

            synchronized (lock) {
                transientState = UpdateState.VERIFYING;
            }

            updateRestartPort.restart(RESTART_EXIT_CODE);
        }, "update-restart-thread");

        restartThread.setDaemon(false);
        restartThread.start();
    }

    protected void startUpdateTask(Runnable task) {
        Thread workerThread = new Thread(task, "update-workflow-thread");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void runUpdateNowWorkflow() {
        try {
            UpdateVersionInfo staged;
            AvailableRelease release;
            synchronized (lock) {
                staged = resolveStagedInfo();
                release = availableRelease.orElse(null);
                if (staged != null) {
                    activeTarget = Optional.of(copyVersionInfo(staged));
                }
            }

            if (staged != null) {
                tryApplyStagedUpdate(true);
                return;
            }

            if (release == null) {
                CheckResolution resolution = resolveCheck();
                release = resolution.release();
                if (release == null) {
                    return;
                }
            }

            prepare();
            tryApplyStagedUpdate(true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            UpdateState state = transientState;
            if (state == UpdateState.FAILED) {
                return;
            }
            if (state == UpdateState.APPLYING || state == UpdateState.VERIFYING) {
                handleApplyFailure(exception);
                return;
            }
            if (state == UpdateState.CHECKING) {
                handleCheckFailure(exception);
                return;
            }
            handlePrepareFailure(null, exception);
        } catch (java.io.IOException | RuntimeException exception) {
            UpdateState state = transientState;
            if (state == UpdateState.FAILED) {
                return;
            }
            if (state == UpdateState.CHECKING) {
                handleCheckFailure(exception);
                return;
            }
            if (state == UpdateState.APPLYING || state == UpdateState.VERIFYING) {
                handleApplyFailure(exception);
                return;
            }
            handlePrepareFailure(null, exception);
        }
    }

    void runAutoUpdateCycle() {
        if (!isEnabled() || !updateRuntimeConfigPort.isAutoUpdateEnabled()) {
            return;
        }
        synchronized (lock) {
            if (transientState == UpdateState.CHECKING
                    || transientState == UpdateState.PREPARING
                    || transientState == UpdateState.APPLYING
                    || transientState == UpdateState.VERIFYING) {
                return;
            }
        }

        try {
            UpdateVersionInfo staged = resolveStagedInfo();
            if (staged != null) {
                synchronized (lock) {
                    activeTarget = Optional.of(copyVersionInfo(staged));
                }
                tryApplyStagedUpdate(false);
                return;
            }

            AvailableRelease release;
            synchronized (lock) {
                release = availableRelease.orElse(null);
            }
            if (release != null) {
                prepare();
                tryApplyStagedUpdate(false);
                return;
            }

            if (!shouldPerformAutoCheck()) {
                return;
            }

            CheckResolution resolution = resolveCheck();
            if (resolution.release() == null) {
                return;
            }

            prepare();
            tryApplyStagedUpdate(false);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            handleAutoWorkflowFailure(exception);
        } catch (java.io.IOException | RuntimeException exception) {
            handleAutoWorkflowFailure(exception);
        }
    }

    public void runAutoUpdateCycleSafely() {
        try {
            runAutoUpdateCycle();
        } catch (RuntimeException exception) {
            log.warn("[update] auto update cycle failed: {}", safeMessage(exception));
        }
    }

    private boolean shouldPerformAutoCheck() {
        Integer intervalMinutes = updateRuntimeConfigPort.getUpdateCheckIntervalMinutes();
        if (intervalMinutes == null || intervalMinutes < 1) {
            intervalMinutes = 60;
        }
        Instant previousCheck = lastCheckAt;
        if (previousCheck == null) {
            return true;
        }
        return !previousCheck.plus(java.time.Duration.ofMinutes(intervalMinutes)).isAfter(Instant.now(clock));
    }

    private UpdateMaintenanceWindow.Status evaluateMaintenanceWindow() {
        return updateMaintenanceWindow.evaluate(
                updateRuntimeConfigPort.isUpdateMaintenanceWindowEnabled(),
                updateRuntimeConfigPort.getUpdateMaintenanceWindowStartUtc(),
                updateRuntimeConfigPort.getUpdateMaintenanceWindowEndUtc(),
                Instant.now(clock));
    }

    private boolean tryApplyStagedUpdate(boolean bypassMaintenanceWindow) {
        UpdateVersionInfo staged = resolveStagedInfo();
        if (staged == null) {
            return false;
        }

        synchronized (lock) {
            activeTarget = Optional.of(copyVersionInfo(staged));
        }

        if (!bypassMaintenanceWindow) {
            UpdateMaintenanceWindow.Status windowStatus = evaluateMaintenanceWindow();
            if (!windowStatus.open()) {
                synchronized (lock) {
                    transientState = UpdateState.WAITING_FOR_WINDOW;
                }
                return false;
            }
        }

        UpdateActivityGate.Result activityStatus = updateActivityGate.getStatus();
        if (activityStatus.busy()) {
            synchronized (lock) {
                transientState = UpdateState.WAITING_FOR_IDLE;
            }
            return false;
        }

        applyStagedUpdate();
        return true;
    }

    private IllegalStateException handleCheckFailure(Throwable throwable) {
        String message = "Failed to check updates: " + safeMessage(throwable);
        synchronized (lock) {
            lastCheckError = message;
            activeTarget = Optional.empty();
            transientState = UpdateState.FAILED;
        }
        return new IllegalStateException(message, throwable);
    }

    private IllegalStateException handlePrepareFailure(String assetName, Throwable throwable) {
        cleanupTempJar(assetName);
        String message = "Failed to prepare update: " + safeMessage(throwable);
        synchronized (lock) {
            transientState = UpdateState.FAILED;
            lastCheckError = message;
        }
        return new IllegalStateException(message, throwable);
    }

    private IllegalStateException handleApplyFailure(Throwable throwable) {
        String message = "Failed to apply update: " + safeMessage(throwable);
        synchronized (lock) {
            transientState = UpdateState.FAILED;
            lastCheckError = message;
        }
        return new IllegalStateException(message, throwable);
    }

    private void handleAutoWorkflowFailure(Throwable throwable) {
        UpdateState state = transientState;
        if (state == UpdateState.FAILED) {
            return;
        }
        if (state == UpdateState.CHECKING) {
            handleCheckFailure(throwable);
            return;
        }
        if (state == UpdateState.APPLYING || state == UpdateState.VERIFYING) {
            handleApplyFailure(throwable);
            return;
        }
        handlePrepareFailure(null, throwable);
    }

    private void cleanupTempJar(String assetName) {
        if (assetName == null || assetName.isBlank()) {
            return;
        }
        updateArtifactStorePort.cleanupTempArtifact(assetName);
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "unknown error";
        }
        return throwable.getMessage();
    }

    private UpdateVersionInfo resolveTargetInfo(UpdateState effectiveState, UpdateVersionInfo staged,
            UpdateVersionInfo available) {
        if (effectiveState == UpdateState.APPLYING
                || effectiveState == UpdateState.VERIFYING
                || effectiveState == UpdateState.PREPARING
                || effectiveState == UpdateState.WAITING_FOR_WINDOW
                || effectiveState == UpdateState.WAITING_FOR_IDLE
                || effectiveState == UpdateState.FAILED) {
            if (activeTarget.isPresent()) {
                return copyVersionInfo(activeTarget.orElseThrow());
            }
        }
        if (staged != null) {
            return copyVersionInfo(staged);
        }
        if (available != null) {
            return copyVersionInfo(available);
        }
        if (effectiveState == UpdateState.CHECKING && activeTarget.isPresent()) {
            return copyVersionInfo(activeTarget.orElseThrow());
        }
        return null;
    }

    private StagePresentation buildStagePresentation(
            UpdateState state,
            UpdateVersionInfo current,
            UpdateVersionInfo target) {
        String currentVersion = current != null ? current.getVersion() : null;
        String targetVersion = target != null ? target.getVersion() : null;
        return switch (state) {
        case DISABLED -> new StagePresentation(
                0,
                "Updates disabled",
                "Self-update is disabled in backend configuration.");
        case CHECKING -> new StagePresentation(
                10,
                "Checking latest release",
                "Looking up the newest compatible release before starting the update.");
        case AVAILABLE -> new StagePresentation(
                25,
                buildVersionTitle("Update available", targetVersion),
                "A compatible release is ready. Start update to download it and restart the service.");
        case PREPARING -> new StagePresentation(
                52,
                buildVersionTitle("Downloading and verifying", targetVersion),
                "The release package is being downloaded and validated before restart.");
        case STAGED -> new StagePresentation(
                72,
                buildVersionTitle("Update staged", targetVersion),
                "The release is ready locally. Restart the service to switch to the new version.");
        case WAITING_FOR_WINDOW -> new StagePresentation(
                76,
                buildVersionTitle("Waiting for maintenance window", targetVersion),
                "The release is staged and will be applied when the configured UTC maintenance window opens.");
        case WAITING_FOR_IDLE -> new StagePresentation(
                80,
                buildVersionTitle("Waiting for running work", targetVersion),
                "The release is staged and will be applied once active session or auto-mode work finishes.");
        case APPLYING -> new StagePresentation(
                88,
                buildVersionTitle("Scheduling restart", targetVersion),
                "The new package is selected. The service is now restarting into the updated runtime.");
        case VERIFYING -> new StagePresentation(
                96,
                buildVersionTitle("Waiting for service", targetVersion),
                "The backend is restarting. This page should reconnect and reload automatically.");
        case FAILED -> new StagePresentation(
                targetVersion != null ? 52 : 12,
                "Update failed",
                lastCheckError == null || lastCheckError.isBlank()
                        ? "The last update attempt failed."
                        : lastCheckError);
        case IDLE -> new StagePresentation(
                0,
                currentVersion == null ? "System ready" : "Running " + currentVersion,
                "No update workflow is running.");
        };
    }

    private String buildVersionTitle(String prefix, String version) {
        if (version == null || version.isBlank()) {
            return prefix;
        }
        return prefix + " " + version;
    }

    private UpdateVersionInfo toVersionInfo(AvailableRelease release) {
        return UpdateVersionInfo.builder()
                .version(release.getVersion())
                .tag(release.getTagName())
                .assetName(release.getAssetName())
                .publishedAt(release.getPublishedAt())
                .build();
    }

    private UpdateVersionInfo copyVersionInfo(UpdateVersionInfo value) {
        if (value == null) {
            return null;
        }
        return UpdateVersionInfo.builder()
                .version(value.getVersion())
                .source(value.getSource())
                .tag(value.getTag())
                .assetName(value.getAssetName())
                .preparedAt(value.getPreparedAt())
                .publishedAt(value.getPublishedAt())
                .build();
    }

    private record CheckResolution(AvailableRelease release, String message, String version) {
    }

    private record StagePresentation(int progressPercent, String title, String description) {
    }
}
