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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Time-series metric for export to monitoring systems.
 *
 * <p>
 * Represents a single measurement at a point in time with:
 * <ul>
 * <li>{@code name} - metric name (e.g., "llm_requests", "llm_tokens")</li>
 * <li>{@code tags} - dimensions for grouping (provider, model, etc.)</li>
 * <li>{@code value} - numeric measurement</li>
 * <li>{@code timestamp} - when the measurement was taken</li>
 * </ul>
 *
 * <p>
 * Compatible with time-series databases like Prometheus, InfluxDB, etc.
 *
 * @since 1.0
 */
@Data
@Builder
public class UsageMetric {

    private String name;
    private Map<String, String> tags;
    private double value;
    private Instant timestamp;

    public static UsageMetric of(String name, double value, String... tags) {
        Map<String, String> tagMap = new HashMap<>();
        for (int i = 0; i < tags.length - 1; i += 2) {
            tagMap.put(tags[i], tags[i + 1]);
        }
        return UsageMetric.builder()
                .name(name)
                .tags(tagMap)
                .value(value)
                .timestamp(Instant.now())
                .build();
    }
}
