package me.golemcore.bot.domain.model.selfevolving.tactic;

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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Immutable runtime-status projection for the managed local Ollama runtime.
 */
@Value
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ManagedLocalOllamaStatus {

    ManagedLocalOllamaState currentState;
    Boolean owned;
    String endpoint;
    String version;
    String selectedModel;
    Boolean modelPresent;
    String lastError;
    Integer restartAttempts;
    Instant nextRetryAt;
    String nextRetryTime;
    Instant updatedAt;
}
