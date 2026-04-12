package me.golemcore.bot.domain.service;

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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.port.outbound.WorkspaceFilePort;
import me.golemcore.bot.port.outbound.WorkspaceSettingsPort;
import org.springframework.stereotype.Service;

/**
 * Collects workspace instructions from AGENTS.md and CLAUDE.md files.
 *
 * <p>
 * The scan is recursive from configured tool workspaces and follows "more local
 * overrides broader" ordering by placing deeper files later in output.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceInstructionService {

    private static final Set<String> INSTRUCTION_FILE_NAMES = Set.of("AGENTS.md", "CLAUDE.md");
    private static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
            ".git", "node_modules", "target", "build", "dist", "out",
            ".idea", ".vscode", ".gradle", ".mvn", ".venv", "venv");

    private static final int MAX_SCAN_DEPTH = 24;
    private static final int MAX_INSTRUCTION_FILES = 80;
    private static final int MAX_SINGLE_FILE_CHARS = 40_000;
    private static final int MAX_TOTAL_CONTEXT_CHARS = 160_000;
    private static final long CACHE_TTL_MS = 5_000L;

    private final WorkspaceSettingsPort settingsPort;
    private final WorkspaceFilePort workspaceFilePort;

    private volatile long cacheTimestampMs;
    private volatile String cachedContext = "";

    /**
     * Builds formatted instruction context to inject into system prompt.
     */
    public String getWorkspaceInstructionsContext() {
        long nowMs = System.currentTimeMillis();
        if (nowMs - cacheTimestampMs < CACHE_TTL_MS) {
            return cachedContext;
        }

        synchronized (this) {
            nowMs = System.currentTimeMillis();
            if (nowMs - cacheTimestampMs < CACHE_TTL_MS) {
                return cachedContext;
            }

            String rebuilt = rebuildContext();
            cachedContext = rebuilt;
            cacheTimestampMs = nowMs;
            return rebuilt;
        }
    }

    private String rebuildContext() {
        List<Path> roots = resolveWorkspaceRoots();
        if (roots.isEmpty()) {
            return "";
        }

        List<InstructionFile> files = new ArrayList<>();
        for (Path root : roots) {
            if (files.size() >= MAX_INSTRUCTION_FILES) {
                break;
            }
            scanRoot(root, files);
        }

        if (files.isEmpty()) {
            return "";
        }

        files.sort(Comparator
                .comparingInt(InstructionFile::directoryDepth)
                .thenComparingInt(InstructionFile::typePriority)
                .thenComparing(InstructionFile::relativePath));

        return render(files);
    }

    private List<Path> resolveWorkspaceRoots() {
        Set<Path> roots = new LinkedHashSet<>();

        Path shellRoot = resolveConfiguredPath(settingsPort.workspace().shellWorkspace());
        if (shellRoot != null && workspaceFilePort.isDirectory(shellRoot)) {
            roots.add(shellRoot);
        }

        Path filesystemRoot = resolveConfiguredPath(settingsPort.workspace().filesystemWorkspace());
        if (filesystemRoot != null && workspaceFilePort.isDirectory(filesystemRoot)) {
            roots.add(filesystemRoot);
        }

        return new ArrayList<>(roots);
    }

    private Path resolveConfiguredPath(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return null;
        }
        try {
            String resolved = configuredPath.replace("${user.home}", System.getProperty("user.home"));
            return Paths.get(resolved).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            log.warn("[WorkspaceInstructions] Invalid workspace path '{}': {}", configuredPath, e.getMessage());
            return null;
        }
    }

    private void scanRoot(Path root, List<InstructionFile> collected) {
        try {
            for (Path candidate : workspaceFilePort.walk(root)) {
                if (candidate.equals(root)) {
                    continue;
                }
                Path relative = root.relativize(candidate);
                if (relative.getNameCount() > MAX_SCAN_DEPTH) {
                    continue;
                }
                if (hasIgnoredDirectorySegment(relative)) {
                    continue;
                }
                if (!workspaceFilePort.isRegularFile(candidate)) {
                    continue;
                }
                Path fileNamePath = candidate.getFileName();
                if (fileNamePath == null) {
                    continue;
                }
                String fileName = fileNamePath.toString();
                if (!isInstructionFile(fileName)) {
                    continue;
                }
                if (collected.size() >= MAX_INSTRUCTION_FILES) {
                    break;
                }

                String content = readInstructionContent(candidate);
                if (content == null || content.isBlank()) {
                    continue;
                }

                Path relativeParent = relative.getParent();
                int directoryDepth = relativeParent != null ? relativeParent.getNameCount() : 0;
                collected.add(new InstructionFile(root, relative, fileName, directoryDepth, content));
            }
        } catch (IOException e) {
            log.debug("[WorkspaceInstructions] Failed to scan {}: {}", root, e.getMessage());
        }
    }

    private boolean hasIgnoredDirectorySegment(Path relativePath) {
        for (Path segment : relativePath) {
            if (segment == null) {
                continue;
            }
            if (isIgnoredDirectory(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isIgnoredDirectory(String directoryName) {
        return IGNORED_DIRECTORY_NAMES.contains(directoryName.toLowerCase(Locale.ROOT));
    }

    private boolean isInstructionFile(String fileName) {
        for (String allowed : INSTRUCTION_FILE_NAMES) {
            if (allowed.equalsIgnoreCase(fileName)) {
                return true;
            }
        }
        return false;
    }

    private String readInstructionContent(Path file) {
        try {
            String content = workspaceFilePort.readString(file);
            if (content.length() > MAX_SINGLE_FILE_CHARS) {
                return content.substring(0, MAX_SINGLE_FILE_CHARS) + "\n[TRUNCATED]";
            }
            return content;
        } catch (IOException e) {
            log.debug("[WorkspaceInstructions] Failed to read {}: {}", file, e.getMessage());
            return "";
        }
    }

    private String render(List<InstructionFile> files) {
        StringBuilder sb = new StringBuilder();
        int totalChars = 0;

        for (InstructionFile file : files) {
            String blockHeader = "## " + file.relativePath() + "\n";
            String blockContent = file.content().trim() + "\n\n";
            int blockSize = blockHeader.length() + blockContent.length();

            if (totalChars + blockSize > MAX_TOTAL_CONTEXT_CHARS) {
                break;
            }

            sb.append(blockHeader);
            sb.append(blockContent);
            totalChars += blockSize;
        }

        return sb.toString().trim();
    }

    private record InstructionFile(Path root, Path relativePath, String fileName, int directoryDepth, String content) {
        private int typePriority() {
            if ("CLAUDE.md".equalsIgnoreCase(fileName)) {
                return 0;
            }
            return 1; // AGENTS.md after CLAUDE.md in the same folder
        }
    }
}
