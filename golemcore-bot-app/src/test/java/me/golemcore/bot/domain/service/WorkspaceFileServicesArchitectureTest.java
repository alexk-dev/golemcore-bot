package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkspaceFileServicesArchitectureTest {

    private static final Path WORKSPACE_PATH_SERVICE = Path.of(
            "../golemcore-bot-tools/src/main/java/me/golemcore/bot/domain/service/WorkspacePathService.java");
    private static final Path TOOL_ARTIFACT_SERVICE = Path.of(
            "../golemcore-bot-tools/src/main/java/me/golemcore/bot/domain/service/ToolArtifactService.java");
    private static final Path DASHBOARD_FILE_SERVICE = Path.of(
            "src/main/java/me/golemcore/bot/domain/service/DashboardFileService.java");
    private static final Path UPDATE_RUNTIME_CLEANUP_SERVICE = Path.of(
            "src/main/java/me/golemcore/bot/domain/service/UpdateRuntimeCleanupService.java");
    private static final Path WORKSPACE_INSTRUCTION_SERVICE = Path.of(
            "src/main/java/me/golemcore/bot/domain/service/WorkspaceInstructionService.java");

    @Test
    void workspacePathServiceShouldKeepPathPolicyButDelegateFilesystemExecution() {
        String source = readSource(WORKSPACE_PATH_SERVICE);

        assertTrue(source.contains("WorkspaceFilePort"),
                "WorkspacePathService should depend on WorkspaceFilePort for directory creation, existence checks, real path resolution, and mime probing");
        assertFalse(source.contains("import java.nio.file.Files;"),
                "WorkspacePathService should not import Files directly");
        assertFalse(source.contains("Files.createDirectories("),
                "WorkspacePathService should not create directories directly");
        assertFalse(source.contains("Files.probeContentType("),
                "WorkspacePathService should not probe mime types directly");
        assertFalse(source.contains("Files.exists("),
                "WorkspacePathService should not check filesystem existence directly");
        assertFalse(source.contains("toRealPath("),
                "WorkspacePathService should not resolve real paths directly");
    }

    @Test
    void toolArtifactServiceShouldDelegateWorkspaceFileOperations() {
        String source = readSource(TOOL_ARTIFACT_SERVICE);

        assertTrue(source.contains("WorkspaceFilePort"),
                "ToolArtifactService should depend on WorkspaceFilePort for file writes, reads, and existence checks");
        assertFalse(source.contains("import java.nio.file.Files;"),
                "ToolArtifactService should not import Files directly");
        assertFalse(source.contains("Files.createDirectories("),
                "ToolArtifactService should not create directories directly");
        assertFalse(source.contains("Files.write("),
                "ToolArtifactService should not write artifact files directly");
        assertFalse(source.contains("Files.exists("),
                "ToolArtifactService should not check file existence directly");
        assertFalse(source.contains("Files.readAllBytes("),
                "ToolArtifactService should not read file bytes directly");
    }

    @Test
    void dashboardFileServiceShouldDelegateWorkspaceFileOperations() {
        String source = readSource(DASHBOARD_FILE_SERVICE);

        assertTrue(source.contains("WorkspaceFilePort"),
                "DashboardFileService should depend on WorkspaceFilePort for tree listing, reads, writes, renames, and deletes");
        assertFalse(source.contains("import java.nio.file.Files;"),
                "DashboardFileService should not import Files directly");
        assertFalse(source.contains("Files.list("),
                "DashboardFileService should not list directories directly");
        assertFalse(source.contains("Files.readString("),
                "DashboardFileService should not read file content directly");
        assertFalse(source.contains("Files.writeString("),
                "DashboardFileService should not write file content directly");
        assertFalse(source.contains("Files.move("),
                "DashboardFileService should not rename paths directly");
        assertFalse(source.contains("Files.walk("),
                "DashboardFileService should not recursively delete directories directly");
        assertFalse(source.contains("Files.delete("),
                "DashboardFileService should not delete paths directly");
    }

    @Test
    void updateRuntimeCleanupServiceShouldDelegateWorkspaceFileOperations() {
        String source = readSource(UPDATE_RUNTIME_CLEANUP_SERVICE);

        assertTrue(source.contains("WorkspaceFilePort"),
                "UpdateRuntimeCleanupService should depend on WorkspaceFilePort for marker reads, listing jars, and deletes");
        assertFalse(source.contains("import java.nio.file.Files;"),
                "UpdateRuntimeCleanupService should not import Files directly");
        assertFalse(source.contains("Files.isDirectory("),
                "UpdateRuntimeCleanupService should not inspect directories directly");
        assertFalse(source.contains("Files.list("),
                "UpdateRuntimeCleanupService should not list jars directly");
        assertFalse(source.contains("Files.readString("),
                "UpdateRuntimeCleanupService should not read markers directly");
        assertFalse(source.contains("Files.deleteIfExists("),
                "UpdateRuntimeCleanupService should not delete files directly");
    }

    @Test
    void workspaceInstructionServiceShouldDelegateWorkspaceFileOperations() {
        String source = readSource(WORKSPACE_INSTRUCTION_SERVICE);

        assertTrue(source.contains("WorkspaceFilePort"),
                "WorkspaceInstructionService should depend on WorkspaceFilePort for walking instruction files and reading content");
        assertFalse(source.contains("import java.nio.file.Files;"),
                "WorkspaceInstructionService should not import Files directly");
        assertFalse(source.contains("Files.walkFileTree("),
                "WorkspaceInstructionService should not walk the filesystem directly");
        assertFalse(source.contains("Files.readString("),
                "WorkspaceInstructionService should not read instruction files directly");
        assertFalse(source.contains("Files.isDirectory("),
                "WorkspaceInstructionService should not inspect directories directly");
    }

    private String readSource(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read source: " + path, exception);
        }
    }
}
