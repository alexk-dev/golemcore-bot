package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.ToolArtifact;
import me.golemcore.bot.domain.model.ToolArtifactDownload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ToolArtifactService {

    private static final String TOOL_ARTIFACTS_DIR = ".golemcore/tool-artifacts";
    private static final int MAX_FILENAME_LENGTH = 120;
    private static final int MAX_SEGMENT_LENGTH = 64;

    private final WorkspacePathService workspacePathService;

    public ToolArtifact saveArtifact(
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
        Path path = workspacePathService.resolveSafePath(relativePath);

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

            String storedPath = workspacePathService.toRelativePath(path);
            String resolvedMimeType = workspacePathService.resolveMimeType(path, mimeType);
            return ToolArtifact.builder()
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

    public ToolArtifactDownload getDownload(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Path is required");
        }

        Path path = workspacePathService.resolveSafePath(relativePath);
        if (!path.startsWith(getArtifactsRoot())) {
            throw new IllegalArgumentException("Path is not a tool artifact: " + relativePath);
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: " + relativePath);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Not a file: " + relativePath);
        }

        try {
            byte[] data = Files.readAllBytes(path);
            return ToolArtifactDownload.builder()
                    .path(workspacePathService.toRelativePath(path))
                    .filename(workspacePathService.requireFileName(path))
                    .mimeType(workspacePathService.resolveMimeType(path, null))
                    .size(data.length)
                    .data(data)
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read download file", e);
        }
    }

    private Path getArtifactsRoot() {
        return workspacePathService.resolveSafePath(TOOL_ARTIFACTS_DIR);
    }

    private String buildDownloadUrl(String relativePath) {
        String encoded = URLEncoder.encode(relativePath, StandardCharsets.UTF_8).replace("+", "%20");
        return "/api/files/download?path=" + encoded;
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
}
