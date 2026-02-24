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

import me.golemcore.bot.domain.model.BucketState;
import me.golemcore.bot.domain.model.RateLimitResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.RateLimitPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket based rate limiter with three-tier limits.
 *
 * <p>
 * Implements {@link RateLimitPort} interface with separate buckets for:
 * <ul>
 * <li><b>Global user limit</b> - requests per minute (default 20/min)</li>
 * <li><b>Channel limit</b> - messages per second per channel (default
 * 30/sec)</li>
 * <li><b>LLM provider limit</b> - requests per minute per provider (default
 * 60/min)</li>
 * </ul>
 *
 * <p>
 * Each bucket refills tokens over time using the token bucket algorithm.
 * Maintains separate {@link TokenBucket} instances per key in a concurrent map.
 *
 * <p>
 * Can be disabled via {@code bot.rate-limit.enabled=false}.
 *
 * @since 1.0
 * @see TokenBucket
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TokenBucketRateLimiter implements RateLimitPort {

    private final RuntimeConfigService runtimeConfigService;

    private final Map<String, ConfiguredBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume() {
        if (!runtimeConfigService.isRateLimitEnabled()) {
            return RateLimitResult.allowed(Long.MAX_VALUE);
        }

        String key = "user:global";
        int requestsPerMinute = runtimeConfigService.getUserRequestsPerMinute();
        TokenBucket bucket = resolveBucket(key, requestsPerMinute, Duration.ofMinutes(1));

        RateLimitResult result = bucket.tryConsume();
        if (!result.isAllowed()) {
            log.debug("Rate limit exceeded (global)");
        }
        return result;
    }

    @Override
    public RateLimitResult tryConsumeChannel(String channelType) {
        if (!runtimeConfigService.isRateLimitEnabled()) {
            return RateLimitResult.allowed(Long.MAX_VALUE);
        }

        String key = "channel:" + channelType;
        int messagesPerSecond = runtimeConfigService.getChannelMessagesPerSecond();
        TokenBucket bucket = resolveBucket(key, messagesPerSecond, Duration.ofSeconds(1));

        return bucket.tryConsume();
    }

    @Override
    public RateLimitResult tryConsumeLlm(String providerId) {
        if (!runtimeConfigService.isRateLimitEnabled()) {
            return RateLimitResult.allowed(Long.MAX_VALUE);
        }

        String key = "llm:" + providerId;
        int requestsPerMinute = runtimeConfigService.getLlmRequestsPerMinute();
        TokenBucket bucket = resolveBucket(key, requestsPerMinute, Duration.ofMinutes(1));

        return bucket.tryConsume();
    }

    @Override
    public BucketState getBucketState(String key) {
        ConfiguredBucket configuredBucket = buckets.get(key);
        if (configuredBucket == null) {
            return null;
        }
        return configuredBucket.bucket().getState(key);
    }

    private TokenBucket resolveBucket(String key, int capacity, Duration refillPeriod) {
        ConfiguredBucket configured = buckets.compute(key, (bucketKey, existing) -> {
            if (existing == null || existing.capacity() != capacity
                    || !existing.refillPeriod().equals(refillPeriod)) {
                return new ConfiguredBucket(new TokenBucket(capacity, refillPeriod), capacity, refillPeriod);
            }
            return existing;
        });
        return configured.bucket();
    }

    private record ConfiguredBucket(TokenBucket bucket, int capacity, Duration refillPeriod) {
    }
}
