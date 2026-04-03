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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaState;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaStatus;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.domain.model.RuntimeConfig;
import org.junit.jupiter.api.Test;

class SelfEvolvingTacticSearchStatusProjectionServiceTest {

    @Test
    void shouldProjectDisabledDefaultsWhenStatusIsMissing() {
        SelfEvolvingTacticSearchStatusProjectionService service = new SelfEvolvingTacticSearchStatusProjectionService(
                "http://127.0.0.1:11434",
                "qwen3-embedding:0.6b");

        ManagedLocalOllamaStatus projection = service.project(null);

        assertEquals(ManagedLocalOllamaState.DISABLED, projection.getCurrentState());
        assertFalse(projection.getOwned());
        assertEquals("http://127.0.0.1:11434", projection.getEndpoint());
        assertEquals("qwen3-embedding:0.6b", projection.getSelectedModel());
        assertEquals(0, projection.getRestartAttempts().intValue());
        assertEquals(Instant.EPOCH, projection.getUpdatedAt());
    }

    @Test
    void shouldPreserveExternalReadyProjectionFields() {
        SelfEvolvingTacticSearchStatusProjectionService service = new SelfEvolvingTacticSearchStatusProjectionService(
                "http://127.0.0.1:11434",
                "qwen3-embedding:0.6b");
        ManagedLocalOllamaStatus status = ManagedLocalOllamaStatus.builder()
                .currentState(ManagedLocalOllamaState.EXTERNAL_READY)
                .owned(false)
                .endpoint("http://127.0.0.1:11434")
                .version("0.19.0")
                .selectedModel("qwen3-embedding:0.6b")
                .modelPresent(true)
                .lastError(null)
                .restartAttempts(0)
                .nextRetryAt(null)
                .nextRetryTime(null)
                .updatedAt(Instant.parse("2026-04-02T00:01:00Z"))
                .build();

        assertEquals(status, service.project(status));
    }

    @Test
    void shouldDeriveRetryTimeFromRetryInstantWhenMissing() {
        SelfEvolvingTacticSearchStatusProjectionService service = new SelfEvolvingTacticSearchStatusProjectionService(
                "http://127.0.0.1:11434",
                "qwen3-embedding:0.6b");
        Instant retryAt = Instant.parse("2026-04-02T00:00:10Z");
        ManagedLocalOllamaStatus status = ManagedLocalOllamaStatus.builder()
                .currentState(ManagedLocalOllamaState.DEGRADED_START_TIMEOUT)
                .owned(true)
                .endpoint("http://127.0.0.1:11434")
                .version("0.19.0")
                .selectedModel("qwen3-embedding:0.6b")
                .modelPresent(false)
                .lastError("Ollama did not become healthy within 5 seconds")
                .restartAttempts(1)
                .nextRetryAt(retryAt)
                .nextRetryTime(null)
                .updatedAt(Instant.parse("2026-04-02T00:00:05Z"))
                .build();

        ManagedLocalOllamaStatus projection = service.project(status);

        assertEquals(retryAt, projection.getNextRetryAt());
        assertEquals(retryAt.toString(), projection.getNextRetryTime());
        assertEquals(ManagedLocalOllamaState.DEGRADED_START_TIMEOUT, projection.getCurrentState());
    }

    @Test
    void shouldProjectOwnedRestartBackoffIntoTacticSearchStatus() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        ManagedLocalOllamaSupervisor supervisor = mock(ManagedLocalOllamaSupervisor.class);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(RuntimeConfig.SelfEvolvingConfig.builder()
                .enabled(true)
                .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                        .enabled(true)
                        .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                .mode("hybrid")
                                .embeddings(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig.builder()
                                        .enabled(true)
                                        .provider("ollama")
                                        .baseUrl("http://127.0.0.1:11434")
                                        .model("qwen3-embedding:0.6b")
                                        .local(RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig.builder()
                                                .autoInstall(true)
                                                .pullOnStart(true)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build());
        when(supervisor.currentStatus()).thenReturn(ManagedLocalOllamaStatus.builder()
                .currentState(ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF)
                .owned(true)
                .endpoint("http://127.0.0.1:11434")
                .version("0.19.0")
                .selectedModel("qwen3-embedding:0.6b")
                .modelPresent(false)
                .lastError("Managed Ollama exited with code 137")
                .restartAttempts(3)
                .nextRetryTime("2026-04-02T00:00:10Z")
                .updatedAt(Instant.parse("2026-04-02T00:00:05Z"))
                .build());

        SelfEvolvingTacticSearchStatusProjectionService service = new SelfEvolvingTacticSearchStatusProjectionService(
                runtimeConfigService,
                supervisor,
                Clock.fixed(Instant.parse("2026-04-02T00:00:06Z"), ZoneOffset.UTC));

        TacticSearchStatus status = service.projectCurrent();

        assertEquals("bm25", status.getMode());
        assertTrue(status.getDegraded());
        assertEquals("degraded_restart_backoff", status.getRuntimeState());
        assertTrue(status.getOwned());
        assertEquals(Integer.valueOf(3), status.getRestartAttempts());
        assertEquals("2026-04-02T00:00:10Z", status.getNextRetryTime());
        assertEquals("Managed Ollama exited with code 137", status.getReason());
        assertFalse(status.getRuntimeHealthy());
    }
}
