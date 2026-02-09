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

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe token bucket implementation for rate limiting.
 *
 * <p>
 * The token bucket algorithm maintains a fixed-capacity bucket that:
 * <ul>
 * <li>Starts full with {@code capacity} tokens</li>
 * <li>Refills continuously over the {@code refillPeriod}</li>
 * <li>Consumes tokens on each request</li>
 * <li>Denies requests when empty, returning wait time until next token</li>
 * </ul>
 *
 * <p>
 * Refill is calculated lazily on each {@code tryConsume()} call based on
 * elapsed time since last refill. Uses {@link AtomicLong} for thread-safe token
 * tracking with synchronized methods to prevent race conditions.
 *
 * @since 1.0
 */
public class TokenBucket {

    private final long capacity;
    private final Duration refillPeriod;
    private final AtomicLong tokens;
    private final AtomicLong lastRefillNanos;

    public TokenBucket(long capacity, Duration refillPeriod) {
        this.capacity = capacity;
        this.refillPeriod = refillPeriod;
        this.tokens = new AtomicLong(capacity);
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Try to consume one token.
     */
    public synchronized RateLimitResult tryConsume() {
        refill();

        if (tokens.get() > 0) {
            long remaining = tokens.decrementAndGet();
            return RateLimitResult.allowed(remaining);
        }

        long waitTimeMs = calculateWaitTimeMs();
        return RateLimitResult.denied(waitTimeMs, "Rate limit exceeded");
    }

    /**
     * Try to consume multiple tokens.
     */
    public synchronized RateLimitResult tryConsume(long numTokens) {
        refill();

        if (tokens.get() >= numTokens) {
            long remaining = tokens.addAndGet(-numTokens);
            return RateLimitResult.allowed(remaining);
        }

        long waitTimeMs = calculateWaitTimeMs();
        return RateLimitResult.denied(waitTimeMs, "Rate limit exceeded");
    }

    /**
     * Get current state of the bucket.
     */
    public BucketState getState(String key) {
        refill();
        return BucketState.builder()
                .key(key)
                .tokens(tokens.get())
                .capacity(capacity)
                .lastRefill(Instant.ofEpochSecond(0, lastRefillNanos.get()))
                .refillRatePerSecond(capacity * 1_000_000_000L / refillPeriod.toNanos())
                .build();
    }

    private void refill() {
        long now = System.nanoTime();
        long lastRefill = lastRefillNanos.get();
        long elapsedNanos = now - lastRefill;

        if (elapsedNanos <= 0) {
            return;
        }

        // Calculate tokens to add based on elapsed time
        long tokensToAdd = (elapsedNanos * capacity) / refillPeriod.toNanos();

        if (tokensToAdd > 0) {
            long currentTokens = tokens.get();
            long newTokens = Math.min(capacity, currentTokens + tokensToAdd);
            tokens.set(newTokens);
            lastRefillNanos.set(now);
        }
    }

    private long calculateWaitTimeMs() {
        // Calculate time until next token is available
        long tokensNeeded = 1;
        long nanosPerToken = refillPeriod.toNanos() / capacity;
        return (nanosPerToken * tokensNeeded) / 1_000_000;
    }
}
