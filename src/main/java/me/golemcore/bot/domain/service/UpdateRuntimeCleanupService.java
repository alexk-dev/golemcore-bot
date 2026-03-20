package me.golemcore.bot.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpdateRuntimeCleanupService {

    private static final String CURRENT_MARKER_NAME = "current.txt";
    private static final String STAGED_MARKER_NAME = "staged.txt";
    private static final String JARS_DIR_NAME = "jars";

    private final BotProperties botProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        cleanupAfterSuccessfulStartup();
    }

    void cleanupAfterSuccessfulStartup() {
        if (!botProperties.getUpdate().isEnabled()) {
            return;
        }

        Path updatesDir = Path.of(botProperties.getUpdate().getUpdatesPath()).toAbsolutePath().normalize();
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
                    .filter(path -> path.getFileName() != null && path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !retainedAssets.contains(path.getFileName().toString()))
                    .forEach(this::deleteIfExists);
        } catch (IOException e) {
            log.warn("[update] failed to cleanup old runtime jars: {}", e.getMessage());
        }
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
