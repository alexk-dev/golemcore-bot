package me.golemcore.bot.ratelimit;

import me.golemcore.bot.domain.model.BucketState;
import me.golemcore.bot.domain.model.RateLimitResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    private BotProperties properties;
    private TokenBucketRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().getUser().setRequestsPerMinute(5);
        properties.getRateLimit().getChannel().setMessagesPerSecond(10);
        properties.getRateLimit().getLlm().setRequestsPerMinute(20);

        rateLimiter = new TokenBucketRateLimiter(properties);
    }

    // ===== Disabled =====

    @Test
    void shouldAlwaysAllowWhenDisabled() {
        properties.getRateLimit().setEnabled(false);
        rateLimiter = new TokenBucketRateLimiter(properties);

        for (int i = 0; i < 100; i++) {
            RateLimitResult result = rateLimiter.tryConsume();
            assertTrue(result.isAllowed());
            assertEquals(Long.MAX_VALUE, result.getRemainingTokens());
        }
    }

    @Test
    void shouldAlwaysAllowChannelWhenDisabled() {
        properties.getRateLimit().setEnabled(false);
        rateLimiter = new TokenBucketRateLimiter(properties);

        RateLimitResult result = rateLimiter.tryConsumeChannel("telegram");
        assertTrue(result.isAllowed());
        assertEquals(Long.MAX_VALUE, result.getRemainingTokens());
    }

    @Test
    void shouldAlwaysAllowLlmWhenDisabled() {
        properties.getRateLimit().setEnabled(false);
        rateLimiter = new TokenBucketRateLimiter(properties);

        RateLimitResult result = rateLimiter.tryConsumeLlm("openai");
        assertTrue(result.isAllowed());
        assertEquals(Long.MAX_VALUE, result.getRemainingTokens());
    }

    // ===== Global rate limit =====

    @Test
    void shouldAllowRequestsWithinLimit() {
        for (int i = 0; i < 5; i++) {
            RateLimitResult result = rateLimiter.tryConsume();
            assertTrue(result.isAllowed());
        }
    }

    @Test
    void shouldDenyRequestsExceedingGlobalLimit() {
        // Exhaust all tokens
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryConsume();
        }

        RateLimitResult result = rateLimiter.tryConsume();
        assertFalse(result.isAllowed());
        assertNotNull(result.getWaitTime());
    }

    // ===== Channel rate limit =====

    @Test
    void shouldTrackChannelRateLimitsSeparately() {
        // Each channel gets its own bucket
        RateLimitResult telegram = rateLimiter.tryConsumeChannel("telegram");
        RateLimitResult discord = rateLimiter.tryConsumeChannel("discord");

        assertTrue(telegram.isAllowed());
        assertTrue(discord.isAllowed());
    }

    @Test
    void shouldDenyChannelRequestsExceedingLimit() {
        for (int i = 0; i < 10; i++) {
            rateLimiter.tryConsumeChannel("telegram");
        }

        RateLimitResult result = rateLimiter.tryConsumeChannel("telegram");
        assertFalse(result.isAllowed());
    }

    // ===== LLM rate limit =====

    @Test
    void shouldTrackLlmRateLimitsSeparately() {
        RateLimitResult openai = rateLimiter.tryConsumeLlm("openai");
        RateLimitResult anthropic = rateLimiter.tryConsumeLlm("anthropic");

        assertTrue(openai.isAllowed());
        assertTrue(anthropic.isAllowed());
    }

    @Test
    void shouldDenyLlmRequestsExceedingLimit() {
        for (int i = 0; i < 20; i++) {
            rateLimiter.tryConsumeLlm("openai");
        }

        RateLimitResult result = rateLimiter.tryConsumeLlm("openai");
        assertFalse(result.isAllowed());
    }

    // ===== Bucket isolation =====

    @Test
    void shouldIsolateGlobalFromChannelBuckets() {
        // Exhaust global limit
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryConsume();
        }
        assertFalse(rateLimiter.tryConsume().isAllowed());

        // Channel limit should still be available
        assertTrue(rateLimiter.tryConsumeChannel("telegram").isAllowed());
    }

    @Test
    void shouldIsolateGlobalFromLlmBuckets() {
        // Exhaust global limit
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryConsume();
        }
        assertFalse(rateLimiter.tryConsume().isAllowed());

        // LLM limit should still be available
        assertTrue(rateLimiter.tryConsumeLlm("openai").isAllowed());
    }

    // ===== getBucketState =====

    @Test
    void shouldReturnNullForUnknownBucket() {
        BucketState state = rateLimiter.getBucketState("nonexistent");
        assertNull(state);
    }

    @Test
    void shouldReturnBucketStateAfterUsage() {
        rateLimiter.tryConsume();

        BucketState state = rateLimiter.getBucketState("user:global");
        assertNotNull(state);
        assertEquals("user:global", state.getKey());
        assertTrue(state.getTokens() < 5);
    }

    @Test
    void shouldReturnChannelBucketState() {
        rateLimiter.tryConsumeChannel("telegram");

        BucketState state = rateLimiter.getBucketState("channel:telegram");
        assertNotNull(state);
        assertEquals("channel:telegram", state.getKey());
    }

    @Test
    void shouldReturnLlmBucketState() {
        rateLimiter.tryConsumeLlm("openai");

        BucketState state = rateLimiter.getBucketState("llm:openai");
        assertNotNull(state);
        assertEquals("llm:openai", state.getKey());
    }

    // ===== Remaining tokens =====

    @Test
    void shouldDecrementRemainingTokens() {
        RateLimitResult first = rateLimiter.tryConsume();
        RateLimitResult second = rateLimiter.tryConsume();

        assertTrue(first.getRemainingTokens() > second.getRemainingTokens());
    }
}
