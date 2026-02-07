package me.golemcore.bot.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketTest {

    @Test
    void tryConsume_allowsRequestsWithinLimit() {
        TokenBucket bucket = new TokenBucket(10, Duration.ofMinutes(1));

        for (int i = 0; i < 10; i++) {
            RateLimitResult result = bucket.tryConsume();
            assertTrue(result.isAllowed(), "Request " + (i + 1) + " should be allowed");
            assertEquals(9 - i, result.getRemainingTokens());
        }
    }

    @Test
    void tryConsume_deniesRequestsOverLimit() {
        TokenBucket bucket = new TokenBucket(5, Duration.ofMinutes(1));

        // Consume all tokens
        for (int i = 0; i < 5; i++) {
            bucket.tryConsume();
        }

        // Next request should be denied
        RateLimitResult result = bucket.tryConsume();
        assertFalse(result.isAllowed());
        assertNotNull(result.getWaitTime());
        assertEquals("Rate limit exceeded", result.getReason());
    }

    @Test
    void tryConsume_refillsOverTime() throws InterruptedException {
        // 10 tokens per second
        TokenBucket bucket = new TokenBucket(10, Duration.ofSeconds(1));

        // Consume all tokens
        for (int i = 0; i < 10; i++) {
            bucket.tryConsume();
        }

        // Should be denied immediately
        assertFalse(bucket.tryConsume().isAllowed());

        // Wait for refill (at least 100ms for 1 token)
        Thread.sleep(150);

        // Should be allowed now
        assertTrue(bucket.tryConsume().isAllowed());
    }

    @Test
    void tryConsume_multipleTokens() {
        TokenBucket bucket = new TokenBucket(10, Duration.ofMinutes(1));

        // Consume 5 tokens at once
        RateLimitResult result = bucket.tryConsume(5);
        assertTrue(result.isAllowed());
        assertEquals(5, result.getRemainingTokens());

        // Try to consume 6 more (should fail)
        result = bucket.tryConsume(6);
        assertFalse(result.isAllowed());

        // Consume remaining 5
        result = bucket.tryConsume(5);
        assertTrue(result.isAllowed());
        assertEquals(0, result.getRemainingTokens());
    }

    @Test
    void getState_returnsCorrectState() {
        TokenBucket bucket = new TokenBucket(20, Duration.ofMinutes(1));

        bucket.tryConsume(5);

        BucketState state = bucket.getState("test-key");
        assertEquals("test-key", state.getKey());
        assertEquals(15, state.getTokens());
        assertEquals(20, state.getCapacity());
    }
}
