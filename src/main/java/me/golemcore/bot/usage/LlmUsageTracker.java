package me.golemcore.bot.usage;

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

import me.golemcore.bot.domain.model.LlmUsage;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Interface for tracking and aggregating LLM usage statistics.
 *
 * <p>
 * Records token usage, costs, and latency for all LLM calls. Statistics can be
 * queried by:
 * <ul>
 * <li>Provider - aggregate for all models from a provider</li>
 * <li>Model - per-model breakdown (key format: "provider/model")</li>
 * <li>Time period - filter to recent usage (e.g., last 24 hours)</li>
 * </ul>
 *
 * <p>
 * Implementations typically persist usage data to JSONL files for durability
 * and later analysis.
 *
 * @since 1.0
 * @see LlmUsageTrackerImpl
 */
public interface LlmUsageTracker {

    void recordUsage(String providerId, String model, LlmUsage usage);

    UsageStats getStats(String providerId, Duration period);

    Map<String, UsageStats> getAllStats(Duration period);

    /**
     * Get usage statistics grouped by provider/model. Key format:
     * "providerId/model" (e.g. "langchain4j/gpt-5.1").
     */
    Map<String, UsageStats> getStatsByModel(Duration period);

    List<UsageMetric> exportMetrics();
}
