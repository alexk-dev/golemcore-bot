package me.golemcore.bot.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import me.golemcore.bot.port.outbound.UpdateSettingsPort;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpdateRuntimeCleanupService {

    private static final String CURRENT_MARKER_NAME = "current.txt";
    private static final String STAGED_MARKER_NAME = "staged.txt";
    private static final String JARS_DIR_NAME = "jars";

    private final UpdateSettingsPort settingsPort;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        cleanupAfterSuccessfulStartup();
    }

    @SuppressWarnings("PMD.NullAssignment")
    void cleanupAfterSuccessfulStartup() {
        if (!settingsPort.update().enabled()) {
            return;
        }

        Path updatesDir = Path.of(settingsPort.update().updatesPath()).toAbsolutePath().normalize();
        Path jarsDir = updatesDir.resolve(JARS_DIR_NAME);
        if (!Files.isDirectory(jarsDir)) {
            return;
        }

        Path currentMarker = updatesDir.resolve(CURRENT_MARKER_NAME);
        Path stagedMarker = updatesDir.resolve(STAGED_MARKER_NAME);
        String currentAsset = readMarker(currentMarker);
        String stagedAsset = readMarker(stagedMarker);

        if (currentAsset != null && currentAsset.equals(stagedAsset)) {
            deleteIfExists(stagedMarker);
            stagedAsset = null;
        }

        Set<String> retainedAssets = new HashSet<>();
        if (currentAsset != null) {
            retainedAssets.add(currentAsset);
        }
        if (stagedAsset != null) {
            retainedAssets.add(stagedAsset);
        }

        try (java.util.stream.Stream<Path> stream = Files.list(jarsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> shouldDeleteJar(path, retainedAssets))
                    .forEach(this::deleteIfExists);
        } catch (IOException e) {
            log.warn("[update] failed to cleanup old runtime jars: {}", e.getMessage());
        }
    }

    private boolean shouldDeleteJar(Path path, Set<String> retainedAssets) {
        String fileName = fileName(path);
        return fileName != null && fileName.endsWith(".jar") && !retainedAssets.contains(fileName);
    }

    private String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : null;
    }

    private String readMarker(Path markerPath) {
        try {
            if (!Files.exists(markerPath)) {
                return null;
            }
            String content = Files.readString(markerPath, StandardCharsets.UTF_8).trim();
            return content.isBlank() ? null : content;
        } catch (IOException e) {
            return null;
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("[update] failed to delete {}: {}", path, e.getMessage());
        }
    }
}
