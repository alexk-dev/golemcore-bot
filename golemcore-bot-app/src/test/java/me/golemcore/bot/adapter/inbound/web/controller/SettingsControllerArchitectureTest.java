package me.golemcore.bot.adapter.inbound.web.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SettingsControllerArchitectureTest {

    private static final Path CONTROLLER_SOURCE = Path.of(
            "src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsController.java");

    @Test
    void settingsControllerShouldDependOnDedicatedSettingsMapperAndDtos() {
        String source = readSource();

        assertTrue(source.contains("RuntimeSettingsWebMapper"),
                "SettingsController should delegate HTTP mapping to RuntimeSettingsWebMapper");
        assertTrue(source.contains("adapter.inbound.web.dto.settings"),
                "SettingsController should use dedicated settings web DTOs instead of exposing domain config objects directly");
    }

    @Test
    void settingsControllerShouldNotExposeRawRuntimeConfigAsPrimaryHttpContract() {
        String source = readSource();

        assertFalse(source.contains("ResponseEntity<RuntimeConfig>"),
                "SettingsController should not expose RuntimeConfig directly in HTTP responses");
        assertFalse(source.contains("@RequestBody RuntimeConfig "),
                "SettingsController should not accept RuntimeConfig directly as HTTP request payload");
        assertFalse(source.contains("@RequestBody RuntimeConfig."),
                "SettingsController should not accept nested RuntimeConfig sections directly as HTTP request payloads");
    }

    private String readSource() {
        try {
            return Files.readString(CONTROLLER_SOURCE);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read SettingsController source", exception);
        }
    }
}
