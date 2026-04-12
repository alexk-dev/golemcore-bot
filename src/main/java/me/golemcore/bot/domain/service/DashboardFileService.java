package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.DashboardFileContent;
import me.golemcore.bot.domain.model.DashboardFileNode;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.port.outbound.WorkspaceFilePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DashboardFileService {

    private static final long MAX_EDITABLE_FILE_SIZE = 1024L * 1024L * 2L;

    private final WorkspacePathService workspacePathService;
    private final WorkspaceFilePort workspaceFilePort;

    public List<DashboardFileNode> getTree(String relativePath) {
        Path base = resolveSafePath(relativePath == null ? "" : relativePath);
        if (!workspaceFilePort.exists(base)) {
            throw new IllegalArgumentException("Path not found: " + relativePath);
        }
        if (!workspaceFilePort.isDirectory(base)) {
            throw new IllegalArgumentException("Path is not a directory: " + relativePath);
        }

        return buildChildren(base);
    }

    public DashboardFileContent getContent(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Path is required");
        }

        Path path = resolveSafePath(relativePath);
        if (!workspaceFilePort.exists(path)) {
            throw new IllegalArgumentException("File not found: " + relativePath);
        }
        if (!workspaceFilePort.isRegularFile(path)) {
            throw new IllegalArgumentException("Not a file: " + relativePath);
        }

        try {
            long size = workspaceFilePort.size(path);
            if (size > MAX_EDITABLE_FILE_SIZE) {
                throw new IllegalArgumentException("File too large for editor (max 2 MB)");
            }

            String content = workspaceFilePort.readString(path);
            String updatedAt = workspaceFilePort.getLastModifiedTime(path);
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

        if (workspaceFilePort.exists(path)) {
            throw new IllegalArgumentException("Path already exists: " + relativePath);
        }

        try {
            Path parent = path.getParent();
            if (parent != null) {
                workspaceFilePort.createDirectories(parent);
            }
            workspaceFilePort.write(path, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            long size = workspaceFilePort.size(path);
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
                workspaceFilePort.createDirectories(parent);
            }
            workspaceFilePort.writeString(path, content);

            long size = workspaceFilePort.size(path);
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
        if (!workspaceFilePort.exists(sourcePath)) {
            throw new IllegalArgumentException("Source path not found: " + sourceRelativePath);
        }
        if (workspaceFilePort.exists(targetPath)) {
            throw new IllegalArgumentException("Target path already exists: " + targetRelativePath);
        }

        try {
            Path targetParent = targetPath.getParent();
            if (targetParent != null) {
                workspaceFilePort.createDirectories(targetParent);
            }
            workspaceFilePort.move(sourcePath, targetPath);
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
        if (!workspaceFilePort.exists(path)) {
            throw new IllegalArgumentException("Path not found: " + relativePath);
        }

        try {
            if (workspaceFilePort.isDirectory(path)) {
                deleteDirectoryRecursively(path);
                return;
            }
            workspaceFilePort.delete(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete path", e);
        }
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        List<Path> paths = new ArrayList<>(workspaceFilePort.walk(path));
        paths.sort(Comparator.reverseOrder());
        for (Path current : paths) {
            if (current.equals(getWorkspaceRoot())) {
                continue;
            }
            workspaceFilePort.delete(current);
        }
    }

    private List<DashboardFileNode> buildChildren(Path dir) {
        try {
            List<Path> sorted = workspaceFilePort.list(dir).stream()
                    .filter(path -> !workspaceFilePort.isSymbolicLink(path))
                    .sorted(Comparator
                            .comparing((Path p) -> !workspaceFilePort.isDirectory(p))
                            .thenComparing(p -> requireFileName(p).toLowerCase(Locale.ROOT)))
                    .toList();

            List<DashboardFileNode> nodes = new ArrayList<>();
            for (Path path : sorted) {
                String nodeName = requireFileName(path);
                if (workspaceFilePort.isDirectory(path)) {
                    nodes.add(DashboardFileNode.builder()
                            .path(toRelativePath(path))
                            .name(nodeName)
                            .type("directory")
                            .children(buildChildren(path))
                            .build());
                } else if (workspaceFilePort.isRegularFile(path)) {
                    long size = workspaceFilePort.size(path);
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
