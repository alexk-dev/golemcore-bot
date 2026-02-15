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

package me.golemcore.bot.adapter.inbound.webhook.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Request body for {@code POST /api/hooks/agent} — full agent turn (async).
 * Runs a complete agent turn in an isolated session and optionally delivers the
 * result via callback URL or cross-channel routing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequest {

    /** Prompt for the agent to process. Required. */
    private String message;

    /** Human-readable label for logging. */
    private String name;

    /** Session key. Defaults to {@code "hook:<uuid>"}. */
    private String chatId;

    /**
     * Model tier override ({@code balanced}, {@code smart}, {@code coding},
     * {@code deep}).
     */
    private String model;

    /** Route agent response to a messaging channel. */
    @Builder.Default
    private boolean deliver = false;

    /** Target channel type for delivery (e.g. {@code "telegram"}). */
    private String channel;

    /** Target chat ID on the delivery channel. */
    private String to;

    /** URL to POST the agent response to when processing completes. */
    private String callbackUrl;

    /** Maximum execution time in seconds. */
    @Builder.Default
    private int timeoutSeconds = 300;

    /** Arbitrary metadata passed through to Message.metadata. */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
