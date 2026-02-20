package me.golemcore.bot.domain.service;

import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceInstructionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReadInstructionFilesRecursivelyInLocalityOrder() throws IOException {
        Path rootAgents = tempDir.resolve("AGENTS.md");
        Path appClaude = tempDir.resolve("app").resolve("CLAUDE.md");
        Path featureAgents = tempDir.resolve("app").resolve("feature").resolve("AGENTS.md");

        Files.createDirectories(appClaude.getParent());
        Files.createDirectories(featureAgents.getParent());

        Files.writeString(rootAgents, "Root instruction");
        Files.writeString(appClaude, "App instruction");
        Files.writeString(featureAgents, "Feature instruction");

        WorkspaceInstructionService service = new WorkspaceInstructionService(createProperties(tempDir));

        String context = service.getWorkspaceInstructionsContext();

        assertTrue(context.contains("## AGENTS.md"));
        assertTrue(context.contains("## app/CLAUDE.md"));
        assertTrue(context.contains("## app/feature/AGENTS.md"));

        assertTrue(context.indexOf("Root instruction") < context.indexOf("App instruction"));
        assertTrue(context.indexOf("App instruction") < context.indexOf("Feature instruction"));
    }

    @Test
    void shouldRenderAgentsAfterClaudeInSameDirectory() throws IOException {
        Path folder = tempDir.resolve("project");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("CLAUDE.md"), "Claude local");
        Files.writeString(folder.resolve("AGENTS.md"), "Agents local");

        WorkspaceInstructionService service = new WorkspaceInstructionService(createProperties(tempDir));
        String context = service.getWorkspaceInstructionsContext();

        assertTrue(context.indexOf("Claude local") < context.indexOf("Agents local"));
    }

    @Test
    void shouldSkipIgnoredDirectories() throws IOException {
        Path docsAgents = tempDir.resolve("docs").resolve("AGENTS.md");
        Path gitAgents = tempDir.resolve(".git").resolve("AGENTS.md");
        Path modulesClaude = tempDir.resolve("node_modules").resolve("CLAUDE.md");

        Files.createDirectories(docsAgents.getParent());
        Files.createDirectories(gitAgents.getParent());
        Files.createDirectories(modulesClaude.getParent());

        Files.writeString(docsAgents, "Docs instruction");
        Files.writeString(gitAgents, "Git instruction");
        Files.writeString(modulesClaude, "Modules instruction");

        WorkspaceInstructionService service = new WorkspaceInstructionService(createProperties(tempDir));
        String context = service.getWorkspaceInstructionsContext();

        assertTrue(context.contains("Docs instruction"));
        assertFalse(context.contains("Git instruction"));
        assertFalse(context.contains("Modules instruction"));
    }

    private BotProperties createProperties(Path workspace) {
        BotProperties properties = new BotProperties();
        String workspacePath = workspace.toAbsolutePath().toString();
        properties.getTools().getShell().setWorkspace(workspacePath);
        properties.getTools().getFilesystem().setWorkspace(workspacePath);
        return properties;
    }
}
