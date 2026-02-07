/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.security.InjectionGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tool for file system operations within a sandboxed workspace.
 *
 * <p>
 * All paths are resolved relative to the workspace root. Path traversal attacks
 * are blocked by {@link InjectionGuard}.
 *
 * <p>
 * Operations:
 * <ul>
 * <li>read_file - Read text file content (max 10MB)
 * <li>write_file - Write text content to file
 * <li>list_directory - List files and directories
 * <li>create_directory - Create directory
 * <li>delete - Delete file or directory
 * <li>file_info - Get file metadata (size, modified time, etc.)
 * <li>send_file - Send file to user (images/documents up to 50MB)
 * </ul>
 *
 * <p>
 * Security:
 * <ul>
 * <li>All operations sandboxed to workspace directory
 * <li>Path traversal blocked (../, absolute paths)
 * <li>File size limits (10MB read, 50MB send)
 * <li>List operations limited to 100 files
 * </ul>
 *
 * <p>
 * Configuration: {@code bot.tools.filesystem.workspace}
 *
 * @see InjectionGuard
 */
@Component
@Slf4j
public class FileSystemTool implements ToolComponent {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final long MAX_SEND_FILE_SIZE = 50 * 1024 * 1024; // 50 MB (Telegram limit)
    private static final int MAX_FILES_LIST = 100;

    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("csv", "text/csv"),
            Map.entry("txt", "text/plain"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("zip", "application/zip"),
            Map.entry("tar", "application/x-tar"),
            Map.entry("gz", "application/gzip"),
            Map.entry("html", "text/html"),
            Map.entry("md", "text/markdown"),
            Map.entry("py", "text/x-python"),
            Map.entry("java", "text/x-java"),
            Map.entry("js", "text/javascript"),
            Map.entry("ts", "text/typescript"),
            Map.entry("yaml", "text/yaml"),
            Map.entry("yml", "text/yaml"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("wav", "audio/wav"));

    private final Path workspaceRoot;
    private final InjectionGuard injectionGuard;
    private final boolean enabled;

    public FileSystemTool(BotProperties properties, InjectionGuard injectionGuard) {
        var config = properties.getTools().getFilesystem();
        this.enabled = config.isEnabled();
        this.workspaceRoot = Paths.get(config.getWorkspace()).toAbsolutePath().normalize();
        this.injectionGuard = injectionGuard;

        // Ensure workspace exists
        try {
            Files.createDirectories(workspaceRoot);
            log.info("FileSystemTool workspace: {}, enabled: {}", workspaceRoot, enabled);
        } catch (IOException e) {
            log.error("Failed to create workspace directory: {}", workspaceRoot, e);
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("filesystem")
                .description(
                        """
                                File system operations in the workspace directory.
                                Operations: read_file, write_file, list_directory, create_directory, delete, file_info, send_file.
                                send_file sends a file to the user (images and documents up to 50MB).
                                All paths are relative to the workspace root.
                                """)
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "operation", Map.of(
                                        "type", "string",
                                        "enum",
                                        List.of("read_file", "write_file", "list_directory", "create_directory",
                                                "delete", "file_info", "send_file"),
                                        "description", "Operation to perform"),
                                "path", Map.of(
                                        "type", "string",
                                        "description", "File or directory path (relative to workspace)"),
                                "content", Map.of(
                                        "type", "string",
                                        "description", "Content to write (for write_file operation)"),
                                "append", Map.of(
                                        "type", "boolean",
                                        "description",
                                        "Append to file instead of overwriting (for write_file, default: false)")),
                        "required", List.of("operation", "path")))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("[FileSystem] Execute called with parameters: {}", parameters);

            if (!enabled) {
                log.warn("[FileSystem] Tool is DISABLED");
                return ToolResult.failure("FileSystem tool is disabled");
            }

