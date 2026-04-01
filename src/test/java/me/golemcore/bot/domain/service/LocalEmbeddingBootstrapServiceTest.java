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

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalEmbeddingBootstrapServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private TacticSearchMetricsService metricsService;
    private RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig;
    private StubLocalEmbeddingBootstrapService bootstrapService;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        metricsService = new TacticSearchMetricsService(
                Clock.fixed(Instant.parse("2026-04-01T20:00:00Z"), ZoneOffset.UTC));

        RuntimeConfig config = RuntimeConfig.builder().build();
        selfEvolvingConfig = config.getSelfEvolving();
        selfEvolvingConfig.setEnabled(true);
        selfEvolvingConfig.getTactics().setEnabled(true);
        selfEvolvingConfig.getTactics().getSearch().setMode("hybrid");
        selfEvolvingConfig.getTactics().getSearch().getEmbeddings().setEnabled(true);
        selfEvolvingConfig.getTactics().getSearch().getEmbeddings().setProvider("ollama");
        selfEvolvingConfig.getTactics().getSearch().getEmbeddings().setBaseUrl("http://127.0.0.1:11434");
        selfEvolvingConfig.getTactics().getSearch().getEmbeddings().setModel("qwen3-embedding:0.6b");
        selfEvolvingConfig.getTactics().getSearch().getEmbeddings().getLocal().setAutoInstall(true);
        selfEvolvingConfig.getTactics().getSearch().getEmbeddings().getLocal().setPullOnStart(true);
        selfEvolvingConfig.getTactics().getSearch().getEmbeddings().getLocal().setRequireHealthyRuntime(true);
        selfEvolvingConfig.getTactics().getSearch().getEmbeddings().getLocal().setFailOpen(true);

        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(selfEvolvingConfig);

        bootstrapService = new StubLocalEmbeddingBootstrapService(runtimeConfigService, metricsService,
                Clock.fixed(Instant.parse("2026-04-01T20:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldDegradeToBm25WhenLocalRuntimeIsUnavailable() {
        bootstrapService.runtimeHealthy = false;

        TacticSearchStatus status = bootstrapService.initialize();

        assertEquals("bm25", status.getMode());
        assertTrue(status.getDegraded());
        assertFalse(status.getRuntimeHealthy());
        assertTrue(status.getReason().contains("runtime unavailable"));
        assertEquals(1L, metricsService.snapshot().fallbackCount());
        assertEquals("bm25", metricsService.snapshot().activeMode());
    }

    @Test
    void shouldPullMissingModelWhenAutoInstallEnabled() {
        bootstrapService.runtimeHealthy = true;
        bootstrapService.modelPresent = false;
        bootstrapService.pullResult = true;

        TacticSearchStatus status = bootstrapService.initialize();

        assertEquals("hybrid", status.getMode());
        assertTrue(status.getRuntimeHealthy());
        assertTrue(status.getModelAvailable());
        assertTrue(status.getPullAttempted());
        assertTrue(status.getPullSucceeded());
        assertEquals(1, bootstrapService.pullAttempts);
        assertEquals("hybrid", metricsService.snapshot().activeMode());
    }

    @Test
    void shouldInitializeTacticSearchStatusOnStartup() {
        bootstrapService.runtimeHealthy = true;
        bootstrapService.modelPresent = true;

        ReflectionTestUtils.invokeMethod(bootstrapService, "initializeOnStartup");

        assertEquals("hybrid", metricsService.snapshot().activeMode());
        assertFalse(metricsService.snapshot().degraded());
        assertEquals(0L, metricsService.snapshot().fallbackCount());
    }

    private static final class StubLocalEmbeddingBootstrapService extends LocalEmbeddingBootstrapService {

        private boolean runtimeHealthy = true;
        private boolean modelPresent = true;
        private boolean pullResult = false;
        private int pullAttempts;

        private StubLocalEmbeddingBootstrapService(RuntimeConfigService runtimeConfigService,
                TacticSearchMetricsService metricsService,
                Clock clock) {
            super(runtimeConfigService, metricsService, clock);
        }

        @Override
        protected boolean isRuntimeHealthy(String baseUrl) {
            return runtimeHealthy;
        }

        @Override
        protected boolean hasModel(String baseUrl, String model) {
            return modelPresent;
        }

        @Override
        protected boolean pullModel(String baseUrl, String model) {
            pullAttempts++;
            if (pullResult) {
                modelPresent = true;
            }
            return pullResult;
        }
    }
}
