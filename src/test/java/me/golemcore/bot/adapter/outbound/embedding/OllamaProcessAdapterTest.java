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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OllamaProcessAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldBindManagedRuntimeToConfiguredEndpoint() {
        OllamaProcessAdapter adapter = new OllamaProcessAdapter("ollama");

        List<String> command = adapter.buildServeCommand("http://127.0.0.1:11434");
        Map<String, String> environment = adapter.buildEnvironment("http://127.0.0.1:11434");

        assertEquals(List.of("ollama", "serve"), command);
        assertEquals("127.0.0.1:11434", environment.get("OLLAMA_HOST"));
    }

    @Test
    void shouldDetectBinaryAvailabilityThroughConfiguredExecutable() throws IOException {
        Path fakeBinary = createExecutable("fake-ollama", """
                #!/bin/sh
                exit 0
                """);

        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString());

        assertTrue(adapter.isBinaryAvailable());
    }

    @Test
    void shouldReadInstalledVersionFromConfiguredExecutable() throws IOException {
        Path fakeBinary = createExecutable("fake-ollama-version", """
                #!/bin/sh
                echo 'ollama version is 0.19.0'
                exit 0
                """);

        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString());

        assertEquals("0.19.0", adapter.getInstalledVersion());
    }

    @Test
    void shouldReturnNullVersionWhenConfiguredExecutableFails() throws IOException {
        Path fakeBinary = createExecutable("fake-ollama-fail", """
                #!/bin/sh
                exit 1
                """);

        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString());

        assertFalse(adapter.isBinaryAvailable());
        assertEquals(null, adapter.getInstalledVersion());
    }

    @Test
    void shouldUseDefaultLocalHostWhenEndpointIsMissing() {
        OllamaProcessAdapter adapter = new OllamaProcessAdapter("ollama");

        Map<String, String> environment = adapter.buildEnvironment(null);

        assertEquals("127.0.0.1:11434", environment.get("OLLAMA_HOST"));
    }

    @Test
    void shouldUseDefaultLocalHostWhenEndpointIsBlank() {
        OllamaProcessAdapter adapter = new OllamaProcessAdapter("ollama");

        Map<String, String> environment = adapter.buildEnvironment("   ");

        assertEquals("127.0.0.1:11434", environment.get("OLLAMA_HOST"));
    }

    @Test
    void shouldThrowWhenManagedRuntimeFailsToStart() {
        OllamaProcessAdapter adapter = new OllamaProcessAdapter(tempDir.resolve("missing-ollama").toString());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> adapter.startServe("http://127.0.0.1:11434"));

        assertEquals("Failed to start managed Ollama process", error.getMessage());
    }

    @Test
    void shouldReturnNullExitCodeWhenNoOwnedProcessExists() {
        OllamaProcessAdapter adapter = new OllamaProcessAdapter("ollama");

        assertNull(adapter.getOwnedProcessExitCode());
    }

    @Test
    void shouldStopSafelyWhenNoOwnedProcessExists() {
        OllamaProcessAdapter adapter = new OllamaProcessAdapter("ollama");

        adapter.stopOwnedProcess();

        assertFalse(adapter.isOwnedProcessAlive());
    }

    @Test
    void shouldReturnNullExitCodeWhileOwnedProcessIsAlive() throws Exception {
        Path fakeBinary = createExecutable("fake-ollama-alive", """
                #!/bin/sh
                sleep 30
                """);
        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString());

        adapter.startServe("http://127.0.0.1:11434");

        assertTrue(adapter.isOwnedProcessAlive());
        assertEquals(null, adapter.getOwnedProcessExitCode());

        adapter.stopOwnedProcess();
    }

    @Test
    void shouldReturnOwnedProcessExitCodeAfterProcessFinishes() throws Exception {
        Path fakeBinary = createExecutable("fake-ollama-finished", """
                #!/bin/sh
                exit 0
                """);
        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString());

        adapter.startServe("http://127.0.0.1:11434");
        waitUntilProcessStops(adapter);

        assertEquals(0, adapter.getOwnedProcessExitCode());
        adapter.stopOwnedProcess();
    }

    @Test
    void shouldReturnNonZeroOwnedProcessExitCodeAfterProcessFinishes() throws Exception {
        Path fakeBinary = createExecutable("fake-ollama-failed-exit", """
                #!/bin/sh
                exit 7
                """);
        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString());

        adapter.startServe("http://127.0.0.1:11434");
        waitUntilProcessStops(adapter);

        assertEquals(7, adapter.getOwnedProcessExitCode());
        adapter.stopOwnedProcess();
    }

    @Test
    void shouldReturnEndpointUnchangedWhenHostCannotBeParsed() {
        OllamaProcessAdapter adapter = new OllamaProcessAdapter("ollama");

        Map<String, String> environment = adapter.buildEnvironment("127.0.0.1:11434");

        assertEquals("127.0.0.1:11434", environment.get("OLLAMA_HOST"));
    }

    @Test
    void shouldReturnEndpointUnchangedWhenUriHasNoPort() {
        OllamaProcessAdapter adapter = new OllamaProcessAdapter("ollama");

        Map<String, String> environment = adapter.buildEnvironment("http://localhost");

        assertEquals("http://localhost", environment.get("OLLAMA_HOST"));
    }

    @Test
    void shouldReturnEndpointUnchangedWhenUriPortIsZero() {
        OllamaProcessAdapter adapter = new OllamaProcessAdapter("ollama");

        Map<String, String> environment = adapter.buildEnvironment("http://localhost:0");

        assertEquals("http://localhost:0", environment.get("OLLAMA_HOST"));
    }

    @Test
    void shouldStartServeWithNormalizedOllamaHostEnvironment() throws Exception {
        Path hostOutput = tempDir.resolve("ollama-host.txt");
        Path fakeBinary = createExecutable("fake-ollama-env", """
                #!/bin/sh
                printf '%s' "$OLLAMA_HOST" > "$(dirname "$0")/ollama-host.txt"
                exit 0
                """);
        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString());

        adapter.startServe("http://127.0.0.1:11435");
        waitUntilProcessStops(adapter);

        assertEquals("127.0.0.1:11435", Files.readString(hostOutput));
        adapter.stopOwnedProcess();
    }

    @Test
    void shouldReturnNullVersionWhenVersionProbeTimesOut() throws IOException {
        Path fakeBinary = createExecutable("fake-ollama-slow", """
                #!/bin/sh
                exec sleep 5
                """);

        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString(), Duration.ofMillis(100));

        assertFalse(adapter.isBinaryAvailable());
        assertEquals(null, adapter.getInstalledVersion());
    }

    @Test
    void shouldReturnMissingVersionWhenExecutableIsUnavailable() {
        OllamaProcessAdapter adapter = new OllamaProcessAdapter(tempDir.resolve("missing-version-ollama").toString());

        assertFalse(adapter.isBinaryAvailable());
        assertNull(adapter.getInstalledVersion());
    }

    @Test
    void shouldReadInstalledVersionFromAlternateOllamaPrefix() throws IOException {
        Path fakeBinary = createExecutable("fake-ollama-version-alt", """
                #!/bin/sh
                echo 'ollama version 0.19.0'
                exit 0
                """);

        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString());

        assertEquals("0.19.0", adapter.getInstalledVersion());
    }

    @Test
    void shouldReturnRawInstalledVersionWhenPrefixIsUnknown() throws IOException {
        Path fakeBinary = createExecutable("fake-ollama-version-raw", """
                #!/bin/sh
                echo 'v0.19.0-custom'
                exit 0
                """);

        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString());

        assertEquals("v0.19.0-custom", adapter.getInstalledVersion());
    }

    @Test
    void shouldReturnNullInstalledVersionWhenOutputIsBlank() throws IOException {
        Path fakeBinary = createExecutable("fake-ollama-version-blank", """
                #!/bin/sh
                printf ''
                exit 0
                """);

        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString());

        assertNull(adapter.getInstalledVersion());
    }

    @Test
    void shouldReturnNullVersionWhenVersionProbeThreadIsInterrupted() throws IOException {
        Path fakeBinary = createExecutable("fake-ollama-interrupted-version", """
                #!/bin/sh
                sleep 30
                """);
        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString());

        Thread.currentThread().interrupt();
        try {
            assertNull(adapter.getInstalledVersion());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldStartAndStopOwnedProcessSafely() throws Exception {
        Path fakeBinary = createExecutable("fake-ollama-serve", """
                #!/bin/sh
                sleep 30
                """);
        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString());

        adapter.startServe("http://127.0.0.1:11434");

        assertTrue(adapter.isOwnedProcessAlive());
        adapter.stopOwnedProcess();
        Thread.sleep(100);
        assertFalse(adapter.isOwnedProcessAlive());
    }

    @Test
    void shouldForceStopOwnedProcessWhenGracefulStopTimesOut() throws Exception {
        Path pidFile = tempDir.resolve("ollama-ignore-term.pid");
        Path readyMarker = tempDir.resolve("ollama-ignore-term-ready.txt");
        Path fakeBinary = createExecutable("fake-ollama-ignore-term", """
                #!/bin/sh
                printf '%s' "$$" > "$(dirname "$0")/ollama-ignore-term.pid"
                trap '' TERM
                printf ready > "$(dirname "$0")/ollama-ignore-term-ready.txt"
                while true; do
                  read line || true
                done
                """);
        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString(), Duration.ofSeconds(2),
                Duration.ofMillis(100));

        adapter.startServe("http://127.0.0.1:11434");
        waitUntilFileExists(readyMarker);
        ProcessHandle processHandle = ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).orElseThrow();
        try {
            assertTrue(processHandle.isAlive());

            adapter.stopOwnedProcess();
            processHandle.onExit().get(1, TimeUnit.SECONDS);

            assertFalse(processHandle.isAlive());
        } finally {
            if (processHandle.isAlive()) {
                processHandle.destroyForcibly();
            }
        }
    }

    @Test
    void shouldRequestGracefulStopBeforeForceKill() throws Exception {
        Path readyMarker = tempDir.resolve("ollama-ready.txt");
        Path stopMarker = tempDir.resolve("ollama-stopped.txt");
        Path fakeBinary = createExecutable("fake-ollama-graceful-stop", """
                #!/bin/sh
                trap 'printf stopped > "$(dirname "$0")/ollama-stopped.txt"; exit 0' TERM
                printf ready > "$(dirname "$0")/ollama-ready.txt"
                while true; do
                  sleep 1
                done
                """);
        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString());

        adapter.startServe("http://127.0.0.1:11434");
        waitUntilFileExists(readyMarker);
        assertTrue(adapter.isOwnedProcessAlive());
        adapter.stopOwnedProcess();

        assertEquals("stopped", Files.readString(stopMarker));
        assertFalse(adapter.isOwnedProcessAlive());
    }

    @Test
    void shouldForceStopOwnedProcessWhenStopThreadIsInterrupted() throws Exception {
        Path fakeBinary = createExecutable("fake-ollama-interrupted-stop", """
                #!/bin/sh
                sleep 30
                """);
        OllamaProcessAdapter adapter = new OllamaProcessAdapter(fakeBinary.toString());

        adapter.startServe("http://127.0.0.1:11434");
        Thread.currentThread().interrupt();
        try {
            adapter.stopOwnedProcess();
            assertFalse(adapter.isOwnedProcessAlive());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private Path createExecutable(String name, String body) throws IOException {
        Path script = tempDir.resolve(name);
        Files.writeString(script, body);
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(script, permissions);
        return script;
    }

    private void waitUntilProcessStops(OllamaProcessAdapter adapter) throws InterruptedException {
        for (int index = 0; index < 20 && adapter.isOwnedProcessAlive(); index++) {
            Thread.sleep(50);
        }
    }

    private void waitUntilFileExists(Path path) throws InterruptedException {
        for (int index = 0; index < 20 && !Files.exists(path); index++) {
            Thread.sleep(50);
        }
    }
}
