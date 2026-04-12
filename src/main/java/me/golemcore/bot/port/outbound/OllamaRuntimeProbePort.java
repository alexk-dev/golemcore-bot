package me.golemcore.bot.port.outbound;

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

import java.time.Duration;

/**
 * Outbound port for probing Ollama runtime reachability and diagnostics.
 */
public interface OllamaRuntimeProbePort {

    boolean isRuntimeReachable(String endpoint, Duration timeout);

    String getRuntimeVersion(String endpoint, Duration timeout);

    boolean hasModel(String endpoint, String model, Duration timeout);

    default boolean isRuntimeReachable(String endpoint) {
        return isRuntimeReachable(endpoint, Duration.ofSeconds(5));
    }

    default String getRuntimeVersion(String endpoint) {
        return getRuntimeVersion(endpoint, Duration.ofSeconds(5));
    }

    default boolean hasModel(String endpoint, String model) {
        return hasModel(endpoint, model, Duration.ofSeconds(5));
    }
}
