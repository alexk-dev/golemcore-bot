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

/**
 * Result of a rate limit check operation.
 *
 * <p>
 * Contains:
 * <ul>
 * <li>{@code allowed} - whether the request was permitted</li>
 * <li>{@code remainingTokens} - how many tokens are left in the bucket</li>
 * <li>{@code waitTime} - if denied, how long to wait until next token</li>
 * <li>{@code reason} - explanation for denial</li>
 * </ul>
 *
 * <p>
 * Factory methods {@link #allowed(long)} and {@link #denied(long, String)}
 * provide convenient result construction.
 *
 * @since 1.0
 */
@Data
@Builder
public class RateLimitResult {

    private boolean allowed;
    private long remainingTokens;
    private Duration waitTime;
    private String reason;

    public static RateLimitResult allowed(long remaining) {
        return RateLimitResult.builder()
                .allowed(true)
                .remainingTokens(remaining)
                .build();
    }

    public static RateLimitResult denied(long waitMs, String reason) {
        return RateLimitResult.builder()
                .allowed(false)
                .waitTime(Duration.ofMillis(waitMs))
                .reason(reason)
                .build();
    }
}
