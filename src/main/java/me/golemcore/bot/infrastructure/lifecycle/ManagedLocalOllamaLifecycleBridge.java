package me.golemcore.bot.infrastructure.lifecycle;

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

import jakarta.annotation.PreDestroy;
import java.net.URI;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.ManagedLocalOllamaSupervisor;
import me.golemcore.bot.domain.service.RuntimeConfigService;

/**
 * Framework-facing lifecycle bridge for the managed local Ollama supervisor.
 */
public class ManagedLocalOllamaLifecycleBridge {

    private final RuntimeConfigService runtimeConfigService;
    private final ManagedLocalOllamaSupervisor supervisor;
    private volatile boolean started;

    public ManagedLocalOllamaLifecycleBridge(RuntimeConfigService runtimeConfigService,
            ManagedLocalOllamaSupervisor supervisor) {
        this.runtimeConfigService = runtimeConfigService;
        this.supervisor = supervisor;
    }

    public synchronized void runStartupGate() {
        if (started) {
            return;
        }
        supervisor.startupCheck(isManagedLocalEmbeddingsActive(runtimeConfigService.getSelfEvolvingConfig()));
        started = true;
    }

    @PreDestroy
    public synchronized void stop() {
        supervisor.stop();
        started = false;
    }

    boolean isStarted() {
        return started;
    }

    private boolean isManagedLocalEmbeddingsActive(RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig) {
        if (selfEvolvingConfig == null || !Boolean.TRUE.equals(selfEvolvingConfig.getEnabled())) {
            return false;
        }
        RuntimeConfig.SelfEvolvingTacticsConfig tacticsConfig = selfEvolvingConfig.getTactics();
        if (tacticsConfig == null || !Boolean.TRUE.equals(tacticsConfig.getEnabled())) {
            return false;
        }
        RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig = tacticsConfig.getSearch();
        if (searchConfig == null || !"hybrid".equalsIgnoreCase(searchConfig.getMode())) {
            return false;
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddingsConfig = searchConfig.getEmbeddings();
        if (embeddingsConfig == null || !Boolean.TRUE.equals(embeddingsConfig.getEnabled())) {
            return false;
        }
        if (!"ollama".equalsIgnoreCase(trimToNull(embeddingsConfig.getProvider()))) {
            return false;
        }
        return isLocalEndpoint(trimToNull(embeddingsConfig.getBaseUrl()));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isLocalEndpoint(String baseUrl) {
        if (baseUrl == null) {
            return true;
        }
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            return "127.0.0.1".equals(host)
                    || "localhost".equalsIgnoreCase(host)
                    || "::1".equals(host)
                    || "[::1]".equals(host);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
