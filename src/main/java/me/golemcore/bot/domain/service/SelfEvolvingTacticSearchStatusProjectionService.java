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

import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaStatus;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaState;

import java.time.Instant;

/**
 * Domain-level projection wrapper for the managed Ollama runtime status.
 */
public class SelfEvolvingTacticSearchStatusProjectionService {

    private final String defaultEndpoint;
    private final String defaultSelectedModel;

    public SelfEvolvingTacticSearchStatusProjectionService() {
        this(null, null);
    }

    public SelfEvolvingTacticSearchStatusProjectionService(String defaultEndpoint, String defaultSelectedModel) {
        this.defaultEndpoint = defaultEndpoint;
        this.defaultSelectedModel = defaultSelectedModel;
    }

    public ManagedLocalOllamaStatus project(ManagedLocalOllamaStatus status) {
        ManagedLocalOllamaStatus source = status != null ? status : ManagedLocalOllamaStatus.builder().build();
        Instant nextRetryAt = source.getNextRetryAt();

        return ManagedLocalOllamaStatus.builder()
                .currentState(
                        source.getCurrentState() != null ? source.getCurrentState() : ManagedLocalOllamaState.DISABLED)
                .owned(Boolean.TRUE.equals(source.getOwned()))
                .endpoint(source.getEndpoint() != null ? source.getEndpoint() : defaultEndpoint)
                .version(source.getVersion())
                .selectedModel(source.getSelectedModel() != null ? source.getSelectedModel() : defaultSelectedModel)
                .modelPresent(source.getModelPresent())
                .lastError(source.getLastError())
                .restartAttempts(source.getRestartAttempts() != null ? source.getRestartAttempts() : 0)
                .nextRetryAt(nextRetryAt)
                .nextRetryTime(source.getNextRetryTime() != null
                        ? source.getNextRetryTime()
                        : (nextRetryAt != null ? nextRetryAt.toString() : null))
                .updatedAt(source.getUpdatedAt() != null ? source.getUpdatedAt() : Instant.EPOCH)
                .build();
    }
}
