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
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.security.InjectionGuard;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Tool for executing shell commands within a sandboxed environment.
 *
 * <p>
 * Commands are executed in the workspace directory with strict security
 * restrictions. Multiple layers of filtering prevent dangerous operations.
 *
 * <p>
 * Security:
 * <ul>
 * <li>Working directory sandboxed to workspace
 * <li>Blocked commands: rm -rf /, sudo, mkfs, shutdown, etc.
 * <li>Blocked patterns: file:// redirects, eval, curl|sh, etc.
 * <li>Path traversal injection detection via {@link InjectionGuard}
 * <li>Configurable timeout (default 30s, max 300s)
 * <li>Output truncation (max 100K characters)
 * </ul>
 *
 * <p>
 * Commands execute via /bin/sh -c in the workspace directory.
 *
 * <p>
 * Configuration:
 * <ul>
 * <li>Enable/disable is controlled via RuntimeConfig (tools.shellEnabled)
 * <li>{@code bot.tools.shell.workspace} - Working directory
 * <li>{@code bot.tools.shell.default-timeout} - Default timeout (seconds)
 * <li>{@code bot.tools.shell.max-timeout} - Max timeout (seconds)
 * </ul>
 *
 * @see InjectionGuard
 */
@Component
@Slf4j
public class ShellTool implements ToolComponent {

    private static final String PARAM_TYPE = "type";
    private static final String PARAM_COMMAND = "command";
    private static final String TYPE_STRING = "string";
    private static final String TYPE_INTEGER = "integer";
    private static final String TYPE_OBJECT = "object";

    private static final int MAX_OUTPUT_LENGTH = 100_000;

