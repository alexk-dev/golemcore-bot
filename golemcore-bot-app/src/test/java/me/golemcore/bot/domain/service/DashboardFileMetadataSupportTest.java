package me.golemcore.bot.domain.service;

import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardFileMetadataSupportTest {

    @TempDir
    Path tempDir;

    private WorkspacePathService workspacePathService;

    @BeforeEach
    void setUp() {
        Path workspaceRoot = tempDir.resolve("workspace");
        BotProperties botProperties = new BotProperties();
        botProperties.getTools().getFilesystem().setWorkspace(workspaceRoot.toString());
        workspacePathService = new WorkspacePathService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                new LocalTestWorkspaceFilePort());
        workspacePathService.init();
    }

    @ParameterizedTest
    @CsvSource({
            "config/app.json,application/json",
            "config/app.yml,application/yaml",
            "config/app.yaml,application/yaml",
            "pom.xml,application/xml",
            "index.html,text/html",
            "styles/app.css,text/css",
            "styles/app.scss,text/x-scss",
            "bin/run.sh,text/x-shellscript",
            "scripts/app.py,text/x-python",
            "cmd/main.go,text/x-go",
            "src/lib.rs,text/x-rustsrc",
            "src/Main.kt,text/x-kotlin",
            "db/schema.sql,application/sql",
            "config/app.toml,application/toml",
            "config/app.ini,text/plain",
            "notes/todo.txt,text/plain"
    })
    void shouldResolveTextLikeOctetStreamFallbacks(String relativePath, String expectedMimeType) {
        Path path = workspacePathService.resolveSafePath(relativePath);

        String mimeType = DashboardFileMetadataSupport.resolveMimeType(
                workspacePathService,
                path,
                "application/octet-stream");

        assertEquals(expectedMimeType, mimeType);
    }

    @Test
    void shouldKeepExplicitRequestedMimeType() {
        Path path = workspacePathService.resolveSafePath("src/App.tsx");

        String mimeType = DashboardFileMetadataSupport.resolveMimeType(workspacePathService, path, "text/custom");

        assertEquals("text/custom", mimeType);
    }

    @Test
    void shouldNormalizeMacBinaryProbeToGenericBinary() {
        Path path = workspacePathService.resolveSafePath("data/archive.bin");

        String mimeType = DashboardFileMetadataSupport.resolveMimeType(
                workspacePathService,
                path,
                "application/macbinary");

        assertEquals("application/octet-stream", mimeType);
    }
}
