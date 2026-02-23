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

package me.golemcore.bot.plugin.builtin.webhooks.inbound.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload POSTed to the caller's {@code callbackUrl} after an {@code /agent}
 * run completes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CallbackPayload {

    /** Run identifier correlating with the original {@code /agent} response. */
    private String runId;

    /** Session identifier. */
    private String chatId;

    /** Outcome: {@code "completed"} or {@code "failed"}. */
    private String status;

    /** Agent response text (on success). */
    private String response;

    /** Model tier used. */
    private String model;

    /** Wall-clock execution time in milliseconds. */
    private long durationMs;

    /** Error message (on failure). */
    private String error;
}