    // Blocked commands and patterns for security
    private static final Set<String> BLOCKED_COMMANDS = Set.of(
            "rm -rf /", "rm -rf /*",
            "mkfs", "dd if=/dev",
            ":(){ :|:& };:", // Fork bomb
            "shutdown", "reboot", "halt", "poweroff",
            "passwd", "useradd", "userdel", "usermod",
            "chmod 777 /", "chown -R",
            "sudo su", "su -",
            "nc -l", "ncat -l", // Reverse shells
            "> /dev/sda");

    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            Pattern.compile("rm\\s+(-[rf]+\\s+)?/(?!tmp)"), // rm outside /tmp
            Pattern.compile(">(\\s*)/dev/"),
            Pattern.compile("\\|\\s*(bash|sh|zsh)\\s*-"),
            Pattern.compile("curl.*\\|.*sh"),
            Pattern.compile("wget.*\\|.*sh"),
            Pattern.compile("eval\\s*\\$"),
            Pattern.compile("base64\\s*-d.*\\|.*(sh|bash)"),
            Pattern.compile("/etc/passwd"),
            Pattern.compile("/etc/shadow"));

    private static final Set<String> DEFAULT_ALLOWED_ENV_VARS = Set.of(
            "PATH", "LANG", "LC_ALL", "LC_CTYPE", "TERM", "TMPDIR",
            "TZ", "SHELL", "USER", "LOGNAME");

    private final Path workspaceRoot;
    private final InjectionGuard injectionGuard;
    private final RuntimeConfigService runtimeConfigService;
    private final int defaultTimeout;
    private final int maxTimeout;
    private final Set<String> allowedEnvVars;
    private final ExecutorService executor;

    public ShellTool(BotProperties properties, RuntimeConfigService runtimeConfigService,
            InjectionGuard injectionGuard) {
        BotProperties.ShellToolProperties config = properties.getTools().getShell();
        this.runtimeConfigService = runtimeConfigService;
        this.defaultTimeout = config.getDefaultTimeout();
        this.maxTimeout = config.getMaxTimeout();
        this.workspaceRoot = Paths.get(config.getWorkspace()).toAbsolutePath().normalize();
        this.injectionGuard = injectionGuard;
        this.allowedEnvVars = buildAllowedEnvVars(config.getAllowedEnvVars());
        this.executor = Executors.newCachedThreadPool();

        // Ensure workspace exists
        try {
            Files.createDirectories(workspaceRoot);
            log.info("ShellTool workspace: {}", workspaceRoot);
        } catch (IOException e) {
            log.error("Failed to create workspace directory: {}", workspaceRoot, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("[Shell] Executor did not terminate within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("shell")
                .description(
                        """
                                Execute shell commands in the workspace directory.
                                Use for running scripts, compiling code, or system commands.
                                Commands run with timeout protection (default 30s, max 300s).
                                Working directory is the sandbox workspace.
                                Allowed commands: ls, find, cat, head, tail, grep, wc, echo, mkdir, touch, cp, mv, python, node, etc.
                                Only dangerous system commands are blocked (rm -rf /, shutdown, passwd, etc.).
                                """)
                .inputSchema(Map.of(
                        PARAM_TYPE, TYPE_OBJECT,
                        "properties", Map.of(
                                PARAM_COMMAND, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        "description", "Shell command to execute"),
                                "timeout", Map.of(
                                        PARAM_TYPE, TYPE_INTEGER,
                                        "description", "Timeout in seconds (default: 30, max: 300)"),
                                "workdir", Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        "description", "Working directory relative to workspace (optional)")),
                        "required", List.of(PARAM_COMMAND)))
                .build();
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigService.isShellEnabled();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("[Shell] Execute called with parameters: {}", parameters);

            if (!isEnabled()) {
                log.warn("[Shell] Tool is DISABLED");
                return ToolResult.failure("Shell tool is disabled");
            }

            try {
                String command = (String) parameters.get(PARAM_COMMAND);
                if (command == null || command.isBlank()) {
                    log.warn("[Shell] Missing command parameter");
                    return ToolResult.failure("Missing required parameter: command");
                }
                log.info("[Shell] Command: '{}'", truncate(command, 200));

                // Security validation
                log.debug("[Shell] Validating command security...");
                ToolResult securityCheck = validateCommand(command);
                if (!securityCheck.isSuccess()) {
                    log.warn("[Shell] Security check FAILED: {}", securityCheck.getError());
                    return securityCheck;
                }

                // Parse timeout
                int timeout = defaultTimeout;
                Object timeoutObj = parameters.get("timeout");
                if (timeoutObj != null) {
                    timeout = Math.min(((Number) timeoutObj).intValue(), maxTimeout);
                    timeout = Math.max(timeout, 1);
                }
                log.debug("[Shell] Timeout: {}s", timeout);

                // Resolve working directory
                Path workDir = workspaceRoot;
                String workdirStr = (String) parameters.get("workdir");
                if (workdirStr != null && !workdirStr.isBlank()) {
                    workDir = workspaceRoot.resolve(workdirStr).normalize();
                    if (!workDir.startsWith(workspaceRoot)) {
                        log.warn("[Shell] Working directory outside workspace: {}", workdirStr);
                        return ToolResult.failure("Working directory must be within workspace");
                    }
                    if (!Files.isDirectory(workDir)) {
                        log.warn("[Shell] Working directory does not exist: {}", workdirStr);
                        return ToolResult.failure("Working directory does not exist: " + workdirStr);
                    }
                    // Follow symlinks to prevent symlink escape
                    try {
                        Path realWorkDir = workDir.toRealPath();
                        Path realWorkspace = workspaceRoot.toRealPath();
                        if (!realWorkDir.startsWith(realWorkspace)) {
                            log.warn("[Shell] Symlink escape blocked in workdir: {} -> {}", workDir, realWorkDir);
                            return ToolResult.failure("Working directory must be within workspace");
                        }
                    } catch (IOException e) {
                        log.warn("[Shell] Failed to resolve real path for workdir: {}", workdirStr);
                        return ToolResult.failure("Invalid working directory");
                    }
                }
                log.debug("[Shell] Working directory: {}", workDir);

                log.info("[Shell] Executing command...");
                ToolResult result = executeCommand(command, workDir, timeout);
                @SuppressWarnings("unchecked")
                Map<String, Object> resultData = result.getData() != null ? (Map<String, Object>) result.getData()
                        : null;
                log.info("[Shell] Command result: success={}, exitCode={}",
                        result.isSuccess(),
                        resultData != null ? resultData.get("exitCode") : "N/A");
                return result;

            } catch (Exception e) {
                log.error("[Shell] ERROR: {}", e.getMessage(), e);
                return ToolResult.failure("Error: " + e.getMessage());
            }
        }, executor);
    }

    private String truncate(String text, int maxLen) {
        if (text == null)
            return "<null>";
        if (text.length() <= maxLen)
            return text;
        return text.substring(0, maxLen) + "...";
    }

    private ToolResult validateCommand(String command) {
        // Check for blocked commands
        String normalizedCmd = command.toLowerCase(java.util.Locale.ROOT).trim();
        for (String blocked : BLOCKED_COMMANDS) {
            if (normalizedCmd.contains(blocked.toLowerCase(java.util.Locale.ROOT))) {
                log.warn("Blocked command attempt: {}", command);
                return ToolResult.failure("Command blocked for security reasons");
            }
        }

        // Check for blocked patterns
        for (Pattern pattern : BLOCKED_PATTERNS) {
            if (pattern.matcher(command).find()) {
                log.warn("Blocked pattern in command: {}", command);
                return ToolResult.failure("Command blocked for security reasons");
            }
        }

        // Use existing injection guard
        if (injectionGuard.detectCommandInjection(command)) {
            log.warn("Command injection detected: {}", command);
            return ToolResult.failure("Command injection detected");
        }

        return ToolResult.success("OK");
    }

    private static Set<String> buildAllowedEnvVars(String configValue) {
        if (configValue == null || configValue.isBlank()) {
            return DEFAULT_ALLOWED_ENV_VARS;
        }
        Set<String> merged = new HashSet<>(DEFAULT_ALLOWED_ENV_VARS);
        Set<String> custom = Arrays.stream(configValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        merged.addAll(custom);
        return Collections.unmodifiableSet(merged);
    }

    private ToolResult executeCommand(String command, Path workDir, int timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder();

        // Use shell to execute command
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            pb.command("cmd.exe", "/c", command);
        } else {
            pb.command("/bin/sh", "-c", command);
        }

        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        // Sanitize environment: only keep safe vars, block LD_PRELOAD etc.
        Map<String, String> env = pb.environment();
        env.keySet().retainAll(allowedEnvVars);
        env.put("HOME", workspaceRoot.toString());
        env.put("PWD", workDir.toString());

        long startTime = System.currentTimeMillis();

        try {
            Process process = pb.start();

            // Read output with timeout
            Future<String> outputFuture = executor.submit(() -> {
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    while (line != null) {
                        if (output.length() < MAX_OUTPUT_LENGTH) {
                            output.append(line).append("\n");
                        }
                        line = reader.readLine();
                    }
                }
                return output.toString();
            });

            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            long duration = System.currentTimeMillis() - startTime;

            if (!completed) {
                process.destroyForcibly();
                return ToolResult.failure("Command timed out after " + timeoutSeconds + " seconds");
            }

            String output;
            try {
                output = outputFuture.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                output = "[Output read timeout]";
            }

            int exitCode = process.exitValue();

            // Truncate output if needed
            if (output.length() > MAX_OUTPUT_LENGTH) {
                output = output.substring(0, MAX_OUTPUT_LENGTH) + "\n[Output truncated...]";
            }

            Map<String, Object> data = Map.of(
                    "exitCode", exitCode,
                    "duration", duration,
                    PARAM_COMMAND, command,
                    "workdir", workDir.toString());

            if (exitCode == 0) {
                return ToolResult.success(output.isEmpty() ? "(no output)" : output, data);
            } else {
                String result = "Exit code: " + exitCode + "\n" + output;
                return ToolResult.builder()
                        .success(false)
                        .output(result)
                        .data(data)
                        .error("Command failed with exit code " + exitCode)
                        .build();
            }

        } catch (IOException e) {
            return ToolResult.failure("Failed to execute command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Command execution interrupted");
        } catch (ExecutionException e) {
            return ToolResult.failure("Error reading output: " + e.getMessage());
        }
    }
}
