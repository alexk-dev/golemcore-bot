package me.golemcore.bot.adapter.outbound.embedding;

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
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.golemcore.bot.port.outbound.OllamaProcessPort;

/**
 * Concrete process adapter for starting and stopping a managed local Ollama
 * runtime.
 */
public class OllamaProcessAdapter implements OllamaProcessPort {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(2);

    private final String executable;
    private Optional<Process> ownedProcess = Optional.empty();

    public OllamaProcessAdapter() {
        this("ollama");
    }

    OllamaProcessAdapter(String executable) {
        this.executable = executable;
    }

    @Override
    public boolean isBinaryAvailable() {
        VersionProbeResult probeResult = runVersionProbe();
        return probeResult.finished() && probeResult.exitCode() == 0;
    }

    @Override
    public String getInstalledVersion() {
        VersionProbeResult probeResult = runVersionProbe();
        if (!probeResult.finished() || probeResult.exitCode() != 0) {
            return null;
        }
        return normalizeRuntimeVersion(probeResult.output());
    }

    @Override
    public synchronized void startServe(String endpoint) {
        try {
            ProcessBuilder builder = new ProcessBuilder(buildServeCommand(endpoint));
            builder.environment().putAll(buildEnvironment(endpoint));
            builder.redirectErrorStream(true);
            ownedProcess = Optional.of(builder.start());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start managed Ollama process", exception);
        }
    }

    @Override
    public synchronized boolean isOwnedProcessAlive() {
        return ownedProcess.map(Process::isAlive).orElse(false);
    }

    @Override
    public synchronized Integer getOwnedProcessExitCode() {
        Process process = ownedProcess.orElse(null);
        if (process == null) {
            return null;
        }
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException ignored) {
            return null;
        }
    }

    @Override
    public synchronized void stopOwnedProcess() {
        Process process = ownedProcess.orElse(null);
        if (process == null) {
            return;
        }
        ownedProcess = Optional.empty();
        process.destroy();
        try {
            boolean finished = process.waitFor(STOP_TIMEOUT.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(STOP_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    List<String> buildServeCommand(String endpoint) {
        return List.of(executable, "serve");
    }

    Map<String, String> buildEnvironment(String endpoint) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("OLLAMA_HOST", normalizeOllamaHost(endpoint));
        return environment;
    }

    private List<String> buildVersionCommand() {
        return List.of(executable, "--version");
    }

    private VersionProbeResult runVersionProbe() {
        try {
            Process process = new ProcessBuilder(buildVersionCommand())
                    .redirectErrorStream(true)
                    .start();
            try {
                boolean finished = process.waitFor(STOP_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return new VersionProbeResult(false, null, null);
                }
                String output = new String(process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                return new VersionProbeResult(true, process.exitValue(), output);
            } finally {
                process.destroy();
            }
        } catch (IOException exception) {
            return new VersionProbeResult(false, null, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new VersionProbeResult(false, null, null);
        }
    }

    private String normalizeRuntimeVersion(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String normalized = output.trim();
        String lowerCase = normalized.toLowerCase(java.util.Locale.ROOT);
        if (lowerCase.startsWith("ollama version is ")) {
            return normalized.substring("ollama version is ".length()).trim();
        }
        if (lowerCase.startsWith("ollama version ")) {
            return normalized.substring("ollama version ".length()).trim();
        }
        return normalized;
    }

    private String normalizeOllamaHost(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "127.0.0.1:11434";
        }
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || port <= 0) {
                return endpoint;
            }
            return host + ":" + port;
        } catch (IllegalArgumentException exception) {
            return endpoint;
        }
    }

    private record VersionProbeResult(boolean finished, Integer exitCode, String output) {
    }
}
