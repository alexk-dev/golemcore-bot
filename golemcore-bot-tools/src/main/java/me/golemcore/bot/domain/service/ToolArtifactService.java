package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.ToolArtifact;
import me.golemcore.bot.domain.model.ToolArtifactDownload;
import me.golemcore.bot.port.outbound.ToolArtifactReadPort;
import me.golemcore.bot.port.outbound.WorkspaceFilePort;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

@Service
public class ToolArtifactService implements ToolArtifactReadPort {

    private static final String TOOL_ARTIFACTS_DIR = ".golemcore/tool-artifacts";
    private static final int MAX_FILENAME_LENGTH = 120;
    private static final int MAX_SEGMENT_LENGTH = 64;
    private static final int DEFAULT_THUMBNAIL_MAX_DIMENSION = 240;
    private static final String THUMBNAIL_MIME_TYPE = "image/png";

    private final WorkspacePathService workspacePathService;
    private final WorkspaceFilePort workspaceFilePort;

    public ToolArtifactService(WorkspacePathService workspacePathService, WorkspaceFilePort workspaceFilePort) {
        this.workspacePathService = workspacePathService;
        this.workspaceFilePort = workspaceFilePort;
    }

    public ToolArtifact saveArtifact(String sessionId, String toolName, String filename, byte[] data, String mimeType) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("File data is required");
        }

        String safeSessionId = sanitizePathSegment(sessionId, "session");
        String safeToolName = sanitizePathSegment(toolName, "tool");
        String safeFilename = sanitizeFilename(filename, mimeType);
        String artifactId = Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String relativePath = TOOL_ARTIFACTS_DIR + "/" + safeSessionId + "/" + safeToolName + "/" + artifactId + "/"
                + safeFilename;
        Path path = workspacePathService.resolveSafePath(relativePath);

        try {
            Path parent = path.getParent();
            if (parent != null) {
                workspaceFilePort.createDirectories(parent);
            }
            workspaceFilePort.write(path, data);

            String storedPath = workspacePathService.toRelativePath(path);
            String resolvedMimeType = workspacePathService.resolveMimeType(path, mimeType);
            return ToolArtifact.builder().path(storedPath).filename(safeFilename).mimeType(resolvedMimeType)
                    .size(data.length).downloadUrl(buildDownloadUrl(storedPath)).build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store tool artifact", e);
        }
    }

    @Override
    public ToolArtifactDownload getDownload(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Path is required");
        }

        Path path = workspacePathService.resolveSafePath(relativePath);
        if (!path.startsWith(getArtifactsRoot())) {
            throw new IllegalArgumentException("Path is not a tool artifact: " + relativePath);
        }
        if (!workspaceFilePort.exists(path)) {
            throw new IllegalArgumentException("File not found: " + relativePath);
        }
        if (!workspaceFilePort.isRegularFile(path)) {
            throw new IllegalArgumentException("Not a file: " + relativePath);
        }

        try {
            byte[] data = workspaceFilePort.readAllBytes(path);
            return ToolArtifactDownload.builder().path(workspacePathService.toRelativePath(path))
                    .filename(workspacePathService.requireFileName(path))
                    .mimeType(workspacePathService.resolveMimeType(path, null)).size(data.length).data(data).build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read download file", e);
        }
    }

    public String buildThumbnailBase64(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        ToolArtifactDownload download = getDownload(relativePath);
        return buildThumbnailBase64(download.getData(), download.getMimeType(), DEFAULT_THUMBNAIL_MAX_DIMENSION);
    }

    public String buildThumbnailBase64(byte[] data, String mimeType, int maxDimension) {
        if (data == null || data.length == 0 || mimeType == null || !mimeType.startsWith("image/")) {
            return null;
        }

        int effectiveMaxDimension = maxDimension > 0 ? maxDimension : DEFAULT_THUMBNAIL_MAX_DIMENSION;
        try (ByteArrayInputStream input = new ByteArrayInputStream(data);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            BufferedImage source = ImageIO.read(input);
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return null;
            }

            BufferedImage thumbnail = resizeToFit(source, effectiveMaxDimension);
            if (!ImageIO.write(thumbnail, "png", output)) {
                return null;
            }
            return java.util.Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException ignored) {
            return null;
        }
    }

    public String getThumbnailMimeType() {
        return THUMBNAIL_MIME_TYPE;
    }

    private Path getArtifactsRoot() {
        return workspacePathService.resolveSafePath(TOOL_ARTIFACTS_DIR);
    }

    private String buildDownloadUrl(String relativePath) {
        String encoded = URLEncoder.encode(relativePath, StandardCharsets.UTF_8).replace("+", "%20");
        return "/api/files/download?path=" + encoded;
    }

    private BufferedImage resizeToFit(BufferedImage source, int maxDimension) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int longestSide = Math.max(sourceWidth, sourceHeight);
        if (longestSide <= maxDimension) {
            BufferedImage copy = new BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = copy.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            return copy;
        }

        double scale = (double) maxDimension / longestSide;
        int targetWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
        int targetHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
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
        String candidate = normalizeSanitizedValue(builder, true);
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

        String sanitized = normalizeSanitizedValue(builder, false);
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

    private String normalizeSanitizedValue(CharSequence value, boolean trimTrailingDot) {
        StringBuilder collapsed = new StringBuilder(value.length());
        boolean previousUnderscore = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '_') {
                if (!previousUnderscore) {
                    collapsed.append(current);
                    previousUnderscore = true;
                }
                continue;
            }
            collapsed.append(current);
            previousUnderscore = false;
        }

        int start = 0;
        while (start < collapsed.length() && isLeadingTrimCharacter(collapsed.charAt(start))) {
            start++;
        }

        int end = collapsed.length();
        while (end > start && isTrailingTrimCharacter(collapsed.charAt(end - 1), trimTrailingDot)) {
            end--;
        }
        return collapsed.substring(start, end);
    }

    private boolean isLeadingTrimCharacter(char current) {
        return current == '.' || current == '_' || current == '-';
    }

    private boolean isTrailingTrimCharacter(char current, boolean trimTrailingDot) {
        return current == '_' || current == '-' || (trimTrailingDot && current == '.');
    }
}
