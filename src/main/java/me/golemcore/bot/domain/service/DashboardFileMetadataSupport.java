package me.golemcore.bot.domain.service;

import me.golemcore.bot.port.outbound.WorkspaceFilePort;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class DashboardFileMetadataSupport {

    private static final long MAX_EDITABLE_FILE_SIZE = 1024L * 1024L * 2L;
    private static final Set<String> DEFAULT_IGNORED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", "build", "dist", "node_modules", "target");
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

    static boolean shouldRejectEditableFileSize(
            WorkspacePathService workspacePathService,
            Path path,
            String mimeType,
            long size) {
        return size > MAX_EDITABLE_FILE_SIZE && isTextLikeFile(workspacePathService, path, mimeType);
    }

    static boolean isIgnoredDirectory(
            WorkspaceFilePort workspaceFilePort,
            WorkspacePathService workspacePathService,
            Path path) {
        return workspaceFilePort.isDirectory(path)
                && DEFAULT_IGNORED_DIRECTORIES.contains(requireFileName(workspacePathService, path));
    }

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

    static boolean isImageMimeType(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    static String sanitizeFilename(String filename) {
        String normalized = filename.trim().replace("\\", "/");
        int slashIndex = normalized.lastIndexOf('/');
        String leafName = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        if (leafName.isBlank() || ".".equals(leafName) || "..".equals(leafName)) {
            throw new IllegalArgumentException("Filename is required");
        }
        return leafName;
    }

    static String buildDownloadUrl(String relativePath) {
        String encoded = URLEncoder.encode(relativePath, StandardCharsets.UTF_8).replace("+", "%20");
        return "/api/files/download?path=" + encoded;
    }

    static String resolveMimeType(
            WorkspacePathService workspacePathService,
            Path path,
            String requestedMimeType) {
        String mimeType = workspacePathService.resolveMimeType(path, requestedMimeType);
        String filename = requireFileName(workspacePathService, path);
        if ("application/octet-stream".equals(mimeType)) {
            return findTextMimeType(filename, mimeType);
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
