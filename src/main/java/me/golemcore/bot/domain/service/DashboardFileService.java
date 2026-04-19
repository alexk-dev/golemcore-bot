package me.golemcore.bot.domain.service;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.DashboardFileContent;
import me.golemcore.bot.domain.model.DashboardFileNode;
import me.golemcore.bot.domain.model.ToolArtifactDownload;
import me.golemcore.bot.port.outbound.WorkspaceFilePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DashboardFileService {

    private static final int DEFAULT_TREE_DEPTH = Integer.MAX_VALUE;

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
            String mimeType = DashboardFileMetadataSupport.resolveMimeType(workspacePathService, path, null);
            boolean image = DashboardFileMetadataSupport.isImageMimeType(mimeType);
            if (DashboardFileMetadataSupport.shouldRejectEditableFileSize(workspacePathService, path, mimeType, size)) {
                throw new IllegalArgumentException("File too large for editor (max 2 MB)");
            }
            boolean editable = DashboardFileMetadataSupport.isEditableFile(workspacePathService, path, mimeType, size);
            String updatedAt = workspaceFilePort.getLastModifiedTime(path);
            if (!editable) {
                return buildContent(path, size, updatedAt, mimeType, null, true, image, false);
            }

            String content = workspaceFilePort.readString(path);
            return buildContent(path, size, updatedAt, mimeType, content, false, false, true);
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

        String safeFilename = DashboardFileMetadataSupport.sanitizeFilename(filename);
        Path targetDirectoryPath = resolveUploadDirectory(targetDirectory);
        Path path = targetDirectoryPath.resolve(safeFilename).normalize();
        if (!path.startsWith(getWorkspaceRoot())) {
            throw new IllegalArgumentException("Path must be inside workspace");
        }

        String resolvedMimeType = DashboardFileMetadataSupport.resolveMimeType(workspacePathService, path, mimeType);
        boolean image = DashboardFileMetadataSupport.isImageMimeType(resolvedMimeType);
        boolean editable = DashboardFileMetadataSupport.isEditableFile(workspacePathService, path, resolvedMimeType,
                data.length);
        String content = editable ? decodeUtf8(data) : null;

        try {
            Path parent = path.getParent();
            if (parent != null) {
                workspaceFilePort.createDirectories(parent);
            }
            workspaceFilePort.write(path, data);
            return buildContent(path, data.length, Instant.now().toString(), resolvedMimeType, content, !editable,
                    image, editable);
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
                    .mimeType(DashboardFileMetadataSupport.resolveMimeType(workspacePathService, path, null))
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

    public void validateEditablePath(String relativePath) {
        DashboardFileContent content = getContent(relativePath);
        if (!content.isEditable()) {
            throw new IllegalArgumentException("File is not editable: " + relativePath);
        }
    }

    private DashboardFileContent buildSavedTextContent(Path path, String content) throws IOException {
        long size = workspaceFilePort.size(path);
        String mimeType = DashboardFileMetadataSupport.resolveMimeType(workspacePathService, path, null);
        return buildContent(path, size, Instant.now().toString(), mimeType, content, false, false, true);
    }

    private String decodeUtf8(byte[] data) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("File is not valid UTF-8 text");
        }
    }

    private DashboardFileContent buildContent(
            Path path,
            long size,
            String updatedAt,
            String mimeType,
            String content,
            boolean binary,
            boolean image,
            boolean editable) {
        String relativePath = toRelativePath(path);
        return DashboardFileContent.builder()
                .path(relativePath)
                .content(content)
                .size(size)
                .updatedAt(updatedAt)
                .mimeType(mimeType)
                .binary(binary)
                .image(image)
                .editable(editable)
                .downloadUrl(DashboardFileMetadataSupport.buildDownloadUrl(relativePath))
                .build();
    }

    private Path resolveUploadDirectory(String targetDirectory) {
        String basePath = targetDirectory == null ? "" : targetDirectory.trim();
        return resolveSafePath(basePath);
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
                    .filter(path -> includeIgnored || !DashboardFileMetadataSupport
                            .isIgnoredDirectory(workspaceFilePort, workspacePathService, path))
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
        String mimeType = DashboardFileMetadataSupport.resolveMimeType(workspacePathService, path, null);
        boolean image = DashboardFileMetadataSupport.isImageMimeType(mimeType);
        boolean editable = DashboardFileMetadataSupport.isEditableFile(workspacePathService, path, mimeType, size);
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
                .anyMatch(path -> includeIgnored || !DashboardFileMetadataSupport.isIgnoredDirectory(workspaceFilePort,
                        workspacePathService, path));
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
