package me.golemcore.bot.ratelimit;

import me.golemcore.bot.domain.model.BucketState;
import me.golemcore.bot.domain.model.RateLimitResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final String PROVIDER_OPENAI = "openai";

    private RuntimeConfigService runtimeConfigService;
    private TokenBucketRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isRateLimitEnabled()).thenReturn(true);
        when(runtimeConfigService.getUserRequestsPerMinute()).thenReturn(5);
        when(runtimeConfigService.getChannelMessagesPerSecond()).thenReturn(10);
        when(runtimeConfigService.getLlmRequestsPerMinute()).thenReturn(20);

        rateLimiter = new TokenBucketRateLimiter(runtimeConfigService);
    }

    // ===== Disabled =====

    @Test
    void shouldAlwaysAllowWhenDisabled() {
        when(runtimeConfigService.isRateLimitEnabled()).thenReturn(false);
        rateLimiter = new TokenBucketRateLimiter(runtimeConfigService);

        for (int i = 0; i < 100; i++) {
            RateLimitResult result = rateLimiter.tryConsume();
            assertTrue(result.isAllowed());
            assertEquals(Long.MAX_VALUE, result.getRemainingTokens());
        }
    }

    @Test
    void shouldAlwaysAllowChannelWhenDisabled() {
        when(runtimeConfigService.isRateLimitEnabled()).thenReturn(false);
        rateLimiter = new TokenBucketRateLimiter(runtimeConfigService);

        RateLimitResult result = rateLimiter.tryConsumeChannel(CHANNEL_TELEGRAM);
        assertTrue(result.isAllowed());
        assertEquals(Long.MAX_VALUE, result.getRemainingTokens());
    }

    @Test
    void shouldAlwaysAllowLlmWhenDisabled() {
        when(runtimeConfigService.isRateLimitEnabled()).thenReturn(false);
        rateLimiter = new TokenBucketRateLimiter(runtimeConfigService);

        RateLimitResult result = rateLimiter.tryConsumeLlm(PROVIDER_OPENAI);
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
        RateLimitResult telegram = rateLimiter.tryConsumeChannel(CHANNEL_TELEGRAM);
        RateLimitResult discord = rateLimiter.tryConsumeChannel("discord");

        assertTrue(telegram.isAllowed());
        assertTrue(discord.isAllowed());
    }

    @Test
    void shouldDenyChannelRequestsExceedingLimit() {
        for (int i = 0; i < 10; i++) {
            rateLimiter.tryConsumeChannel(CHANNEL_TELEGRAM);
        }

        RateLimitResult result = rateLimiter.tryConsumeChannel(CHANNEL_TELEGRAM);
        assertFalse(result.isAllowed());
    }

    // ===== LLM rate limit =====

    @Test
    void shouldTrackLlmRateLimitsSeparately() {
        RateLimitResult openai = rateLimiter.tryConsumeLlm(PROVIDER_OPENAI);
        RateLimitResult anthropic = rateLimiter.tryConsumeLlm("anthropic");

        assertTrue(openai.isAllowed());
        assertTrue(anthropic.isAllowed());
    }

    @Test
    void shouldDenyLlmRequestsExceedingLimit() {
        for (int i = 0; i < 20; i++) {
            rateLimiter.tryConsumeLlm(PROVIDER_OPENAI);
        }

        RateLimitResult result = rateLimiter.tryConsumeLlm(PROVIDER_OPENAI);
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
        assertTrue(rateLimiter.tryConsumeChannel(CHANNEL_TELEGRAM).isAllowed());
    }

    @Test
    void shouldIsolateGlobalFromLlmBuckets() {
        // Exhaust global limit
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryConsume();
        }
        assertFalse(rateLimiter.tryConsume().isAllowed());

        // LLM limit should still be available
        assertTrue(rateLimiter.tryConsumeLlm(PROVIDER_OPENAI).isAllowed());
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
        rateLimiter.tryConsumeChannel(CHANNEL_TELEGRAM);

        BucketState state = rateLimiter.getBucketState("channel:telegram");
        assertNotNull(state);
        assertEquals("channel:telegram", state.getKey());
    }

    @Test
    void shouldReturnLlmBucketState() {
        rateLimiter.tryConsumeLlm(PROVIDER_OPENAI);

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
