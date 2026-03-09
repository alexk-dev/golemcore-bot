package me.golemcore.bot.domain.service;

import me.golemcore.bot.infrastructure.config.BotProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspacePathService {

    private final BotProperties botProperties;

    private Path workspaceRoot;

    @PostConstruct
    public void init() {
        String workspace = botProperties.getTools().getFilesystem().getWorkspace();
        this.workspaceRoot = Paths.get(workspace.replace("${user.home}", System.getProperty("user.home")))
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(workspaceRoot);
            log.info("[WorkspaceFiles] Workspace root: {}", workspaceRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize workspace root", e);
        }
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public Path resolveSafePath(String relativePath) {
        String normalizedInput = relativePath == null ? "" : relativePath.trim();
        if (normalizedInput.startsWith("/") || normalizedInput.startsWith("\\")) {
            throw new IllegalArgumentException("Absolute paths are not allowed");
        }
        if (normalizedInput.contains("../") || normalizedInput.contains("..\\")) {
            throw new IllegalArgumentException("Path traversal is not allowed");
        }

        try {
            Path resolved = workspaceRoot.resolve(normalizedInput).normalize();
            if (!resolved.startsWith(workspaceRoot)) {
                throw new IllegalArgumentException("Path must be inside workspace");
            }
            validateExistingParentInsideWorkspace(resolved);
            return resolved;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path");
        }
    }

    public String toRelativePath(Path path) {
        String raw = workspaceRoot.relativize(path).toString();
        return raw.replace("\\", "/");
    }

    public String requireFileName(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            throw new IllegalStateException("Path has no file name: " + path);
        }
        return fileName.toString();
    }

    public String resolveMimeType(Path path, String requestedMimeType) {
        if (requestedMimeType != null && !requestedMimeType.isBlank()) {
            return requestedMimeType;
        }
        try {
            String detected = Files.probeContentType(path);
            if (detected != null && !detected.isBlank()) {
                return detected;
            }
        } catch (IOException ignored) {
            // Fall back to extension-based detection below.
        }

        String filename = requireFileName(path).toLowerCase(Locale.ROOT);
        if (filename.endsWith(".png")) {
            return "image/png";
        }
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (filename.endsWith(".webp")) {
            return "image/webp";
        }
        if (filename.endsWith(".pdf")) {
            return "application/pdf";
        }
        return "application/octet-stream";
    }

    private void validateExistingParentInsideWorkspace(Path resolvedPath) {
        Path existingPath = findExistingPath(resolvedPath);
        if (existingPath == null) {
            throw new IllegalArgumentException("Invalid path");
        }

        try {
            Path realExistingPath = existingPath.toRealPath();
            Path realWorkspacePath = workspaceRoot.toRealPath();
            if (!realExistingPath.startsWith(realWorkspacePath)) {
                throw new IllegalArgumentException("Path must be inside workspace");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to resolve path", e);
        }
    }

    private Path findExistingPath(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current;
    }
}
