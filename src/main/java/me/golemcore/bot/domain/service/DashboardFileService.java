package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.DashboardFileDownload;
import me.golemcore.bot.domain.model.DashboardFileContent;
import me.golemcore.bot.domain.model.DashboardFileNode;
import me.golemcore.bot.domain.model.DashboardStoredFile;
import me.golemcore.bot.infrastructure.config.BotProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardFileService {

    private static final long MAX_EDITABLE_FILE_SIZE = 1024L * 1024L * 2L;
    private static final String TOOL_ARTIFACTS_DIR = ".golemcore/tool-artifacts";
    private static final int MAX_FILENAME_LENGTH = 120;
    private static final int MAX_SEGMENT_LENGTH = 64;

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

    public DashboardStoredFile saveToolArtifact(
            String sessionId,
            String toolName,
            String filename,
            byte[] data,
            String mimeType) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("File data is required");
        }

        String safeSessionId = sanitizePathSegment(sessionId, "session");
        String safeToolName = sanitizePathSegment(toolName, "tool");
        String safeFilename = sanitizeFilename(filename, mimeType);
        String artifactId = Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String relativePath = TOOL_ARTIFACTS_DIR + "/" + safeSessionId + "/" + safeToolName + "/"
                + artifactId + "/" + safeFilename;
        Path path = resolveSafePath(relativePath);

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

            String storedPath = toRelativePath(path);
            String resolvedMimeType = resolveMimeType(path, mimeType);
            return DashboardStoredFile.builder()
                    .path(storedPath)
                    .filename(safeFilename)
                    .mimeType(resolvedMimeType)
                    .size(data.length)
                    .downloadUrl(buildDownloadUrl(storedPath))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store tool artifact", e);
        }
    }

    public DashboardFileDownload getDownload(String relativePath) {
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
            byte[] data = Files.readAllBytes(path);
            return DashboardFileDownload.builder()
                    .path(toRelativePath(path))
                    .filename(requireFileName(path))
                    .mimeType(resolveMimeType(path, null))
                    .size(data.length)
                    .data(data)
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read download file", e);
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

        if (sourcePath.equals(workspaceRoot) || targetPath.equals(workspaceRoot)) {
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
        if (path.equals(workspaceRoot)) {
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
                if (current.equals(workspaceRoot)) {
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
                            .thenComparing(p -> requireFileName(p).toLowerCase()))
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

    private String buildDownloadUrl(String relativePath) {
        String encoded = URLEncoder.encode(relativePath, StandardCharsets.UTF_8).replace("+", "%20");
        return "/api/files/download?path=" + encoded;
    }

    private String resolveMimeType(Path path, String requestedMimeType) {
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

    private String sanitizePathSegment(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (Character.isLetterOrDigit(current) || current == '-' || current == '_' || current == '.') {
                builder.append(current);
            } else {
                builder.append('_');
            }
        }
        String candidate = builder.toString()
                .replaceAll("_+", "_")
                .replaceAll("^[._-]+", "")
                .replaceAll("[._-]+$", "");
        if (candidate.isBlank()) {
            candidate = fallback;
        }
        if (candidate.length() > MAX_SEGMENT_LENGTH) {
            return candidate.substring(0, MAX_SEGMENT_LENGTH);
        }
        return candidate;
    }

    private String sanitizeFilename(String filename, String mimeType) {
        String leafName = filename == null ? "" : filename.trim().replace("\\", "/");
        int slashIndex = leafName.lastIndexOf('/');
        if (slashIndex >= 0) {
            leafName = leafName.substring(slashIndex + 1);
        }
        if (leafName.isBlank()) {
            leafName = "download";
        }

        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < leafName.length(); index++) {
            char current = leafName.charAt(index);
            if (Character.isLetterOrDigit(current) || current == '-' || current == '_' || current == '.') {
                builder.append(current);
            } else {
                builder.append('_');
            }
        }

        String sanitized = builder.toString()
                .replaceAll("_+", "_")
                .replaceAll("^[._-]+", "")
                .replaceAll("[_-]+$", "");
        if (sanitized.isBlank()) {
            sanitized = "download";
        }

        if (!sanitized.contains(".")) {
            String extension = defaultExtensionFromMimeType(mimeType);
            if (extension != null) {
                sanitized = sanitized + extension;
            }
        }

        if (sanitized.length() <= MAX_FILENAME_LENGTH) {
            return sanitized;
        }

        int dotIndex = sanitized.lastIndexOf('.');
        if (dotIndex <= 0) {
            return sanitized.substring(0, MAX_FILENAME_LENGTH);
        }
        String extension = sanitized.substring(dotIndex);
        int maxBaseLength = Math.max(1, MAX_FILENAME_LENGTH - extension.length());
        return sanitized.substring(0, maxBaseLength) + extension;
    }

    private String defaultExtensionFromMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return null;
        }
        return switch (mimeType) {
        case "image/png" -> ".png";
        case "image/jpeg" -> ".jpg";
        case "image/webp" -> ".webp";
        case "application/pdf" -> ".pdf";
        default -> null;
        };
    }

    private String requireFileName(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            throw new IllegalStateException("Path has no file name: " + path);
        }
        return fileName.toString();
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
