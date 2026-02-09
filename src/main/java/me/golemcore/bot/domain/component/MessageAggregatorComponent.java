package me.golemcore.bot.domain.component;

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

import me.golemcore.bot.domain.model.Message;

import java.util.List;

/**
 * Detects and aggregates fragmented user messages for improved skill routing.
 *
 * <p>
 * Users often split complex requests across multiple messages. Implementations
 * identify fragmentation signals and combine related messages into a single
 * query for skill matching.
 *
 * @since 1.0
 */
public interface MessageAggregatorComponent {

    /**
     * Build aggregated query for skill routing from conversation history.
     *
     * @param history
     *            full message history
     * @return aggregated query string for routing
     */
    String buildRoutingQuery(List<Message> history);

    /**
     * Check if a message appears to be fragmented (part of a larger request).
     *
     * @param message
     *            the message to check
     * @param history
     *            recent message history
     * @return true if message appears fragmented
     */
    boolean isFragmented(String message, List<Message> history);

    /**
     * Get aggregation analysis for debugging/logging.
     *
     * @param history
     *            full message history
     * @return analysis result with fragmentation signals
     */
    AggregationAnalysis analyze(List<Message> history);

    /**
     * Analysis result for debugging.
     */
    record AggregationAnalysis(
            boolean isFragmented,
            List<String> signals,
            String summary) {
    }
}
