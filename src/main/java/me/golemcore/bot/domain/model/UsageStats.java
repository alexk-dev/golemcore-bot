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

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.util.Map;

/**
 * Aggregated LLM usage statistics over a time period.
 *
 * <p>
 * Contains quantitative metrics only (no costs):
 * <ul>
 * <li>Total requests</li>
 * <li>Token counts (input, output, total)</li>
 * <li>Average latency</li>
 * <li>Optional per-model breakdowns</li>
 * </ul>
 *
 * <p>
 * Statistics can be aggregated by provider or by specific model.
 *
 * @since 1.0
 */
@Data
@Builder
public class UsageStats {

    private String providerId;
    private String model;
    private long totalRequests;
    private long totalInputTokens;
    private long totalOutputTokens;
    private long totalTokens;
    private Duration avgLatency;
    private Duration period;

    // Breakdown by model for multi-model setups
    private Map<String, Long> requestsByModel;
    private Map<String, Long> tokensByModel;

    public static UsageStats empty(String providerId) {
        return UsageStats.builder()
                .providerId(providerId)
                .totalRequests(0)
                .totalInputTokens(0)
                .totalOutputTokens(0)
                .totalTokens(0)
                .avgLatency(Duration.ZERO)
                .build();
    }
}