            try {
                String operation = (String) parameters.get("operation");
                String pathStr = (String) parameters.get("path");
                log.info("[FileSystem] Operation: {}, Path: {}", operation, pathStr);

                if (operation == null || pathStr == null) {
                    log.warn("[FileSystem] Missing required parameters");
                    return ToolResult.failure("Missing required parameters: operation and path");
                }

                // Security check for path traversal
                if (injectionGuard.detectPathTraversal(pathStr)) {
                    log.warn("[FileSystem] Path traversal attempt BLOCKED: {}", pathStr);
                    return ToolResult.failure("Invalid path: path traversal not allowed");
                }

                // Resolve and validate path is within workspace
                Path resolvedPath = resolveSafePath(pathStr);
                if (resolvedPath == null) {
                    log.warn("[FileSystem] Path resolution failed (outside workspace): {}", pathStr);
                    return ToolResult.failure("Invalid path: must be within workspace");
                }
                log.debug("[FileSystem] Resolved path: {}", resolvedPath);

                ToolResult result = switch (operation) {
                case "read_file" -> readFile(resolvedPath);
                case "write_file" -> writeFile(resolvedPath, parameters);
                case "list_directory" -> listDirectory(resolvedPath);
                case "create_directory" -> createDirectory(resolvedPath);
                case "delete" -> delete(resolvedPath);
                case "file_info" -> fileInfo(resolvedPath);
                case "send_file" -> sendFile(resolvedPath);
                default -> ToolResult.failure("Unknown operation: " + operation);
                };

                log.info("[FileSystem] Operation '{}' result: success={}", operation, result.isSuccess());
                return result;

            } catch (Exception e) {
                log.error("[FileSystem] ERROR: {}", e.getMessage(), e);
                return ToolResult.failure("Error: " + e.getMessage());
            }
        });
    }

    private Path resolveSafePath(String pathStr) {
        try {
            // Normalize and resolve relative to workspace
            Path resolved = workspaceRoot.resolve(pathStr).normalize();

            // Ensure path is still within workspace (logical check)
            if (!resolved.startsWith(workspaceRoot)) {
                return null;
            }

            // Follow symlinks to prevent symlink escape attacks
            if (Files.exists(resolved)) {
                Path realPath = resolved.toRealPath();
                Path realWorkspace = workspaceRoot.toRealPath();
                if (!realPath.startsWith(realWorkspace)) {
                    log.warn("[FileSystem] Symlink escape blocked: {} -> {}", resolved, realPath);
                    return null;
                }
            }

            return resolved;
        } catch (InvalidPathException e) {
            return null;
        } catch (IOException e) {
            log.warn("[FileSystem] Failed to resolve real path: {}", pathStr);
            return null;
        }
    }

    private ToolResult readFile(Path path) {
        if (!Files.exists(path)) {
            return ToolResult.failure("File not found: " + relativePath(path));
        }

        if (!Files.isRegularFile(path)) {
            return ToolResult.failure("Not a file: " + relativePath(path));
        }

        try {
            long size = Files.size(path);
            if (size > MAX_FILE_SIZE) {
                return ToolResult.failure("File too large (max " + (MAX_FILE_SIZE / 1024 / 1024) + " MB)");
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            return ToolResult.success(content, Map.of(
                    "path", relativePath(path),
                    "size", size,
                    "lines", content.lines().count()));

        } catch (IOException e) {
            return ToolResult.failure("Failed to read file: " + e.getMessage());
        }
    }

    private ToolResult writeFile(Path path, Map<String, Object> params) {
        String content = (String) params.get("content");
        if (content == null) {
            return ToolResult.failure("Missing content for write_file operation");
        }

        Boolean append = (Boolean) params.get("append");
        boolean shouldAppend = append != null && append;

        try {
            // Ensure parent directory exists
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            if (shouldAppend) {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, content, StandardCharsets.UTF_8);
            }

            long size = Files.size(path);
            String action = shouldAppend ? "appended to" : "written to";

            return ToolResult.success("Successfully " + action + " file: " + relativePath(path), Map.of(
                    "path", relativePath(path),
                    "size", size,
                    "operation", shouldAppend ? "append" : "write"));

        } catch (IOException e) {
            return ToolResult.failure("Failed to write file: " + e.getMessage());
        }
    }

    private ToolResult listDirectory(Path path) {
        if (!Files.exists(path)) {
            return ToolResult.failure("Directory not found: " + relativePath(path));
        }

        if (!Files.isDirectory(path)) {
            return ToolResult.failure("Not a directory: " + relativePath(path));
        }

        try (Stream<Path> stream = Files.list(path)) {
            List<Map<String, Object>> entries = stream
                    .limit(MAX_FILES_LIST)
                    .map(p -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                            return Map.<String, Object>of(
                                    "name", p.getFileName().toString(),
                                    "type", attrs.isDirectory() ? "directory" : "file",
                                    "size", attrs.size(),
                                    "modified", attrs.lastModifiedTime().toString());
                        } catch (IOException e) {
                            return Map.<String, Object>of(
                                    "name", p.getFileName().toString(),
                                    "type", "unknown");
                        }
                    })
                    .collect(Collectors.toList());

            StringBuilder sb = new StringBuilder();
            sb.append("Directory: ").append(relativePath(path)).append("\n");
            sb.append("Entries: ").append(entries.size()).append("\n\n");

            for (Map<String, Object> entry : entries) {
                String type = (String) entry.get("type");
                String name = (String) entry.get("name");
                if ("directory".equals(type)) {
                    sb.append("[DIR]  ").append(name).append("/\n");
                } else {
                    long size = (long) entry.get("size");
                    sb.append("[FILE] ").append(name).append(" (").append(formatSize(size)).append(")\n");
                }
            }

            return ToolResult.success(sb.toString(), Map.of(
                    "path", relativePath(path),
                    "entries", entries));

        } catch (IOException e) {
            return ToolResult.failure("Failed to list directory: " + e.getMessage());
        }
    }

    private ToolResult createDirectory(Path path) {
        try {
            if (Files.exists(path)) {
                if (Files.isDirectory(path)) {
                    return ToolResult.success("Directory already exists: " + relativePath(path));
                } else {
                    return ToolResult.failure("Path exists but is not a directory: " + relativePath(path));
                }
            }

            Files.createDirectories(path);
            return ToolResult.success("Created directory: " + relativePath(path), Map.of(
                    "path", relativePath(path)));

        } catch (IOException e) {
            return ToolResult.failure("Failed to create directory: " + e.getMessage());
        }
    }

    private ToolResult delete(Path path) {
        if (!Files.exists(path)) {
            return ToolResult.failure("Path not found: " + relativePath(path));
        }

        try {
            if (Files.isDirectory(path)) {
                // Delete directory recursively
                Files.walk(path)
                        .sorted(java.util.Comparator.reverseOrder()) // Reverse order for depth-first
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                            }
                        });
            } else {
                Files.delete(path);
            }

            return ToolResult.success("Deleted: " + relativePath(path));

        } catch (IOException e) {
            return ToolResult.failure("Failed to delete: " + e.getMessage());
        }
    }

    private ToolResult fileInfo(Path path) {
        if (!Files.exists(path)) {
            return ToolResult.failure("Path not found: " + relativePath(path));
        }

        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);

            Map<String, Object> info = Map.of(
                    "path", relativePath(path),
                    "absolutePath", path.toString(),
                    "type", attrs.isDirectory() ? "directory" : "file",
                    "size", attrs.size(),
                    "sizeFormatted", formatSize(attrs.size()),
                    "created", attrs.creationTime().toString(),
                    "modified", attrs.lastModifiedTime().toString(),
                    "readable", Files.isReadable(path),
                    "writable", Files.isWritable(path));

            StringBuilder sb = new StringBuilder();
            sb.append("Path: ").append(relativePath(path)).append("\n");
            sb.append("Type: ").append(attrs.isDirectory() ? "Directory" : "File").append("\n");
            sb.append("Size: ").append(formatSize(attrs.size())).append("\n");
            sb.append("Created: ").append(attrs.creationTime()).append("\n");
            sb.append("Modified: ").append(attrs.lastModifiedTime()).append("\n");

            return ToolResult.success(sb.toString(), info);

        } catch (IOException e) {
            return ToolResult.failure("Failed to get file info: " + e.getMessage());
        }
    }

    private ToolResult sendFile(Path path) {
        if (!Files.exists(path)) {
            return ToolResult.failure("File not found: " + relativePath(path));
        }

        if (!Files.isRegularFile(path)) {
            return ToolResult.failure("Not a file: " + relativePath(path));
        }

        try {
            long size = Files.size(path);
            if (size > MAX_SEND_FILE_SIZE) {
                return ToolResult.failure("File too large for sending (max 50 MB): " + formatSize(size));
            }

            byte[] bytes = Files.readAllBytes(path);
            Path filenamePath = path.getFileName();
            if (filenamePath == null) {
                return ToolResult.failure("Cannot send root path");
            }
            String filename = filenamePath.toString();
            String mimeType = detectMimeType(filename);
            Attachment.Type type = mimeType.startsWith("image/") ? Attachment.Type.IMAGE : Attachment.Type.DOCUMENT;

            return ToolResult.success(
                    "File queued for sending: " + filename + " (" + formatSize(size) + ")",
                    Map.of("file_bytes", bytes, "filename", filename, "mime_type", mimeType, "type", type));

        } catch (IOException e) {
            return ToolResult.failure("Failed to read file: " + e.getMessage());
        }
    }

    static String detectMimeType(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0)
            return "application/octet-stream";
        String ext = filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return MIME_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    private String relativePath(Path path) {
        return workspaceRoot.relativize(path).toString();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024)
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
