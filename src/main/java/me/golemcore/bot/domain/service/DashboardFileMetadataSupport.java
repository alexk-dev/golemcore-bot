package me.golemcore.bot.domain.service;

import me.golemcore.bot.port.outbound.WorkspaceFilePort;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared file metadata helpers for the dashboard IDE and workspace endpoints.
 */
final class DashboardFileMetadataSupport {

    private static final long MAX_EDITABLE_FILE_SIZE = 1024L * 1024L * 2L;
    private static final Set<String> DEFAULT_IGNORED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", "build", "dist", "node_modules", "target");
    private static final Set<String> GENERIC_BINARY_MIME_TYPES = Set.of(
            "application/macbinary", "application/octet-stream", "application/x-macbinary");
    private static final Map<String, String> TEXT_EXTENSION_MIME_TYPES = Map.ofEntries(
            Map.entry(".css", "text/css"),
            Map.entry(".go", "text/x-go"),
            Map.entry(".html", "text/html"),
            Map.entry(".ini", "text/plain"),
            Map.entry(".java", "text/x-java-source"),
            Map.entry(".js", "text/javascript"),
            Map.entry(".json", "application/json"),
            Map.entry(".jsx", "text/javascript"),
            Map.entry(".kt", "text/x-kotlin"),
            Map.entry(".md", "text/markdown"),
            Map.entry(".py", "text/x-python"),
            Map.entry(".rs", "text/x-rustsrc"),
            Map.entry(".scss", "text/x-scss"),
            Map.entry(".sh", "text/x-shellscript"),
            Map.entry(".sql", "application/sql"),
            Map.entry(".toml", "application/toml"),
            Map.entry(".ts", "text/typescript"),
            Map.entry(".tsx", "text/typescript"),
            Map.entry(".txt", "text/plain"),
            Map.entry(".xml", "application/xml"),
            Map.entry(".yaml", "application/yaml"),
            Map.entry(".yml", "application/yaml"));

    private DashboardFileMetadataSupport() {
    }

    /**
     * Returns whether a text-like file should be rejected for inline editing due to
     * its size.
     */
    static boolean shouldRejectEditableFileSize(
            WorkspacePathService workspacePathService,
            Path path,
            String mimeType,
            long size) {
        return size > MAX_EDITABLE_FILE_SIZE && isTextLikeFile(workspacePathService, path, mimeType);
    }

    /**
     * Returns whether the path is a directory hidden from the default IDE tree.
     */
    static boolean isIgnoredDirectory(
            WorkspaceFilePort workspaceFilePort,
            WorkspacePathService workspacePathService,
            Path path) {
        return workspaceFilePort.isDirectory(path)
                && DEFAULT_IGNORED_DIRECTORIES.contains(requireFileName(workspacePathService, path));
    }

    /**
     * Returns whether the file can be opened in the inline dashboard editor.
     */
    static boolean isEditableFile(
            WorkspacePathService workspacePathService,
            Path path,
            String mimeType,
            long size) {
        if (size > MAX_EDITABLE_FILE_SIZE || isImageMimeType(mimeType)) {
            return false;
        }
        return isTextLikeFile(workspacePathService, path, mimeType);
    }

    /**
     * Returns whether the resolved mime type represents an image asset.
     */
    static boolean isImageMimeType(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    /**
     * Normalizes a user-provided filename down to a safe leaf name.
     */
    static String sanitizeFilename(String filename) {
        String normalized = filename.trim().replace("\\", "/");
        int slashIndex = normalized.lastIndexOf('/');
        String leafName = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        if (leafName.isBlank() || ".".equals(leafName) || "..".equals(leafName)) {
            throw new IllegalArgumentException("Filename is required");
        }
        return leafName;
    }

    /**
     * Builds the protected dashboard download URL for a workspace-relative path.
     */
    static String buildDownloadUrl(String relativePath) {
        String encoded = URLEncoder.encode(relativePath, StandardCharsets.UTF_8).replace("+", "%20");
        return "/api/files/download?path=" + encoded;
    }

    /**
     * Resolves a stable mime type for dashboard rendering, correcting generic OS
     * binary probes when the file extension clearly points to a text format.
     */
    static String resolveMimeType(
            WorkspacePathService workspacePathService,
            Path path,
            String requestedMimeType) {
        String mimeType = workspacePathService.resolveMimeType(path, requestedMimeType);
        String filename = requireFileName(workspacePathService, path);
        if (GENERIC_BINARY_MIME_TYPES.contains(mimeType)) {
            // Prefer a known text mime type when the OS reports a generic binary value.
            return findTextMimeType(filename, "application/octet-stream");
        }
        return mimeType;
    }

    private static boolean isTextLikeFile(WorkspacePathService workspacePathService, Path path, String mimeType) {
        if (mimeType != null && mimeType.startsWith("text/")) {
            return true;
        }
        String filename = requireFileName(workspacePathService, path);
        return TEXT_EXTENSION_MIME_TYPES.keySet().stream().anyMatch(filename::endsWith);
    }

    private static String findTextMimeType(String filename, String fallbackMimeType) {
        return TEXT_EXTENSION_MIME_TYPES.entrySet().stream()
                .filter(entry -> filename.endsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(fallbackMimeType);
    }

    private static String requireFileName(WorkspacePathService workspacePathService, Path path) {
        return workspacePathService.requireFileName(path).toLowerCase(Locale.ROOT);
    }
}
