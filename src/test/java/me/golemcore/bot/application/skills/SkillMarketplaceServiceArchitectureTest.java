package me.golemcore.bot.application.skills;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SkillMarketplaceServiceArchitectureTest {

    private static final Path SOURCE_PATH = Path.of(
            "src/main/java/me/golemcore/bot/application/skills/SkillMarketplaceService.java");

    @Test
    void shouldDependOnMarketplacePortsForCatalogArtifactAndInstallWork() {
        String source = readSource();

        assertTrue(source.contains("SkillMarketplaceCatalogPort"),
                "Skill marketplace application service should delegate catalog loading to SkillMarketplaceCatalogPort");
        assertTrue(source.contains("SkillMarketplaceArtifactPort"),
                "Skill marketplace application service should delegate artifact content loading to SkillMarketplaceArtifactPort");
        assertTrue(source.contains("SkillMarketplaceInstallPort"),
                "Skill marketplace application service should delegate install and write work to SkillMarketplaceInstallPort");
    }

    @Test
    void shouldNotUseLowLevelHttpFilesystemOrCodecTypesDirectly() {
        String source = readSource();

        assertFalse(source.contains("import java.net.http.HttpClient;"),
                "Skill marketplace application service must not import HttpClient directly");
        assertFalse(source.contains("import java.net.http.HttpRequest;"),
                "Skill marketplace application service must not import HttpRequest directly");
        assertFalse(source.contains("import java.net.http.HttpResponse;"),
                "Skill marketplace application service must not import HttpResponse directly");
        assertFalse(source.contains("import java.nio.file.Files;"),
                "Skill marketplace application service must not import Files directly");
        assertFalse(source.contains("import com.fasterxml.jackson.databind.ObjectMapper;"),
                "Skill marketplace application service must not import ObjectMapper directly");
        assertFalse(source.contains("import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;"),
                "Skill marketplace application service must not import YAMLFactory directly");
        assertFalse(source.contains("HttpClient.newBuilder()"),
                "Skill marketplace application service must not construct HttpClient directly");
        assertFalse(source.contains("new ObjectMapper("),
                "Skill marketplace application service must not construct ObjectMapper directly");
        assertFalse(source.contains("Files.walk("),
                "Skill marketplace application service must not traverse the filesystem directly");
        assertFalse(source.contains("Files.readString("),
                "Skill marketplace application service must not read files directly");
    }

    private String readSource() {
        try {
            return Files.readString(SOURCE_PATH);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read SkillMarketplaceService source", exception);
        }
    }
}
