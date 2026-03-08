package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.DashboardFileContent;
import me.golemcore.bot.domain.model.DashboardFileNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class DashboardFileService {

    private static final long MAX_EDITABLE_FILE_SIZE = 1024L * 1024L * 2L;

    private final WorkspacePathService workspacePathService;

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

    public DashboardFileContent createContent(String relativePath, String content) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Path is required");
        }

        String value = content == null ? "" : content;
        Path path = resolveSafePath(relativePath);

        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Path already exists: " + relativePath);
        }

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, value, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);

            long size = Files.size(path);
            String updatedAt = Instant.now().toString();
            return DashboardFileContent.builder()
                    .path(toRelativePath(path))
                    .content(value)
                    .size(size)
                    .updatedAt(updatedAt)
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create file", e);
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

    public void renamePath(String sourceRelativePath, String targetRelativePath) {
        if (sourceRelativePath == null || sourceRelativePath.isBlank()) {
            throw new IllegalArgumentException("Source path is required");
        }
        if (targetRelativePath == null || targetRelativePath.isBlank()) {
            throw new IllegalArgumentException("Target path is required");
        }

        Path sourcePath = resolveSafePath(sourceRelativePath);
        Path targetPath = resolveSafePath(targetRelativePath);

        if (sourcePath.equals(getWorkspaceRoot()) || targetPath.equals(getWorkspaceRoot())) {
            throw new IllegalArgumentException("Workspace root cannot be renamed");
        }
        if (!Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Source path not found: " + sourceRelativePath);
        }
        if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Target path already exists: " + targetRelativePath);
        }

        try {
            Path targetParent = targetPath.getParent();
            if (targetParent != null) {
                Files.createDirectories(targetParent);
            }
            movePath(sourcePath, targetPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to rename path", e);
        }
    }

    public void deletePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Path is required");
        }

        Path path = resolveSafePath(relativePath);
        if (path.equals(getWorkspaceRoot())) {
            throw new IllegalArgumentException("Workspace root cannot be deleted");
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Path not found: " + relativePath);
        }

        try {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                deleteDirectoryRecursively(path);
                return;
            }
            Files.delete(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete path", e);
        }
    }

    private void movePath(Path sourcePath, Path targetPath) throws IOException {
        try {
            Files.move(sourcePath, targetPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(sourcePath, targetPath);
        }
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path current : paths) {
                if (current.equals(getWorkspaceRoot())) {
                    continue;
                }
                Files.delete(current);
            }
        }
    }

    private List<DashboardFileNode> buildChildren(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> sorted = stream
                    .filter(path -> !Files.isSymbolicLink(path))
                    .sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                            .thenComparing(p -> requireFileName(p).toLowerCase(Locale.ROOT)))
                    .toList();

            List<DashboardFileNode> nodes = new ArrayList<>();
            for (Path path : sorted) {
                String nodeName = requireFileName(path);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    nodes.add(DashboardFileNode.builder()
                            .path(toRelativePath(path))
                            .name(nodeName)
                            .type("directory")
                            .children(buildChildren(path))
                            .build());
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    long size = Files.size(path);
                    nodes.add(DashboardFileNode.builder()
                            .path(toRelativePath(path))
                            .name(nodeName)
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

    private Path getWorkspaceRoot() {
        return workspacePathService.getWorkspaceRoot();
    }

    private String toRelativePath(Path path) {
        return workspacePathService.toRelativePath(path);
    }

    private Path resolveSafePath(String relativePath) {
        return workspacePathService.resolveSafePath(relativePath);
    }

    private String requireFileName(Path path) {
        return workspacePathService.requireFileName(path);
    }
}
