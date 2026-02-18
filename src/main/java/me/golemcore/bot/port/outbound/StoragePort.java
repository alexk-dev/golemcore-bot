package me.golemcore.bot.port.outbound;

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

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Port for persistent storage operations within the local workspace. Provides
 * file operations organized by directory (sessions, memory, skills, etc.) with
 * support for binary, text, and append-only (JSONL) writes.
 */
public interface StoragePort {

    /**
     * Write binary content to file.
     *
     * @param directory
     *            subdirectory (e.g., "sessions", "memory", "skills")
     * @param path
     *            relative path within directory
     * @param content
     *            binary content
     */
    CompletableFuture<Void> putObject(String directory, String path, byte[] content);

    /**
     * Write text content to file.
     */
    CompletableFuture<Void> putText(String directory, String path, String content);

    /**
     * Read binary content from file.
     */
    CompletableFuture<byte[]> getObject(String directory, String path);

    /**
     * Read text content from file.
     */
    CompletableFuture<String> getText(String directory, String path);

    /**
     * Check if file exists.
     */
    CompletableFuture<Boolean> exists(String directory, String path);

    /**
     * Delete a file.
     */
    CompletableFuture<Void> deleteObject(String directory, String path);

    /**
     * List files by prefix.
     */
    CompletableFuture<List<String>> listObjects(String directory, String prefix);

    /**
     * Append text to a file (for logs, JSONL).
     */
    CompletableFuture<Void> appendText(String directory, String path, String content);

    /**
     * Atomically write text content to file with optional backup.
     *
     * <p>
     * Guarantees crash-safe writes via:
     * <ol>
     * <li>Write to temporary file (.tmp suffix)</li>
     * <li>fsync to ensure data is on disk</li>
     * <li>If backup enabled: rename existing file to .bak</li>
     * <li>Atomic rename of .tmp to target</li>
     * </ol>
     *
     * @param directory
     *            subdirectory
     * @param path
     *            relative path within directory
     * @param content
     *            text content to write
     * @param backup
     *            if true, preserve previous version as .bak
     */
    CompletableFuture<Void> putTextAtomic(String directory, String path, String content, boolean backup);

    /**
     * Ensure directory exists.
     */
    CompletableFuture<Void> ensureDirectory(String directory);
}
