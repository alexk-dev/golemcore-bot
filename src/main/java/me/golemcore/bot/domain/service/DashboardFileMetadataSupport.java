package me.golemcore.bot.domain.service;

import me.golemcore.bot.port.outbound.WorkspaceFilePort;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

final class DashboardFileMetadataSupport {

    private static final long MAX_EDITABLE_FILE_SIZE = 1024L * 1024L * 2L;
    private static final Set<String> DEFAULT_IGNORED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", "build", "dist", "node_modules", "target");

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
        if (leafName.isBlank() || leafName.equals(".") || leafName.equals("..")) {
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

    private static boolean isTextLikeFile(WorkspacePathService workspacePathService, Path path, String mimeType) {
        if (mimeType != null && mimeType.startsWith("text/")) {
            return true;
        }
        String filename = requireFileName(workspacePathService, path);
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

    private static String requireFileName(WorkspacePathService workspacePathService, Path path) {
        return workspacePathService.requireFileName(path).toLowerCase(Locale.ROOT);
    }
}
