package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.DashboardFileContent;
import me.golemcore.bot.domain.model.DashboardFileNode;
import me.golemcore.bot.infrastructure.config.BotProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardFileService {

    private static final long MAX_EDITABLE_FILE_SIZE = 1024L * 1024L * 2L;

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
            log.info("[DashboardFiles] Workspace root: {}", workspaceRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize dashboard file workspace", e);
        }
    }

    public List<DashboardFileNode> getTree(String relativePath) {
        Path base = resolveSafePath(relativePath == null ? "" : relativePath);
        if (!Files.exists(base)) {
            throw new IllegalArgumentException("Path not found: " + relativePath);
        }
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("Path is not a directory: " + relativePath);
        }

        return buildChildren(base);
    }

    public DashboardFileContent getContent(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Path is required");
        }

        Path path = resolveSafePath(relativePath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: " + relativePath);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Not a file: " + relativePath);
        }

        try {
            long size = Files.size(path);
            if (size > MAX_EDITABLE_FILE_SIZE) {
                throw new IllegalArgumentException("File too large for editor (max 2 MB)");
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            String updatedAt = Files.getLastModifiedTime(path).toInstant().toString();
            return DashboardFileContent.builder()
                    .path(toRelativePath(path))
                    .content(content)
                    .size(size)
                    .updatedAt(updatedAt)
                    .build();
        } catch (MalformedInputException e) {
            throw new IllegalArgumentException("File is not valid UTF-8 text");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read file", e);
        }
    }

    public DashboardFileContent saveContent(String relativePath, String content) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Path is required");
        }
        if (content == null) {
            throw new IllegalArgumentException("Content is required");
        }

        Path path = resolveSafePath(relativePath);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            long size = Files.size(path);
            String updatedAt = Instant.now().toString();
            return DashboardFileContent.builder()
                    .path(toRelativePath(path))
                    .content(content)
                    .size(size)
                    .updatedAt(updatedAt)
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save file", e);
        }
    }

    private List<DashboardFileNode> buildChildren(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> sorted = stream
                    .filter(path -> !Files.isSymbolicLink(path))
                    .sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();

            List<DashboardFileNode> nodes = new ArrayList<>();
            for (Path path : sorted) {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    nodes.add(DashboardFileNode.builder()
                            .path(toRelativePath(path))
                            .name(path.getFileName().toString())
                            .type("directory")
                            .children(buildChildren(path))
                            .build());
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    long size = Files.size(path);
                    nodes.add(DashboardFileNode.builder()
                            .path(toRelativePath(path))
                            .name(path.getFileName().toString())
                            .type("file")
                            .size(size)
                            .children(List.of())
                            .build());
                }
            }

            return nodes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list directory", e);
        }
    }

    private Path resolveSafePath(String relativePath) {
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

    private String toRelativePath(Path path) {
        String raw = workspaceRoot.relativize(path).toString();
        return raw.replace("\\", "/");
    }
}
