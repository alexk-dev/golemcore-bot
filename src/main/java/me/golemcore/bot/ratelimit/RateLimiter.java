package me.golemcore.bot.ratelimit;

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

/**
 * Rate limiter interface for controlling request throughput using token bucket
 * algorithm.
 *
 * <p>
 * Provides three separate rate limiting scopes:
 * <ul>
 * <li>Global - overall request rate across all users</li>
 * <li>Channel - per-channel message rate</li>
 * <li>LLM provider - per-provider API call rate</li>
 * </ul>
 *
 * <p>
 * Each {@code tryConsume*()} method attempts to acquire a token from the
 * corresponding bucket. Returns {@link RateLimitResult} indicating whether the
 * request was allowed and how many tokens remain.
 *
 * @since 1.0
 * @see TokenBucketRateLimiter
 */
public interface RateLimiter {

    /**
     * Check and consume a token (global rate limit).
     */
    RateLimitResult tryConsume();

    /**
     * Check and consume a token for a channel.
     */
    RateLimitResult tryConsumeChannel(String channelType);

    /**
     * Check and consume a token for an LLM provider.
     */
    RateLimitResult tryConsumeLlm(String providerId);

    /**
     * Get current bucket state.
     */
    BucketState getBucketState(String key);
}
