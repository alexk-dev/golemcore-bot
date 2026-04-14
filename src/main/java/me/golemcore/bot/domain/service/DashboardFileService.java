package me.golemcore.bot.domain.service;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.DashboardFileContent;
import me.golemcore.bot.domain.model.DashboardFileNode;
import me.golemcore.bot.domain.model.ToolArtifactDownload;
import me.golemcore.bot.port.outbound.WorkspaceFilePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DashboardFileService {

    private static final long MAX_EDITABLE_FILE_SIZE = 1024L * 1024L * 2L;
    private static final int DEFAULT_TREE_DEPTH = Integer.MAX_VALUE;
    private static final Set<String> DEFAULT_IGNORED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", "build", "dist", "node_modules", "target");

    private final WorkspacePathService workspacePathService;
    private final WorkspaceFilePort workspaceFilePort;

    public List<DashboardFileNode> getTree(String relativePath) {
        return getTree(relativePath, DEFAULT_TREE_DEPTH, true);
    }

    public List<DashboardFileNode> getTree(String relativePath, int depth, boolean includeIgnored) {
        Path base = resolveSafePath(relativePath == null ? "" : relativePath);
        if (!workspaceFilePort.exists(base)) {
            throw new IllegalArgumentException("Path not found: " + relativePath);
        }
        if (!workspaceFilePort.isDirectory(base)) {
            throw new IllegalArgumentException("Path is not a directory: " + relativePath);
        }

        int effectiveDepth = Math.max(1, depth);
        return buildChildren(base, effectiveDepth, includeIgnored);
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
            String mimeType = resolveMimeType(path, null);
            boolean image = isImageMimeType(mimeType);
            if (size > MAX_EDITABLE_FILE_SIZE && isTextLikeFile(path, mimeType)) {
                throw new IllegalArgumentException("File too large for editor (max 2 MB)");
            }
            boolean editable = isEditableFile(path, mimeType, size);
            String updatedAt = workspaceFilePort.getLastModifiedTime(path);
            if (!editable) {
                return buildContent(path, null, size, updatedAt, mimeType, true, image, false);
            }

            String content = workspaceFilePort.readString(path);
            return buildContent(path, content, size, updatedAt, mimeType, false, false, true);
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
            workspaceFilePort.write(path, value.getBytes(StandardCharsets.UTF_8));
            return buildSavedTextContent(path, value);
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
            return buildSavedTextContent(path, content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save file", e);
        }
    }

    public DashboardFileContent uploadFile(String targetDirectory, String filename, byte[] data, String mimeType) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename is required");
        }
        if (data == null) {
            throw new IllegalArgumentException("File data is required");
        }

        String safeFilename = sanitizeFilename(filename);
        String basePath = targetDirectory == null ? "" : targetDirectory.trim();
        String relativePath = basePath.isBlank() ? safeFilename : basePath + "/" + safeFilename;
        Path path = resolveSafePath(relativePath);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                workspaceFilePort.createDirectories(parent);
            }
            workspaceFilePort.write(path, data);
            String resolvedMimeType = resolveMimeType(path, mimeType);
            boolean image = isImageMimeType(resolvedMimeType);
            boolean editable = isEditableFile(path, resolvedMimeType, data.length);
            String content = editable ? new String(data, StandardCharsets.UTF_8) : null;
            return buildContent(path, content, data.length, Instant.now().toString(), resolvedMimeType,
                    !editable, image, editable);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to upload file", e);
        }
    }

    public ToolArtifactDownload getDownload(String relativePath) {
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
            byte[] data = workspaceFilePort.readAllBytes(path);
            return ToolArtifactDownload.builder()
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

    private DashboardFileContent buildSavedTextContent(Path path, String content) throws IOException {
        long size = workspaceFilePort.size(path);
        String mimeType = resolveMimeType(path, null);
        return buildContent(path, content, size, Instant.now().toString(), mimeType, false, false, true);
    }

    private DashboardFileContent buildContent(
            Path path,
            String content,
            long size,
            String updatedAt,
            String mimeType,
            boolean binary,
            boolean image,
            boolean editable) {
        return DashboardFileContent.builder()
                .path(toRelativePath(path))
                .content(content)
                .size(size)
                .updatedAt(updatedAt)
                .mimeType(mimeType)
                .binary(binary)
                .image(image)
                .editable(editable)
                .downloadUrl(buildDownloadUrl(toRelativePath(path)))
                .build();
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

    private List<DashboardFileNode> buildChildren(Path dir, int depth, boolean includeIgnored) {
        try {
            List<Path> sorted = workspaceFilePort.list(dir).stream()
                    .filter(path -> !workspaceFilePort.isSymbolicLink(path))
                    .filter(path -> includeIgnored || !isIgnoredDirectory(path))
                    .sorted(Comparator
                            .comparing((Path p) -> !workspaceFilePort.isDirectory(p))
                            .thenComparing(p -> requireFileName(p).toLowerCase(Locale.ROOT)))
                    .toList();

            List<DashboardFileNode> nodes = new ArrayList<>();
            for (Path path : sorted) {
                if (workspaceFilePort.isDirectory(path)) {
                    nodes.add(buildDirectoryNode(path, depth, includeIgnored));
                } else if (workspaceFilePort.isRegularFile(path)) {
                    nodes.add(buildFileNode(path));
                }
            }

            return nodes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list directory", e);
        }
    }

    private DashboardFileNode buildDirectoryNode(Path path, int depth, boolean includeIgnored) throws IOException {
        boolean hasChildren = hasVisibleChildren(path, includeIgnored);
        List<DashboardFileNode> children = depth > 1 ? buildChildren(path, depth - 1, includeIgnored) : List.of();
        return DashboardFileNode.builder()
                .path(toRelativePath(path))
                .name(requireFileName(path))
                .type("directory")
                .updatedAt(workspaceFilePort.getLastModifiedTime(path))
                .editable(false)
                .hasChildren(hasChildren)
                .children(children)
                .build();
    }

    private DashboardFileNode buildFileNode(Path path) throws IOException {
        long size = workspaceFilePort.size(path);
        String mimeType = resolveMimeType(path, null);
        boolean image = isImageMimeType(mimeType);
        boolean editable = isEditableFile(path, mimeType, size);
        return DashboardFileNode.builder()
                .path(toRelativePath(path))
                .name(requireFileName(path))
                .type("file")
                .size(size)
                .mimeType(mimeType)
                .updatedAt(workspaceFilePort.getLastModifiedTime(path))
                .binary(!editable)
                .image(image)
                .editable(editable)
                .hasChildren(false)
                .children(List.of())
                .build();
    }

    private boolean hasVisibleChildren(Path directory, boolean includeIgnored) throws IOException {
        return workspaceFilePort.list(directory).stream()
                .filter(path -> !workspaceFilePort.isSymbolicLink(path))
                .anyMatch(path -> includeIgnored || !isIgnoredDirectory(path));
    }

    private boolean isIgnoredDirectory(Path path) {
        return workspaceFilePort.isDirectory(path)
                && DEFAULT_IGNORED_DIRECTORIES.contains(requireFileName(path).toLowerCase(Locale.ROOT));
    }

    private boolean isEditableFile(Path path, String mimeType, long size) {
        if (size > MAX_EDITABLE_FILE_SIZE || isImageMimeType(mimeType)) {
            return false;
        }
        return isTextLikeFile(path, mimeType);
    }

    private boolean isTextLikeFile(Path path, String mimeType) {
        if (mimeType != null && mimeType.startsWith("text/")) {
            return true;
        }
        String filename = requireFileName(path).toLowerCase(Locale.ROOT);
        return filename.endsWith(".java")
                || filename.endsWith(".js")
                || filename.endsWith(".jsx")
                || filename.endsWith(".ts")
                || filename.endsWith(".tsx")
                || filename.endsWith(".json")
                || filename.endsWith(".md")
                || filename.endsWith(".yml")
                || filename.endsWith(".yaml")
                || filename.endsWith(".xml")
                || filename.endsWith(".html")
                || filename.endsWith(".css")
                || filename.endsWith(".scss")
                || filename.endsWith(".sh")
                || filename.endsWith(".py")
                || filename.endsWith(".go")
                || filename.endsWith(".rs")
                || filename.endsWith(".kt")
                || filename.endsWith(".sql")
                || filename.endsWith(".toml")
                || filename.endsWith(".ini")
                || filename.endsWith(".txt");
    }

    private boolean isImageMimeType(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    private String sanitizeFilename(String filename) {
        String normalized = filename.trim().replace("\\", "/");
        int slashIndex = normalized.lastIndexOf('/');
        String leafName = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        if (leafName.isBlank() || leafName.equals(".") || leafName.equals("..")) {
            throw new IllegalArgumentException("Filename is required");
        }
        return leafName;
    }

    private String buildDownloadUrl(String relativePath) {
        String encoded = URLEncoder.encode(relativePath, StandardCharsets.UTF_8).replace("+", "%20");
        return "/api/files/download?path=" + encoded;
    }

    private String resolveMimeType(Path path, String requestedMimeType) {
        String mimeType = workspacePathService.resolveMimeType(path, requestedMimeType);
        String filename = requireFileName(path).toLowerCase(Locale.ROOT);
        if ("application/octet-stream".equals(mimeType)) {
            if (filename.endsWith(".md")) {
                return "text/markdown";
            }
            if (filename.endsWith(".ts") || filename.endsWith(".tsx")) {
                return "text/typescript";
            }
            if (filename.endsWith(".js") || filename.endsWith(".jsx")) {
                return "text/javascript";
            }
            if (filename.endsWith(".java")) {
                return "text/x-java-source";
            }
        }
        return mimeType;
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
