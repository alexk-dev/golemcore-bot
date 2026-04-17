package me.golemcore.bot.domain.system.toolloop.resilience;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderFallbackSelectorTest {

    private RuntimeConfigService runtimeConfigService;
    private ProviderFallbackSelector selector;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        selector = new ProviderFallbackSelector(runtimeConfigService);
    }

    @Test
    void shouldSelectSequentialFallbacksInOrderWithoutWrap() {
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("sequential")
                .fallbacks(List.of(
                        RuntimeConfig.TierFallback.builder().model("anthropic/claude-sonnet-4").build(),
                        RuntimeConfig.TierFallback.builder().model("google/gemini-2.5-pro").build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("balanced").build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "openai/gpt-5");

        ProviderFallbackSelector.FallbackSelection first = selector.selectNext(context);
        assertNotNull(first);
        assertEquals("anthropic/claude-sonnet-4", first.model());
        assertEquals("anthropic/claude-sonnet-4", context.getAttribute(ContextAttributes.RESILIENCE_L2_FALLBACK_MODEL));

        context.setAttribute(ContextAttributes.LLM_MODEL, "anthropic/claude-sonnet-4");
        ProviderFallbackSelector.FallbackSelection second = selector.selectNext(context);
        assertNotNull(second);
        assertEquals("google/gemini-2.5-pro", second.model());

        context.setAttribute(ContextAttributes.LLM_MODEL, "google/gemini-2.5-pro");
        ProviderFallbackSelector.FallbackSelection exhausted = selector.selectNext(context);
        assertNull(exhausted);
        assertNull(context.getAttribute(ContextAttributes.RESILIENCE_L2_FALLBACK_MODEL));
    }

    @Test
    void shouldWrapRoundRobinFallbacks() {
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("round_robin")
                .fallbacks(List.of(
                        RuntimeConfig.TierFallback.builder().model("anthropic/claude-sonnet-4").build(),
                        RuntimeConfig.TierFallback.builder().model("google/gemini-2.5-pro").build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("balanced").build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "openai/gpt-5");
        assertEquals("anthropic/claude-sonnet-4", selector.selectNext(context).model());

        context.setAttribute(ContextAttributes.LLM_MODEL, "anthropic/claude-sonnet-4");
        assertEquals("google/gemini-2.5-pro", selector.selectNext(context).model());

        context.setAttribute(ContextAttributes.LLM_MODEL, "google/gemini-2.5-pro");
        assertEquals("anthropic/claude-sonnet-4", selector.selectNext(context).model());
    }

    @Test
    void shouldDistributeWeightedFallbacksUsingSmoothWeightedRoundRobin() {
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("weighted")
                .fallbacks(List.of(
                        RuntimeConfig.TierFallback.builder().model("anthropic/claude-sonnet-4").weight(2.0).build(),
                        RuntimeConfig.TierFallback.builder().model("google/gemini-2.5-pro").weight(1.0).build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("balanced").build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "openai/gpt-5");

        assertEquals("anthropic/claude-sonnet-4", selector.selectNext(context).model());
        assertEquals("google/gemini-2.5-pro", selector.selectNext(context).model());
        assertEquals("anthropic/claude-sonnet-4", selector.selectNext(context).model());
    }
}
