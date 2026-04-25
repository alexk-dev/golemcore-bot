package me.golemcore.bot.domain.model;

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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * Tracks usage statistics for a single LLM request including token counts,
 * latency, model information, and request metadata for analytics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsage {

    private int inputTokens;
    private int outputTokens;
    private int totalTokens;
    private Duration latency;
    private Instant timestamp;
    private String sessionId;
    private String model;
    private String providerId;

    // Request metadata for analytics
    private String skillUsed; // Which skill was active
    private String routingReason; // Why this model was selected

    /**
     * Creates a basic usage record with token counts.
     */
    public static LlmUsage of(int inputTokens, int outputTokens) {
        return LlmUsage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .timestamp(Instant.now())
                .build();
    }
}
