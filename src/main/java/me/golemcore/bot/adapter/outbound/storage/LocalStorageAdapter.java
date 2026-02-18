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

package me.golemcore.bot.adapter.outbound.storage;

import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Local filesystem implementation of StoragePort.
 *
 * <p>
 * Stores all data in a local workspace directory with subdirectories for
 * different data types:
 * <ul>
 * <li>sessions/ - conversation sessions
 * <li>memory/ - persistent memory
 * <li>skills/ - skill definitions
 * <li>usage/ - LLM usage tracking (JSONL)
 * <li>preferences/ - user preferences
 * <li>auto/ - auto mode goals and diary
 * <li>prompts/ - system prompt sections
 * </ul>
 *
 * <p>
 * Base path configured via {@code bot.storage.local.base-path}, defaults to
 * {@code ${user.home}/.golemcore/workspace}.
 *
 * @see me.golemcore.bot.port.outbound.StoragePort
 */
@Component
@Primary
@RequiredArgsConstructor
@Slf4j
public class LocalStorageAdapter implements StoragePort {

    private final BotProperties properties;

    private Path basePath;

    @PostConstruct
    public void init() {
        String basePathStr = properties.getStorage().getLocal().getBasePath();
        this.basePath = Paths.get(basePathStr.replace("${user.home}", System.getProperty("user.home")))
                .toAbsolutePath().normalize();

        try {
            Files.createDirectories(basePath);

            // Pre-create all known subdirectories
            for (String dir : List.of("sessions", "memory", "skills", "usage", "preferences", "auto", "prompts")) {
                Files.createDirectories(basePath.resolve(dir));
            }

            log.info("Local storage initialized at: {}", basePath);
        } catch (IOException e) {
            log.error("Failed to create storage directory", e);
        }
    }

    @Override
    public CompletableFuture<Void> putObject(String directory, String path, byte[] content) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path filePath = resolvePath(directory, path);
                Path parent = filePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(filePath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write file: " + directory + "/" + path, e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> putText(String directory, String path, String content) {
        return putObject(directory, path, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public CompletableFuture<byte[]> getObject(String directory, String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path filePath = resolvePath(directory, path);
                if (!Files.exists(filePath)) {
                    return null;
                }
                return Files.readAllBytes(filePath);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read file: " + directory + "/" + path, e);
            }
        });
    }

    @Override
    public CompletableFuture<String> getText(String directory, String path) {
        return getObject(directory, path).thenApply(bytes -> {
            if (bytes == null) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        });
    }

    @Override
    public CompletableFuture<Boolean> exists(String directory, String path) {
        return CompletableFuture.supplyAsync(() -> {
            Path filePath = resolvePath(directory, path);
            return Files.exists(filePath);
        });
    }

    @Override
    public CompletableFuture<Void> deleteObject(String directory, String path) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path filePath = resolvePath(directory, path);
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete file: " + directory + "/" + path, e);
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> listObjects(String directory, String prefix) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path dirPath = basePath.resolve(directory);
                if (!Files.exists(dirPath)) {
                    return Collections.emptyList();
                }

                Path prefixPath = prefix != null && !prefix.isEmpty()
                        ? dirPath.resolve(prefix)
                        : dirPath;

                if (!Files.exists(prefixPath)) {
                    return Collections.emptyList();
                }

                try (Stream<Path> paths = Files.walk(prefixPath)) {
                    return paths
                            .filter(Files::isRegularFile)
                            .map(p -> dirPath.relativize(p).toString())
                            .toList();
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to list files: " + directory + "/" + prefix, e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> appendText(String directory, String path, String content) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path filePath = resolvePath(directory, path);
                Path parent = filePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(filePath, content,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new RuntimeException("Failed to append to file: " + directory + "/" + path, e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> ensureDirectory(String directory) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path dirPath = basePath.resolve(directory);
                Files.createDirectories(dirPath);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create directory: " + directory, e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> putTextAtomic(String directory, String path, String content, boolean backup) {
        return CompletableFuture.runAsync(() -> {
            Path targetPath = resolvePath(directory, path);
            Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".tmp");
            Path backupPath = targetPath.resolveSibling(targetPath.getFileName() + ".bak");

            try {
                Path parent = targetPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                // 1. Write to temp file with fsync
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = Files.newOutputStream(tempPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.SYNC);
                        FileChannel channel = FileChannel.open(tempPath, StandardOpenOption.WRITE)) {
                    os.write(bytes);
                    os.flush();
                    channel.force(true); // fsync: metadata + data
                }

                // 2. Verify written content is readable
                byte[] verification = Files.readAllBytes(tempPath);
                if (verification.length != bytes.length) {
                    throw new IOException("Verification failed: size mismatch");
                }

                // 3. Backup existing file if requested
                if (backup && Files.exists(targetPath)) {
                    Files.copy(targetPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                    log.debug("[Storage] Created backup: {}", backupPath);
                }

                // 4. Atomic rename
                try {
                    Files.move(tempPath, targetPath,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    // Fallback for filesystems without atomic move support
                    log.warn("[Storage] Atomic move not supported, using regular move");
                    Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }

                log.debug("[Storage] Atomic write completed: {}/{}", directory, path);

            } catch (IOException e) {
                // Cleanup temp file on failure
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException cleanupEx) {
                    log.warn("[Storage] Failed to cleanup temp file: {}", tempPath);
                }
                throw new RuntimeException("Atomic write failed: " + directory + "/" + path, e);
            }
        });
    }

    private Path resolvePath(String directory, String path) {
        Path resolved = basePath.resolve(directory).resolve(path).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new IllegalArgumentException("Path traversal blocked: " + directory + "/" + path);
        }
        return resolved;
    }
}
