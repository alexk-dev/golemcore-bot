package me.golemcore.bot.domain.system.toolloop.resilience;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderFallbackSelectorTest {

    @Test
    void shouldReturnNullWhenContextIsNull() {
        assertNull(selector.selectNext(null));
    }

    @Test
    void shouldReturnNullWhenFallbackBindingIsMissing() {
        AgentContext context = AgentContext.builder().modelTier("balanced").build();

        assertNull(selector.selectNext(context));
        assertNull(context.getAttribute(ContextAttributes.RESILIENCE_L2_FALLBACK_MODEL));
    }

    @Test
    void shouldReturnNullWhenBindingHasNoFallbacksConfigured() {
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("sequential")
                .fallbacks(List.of())
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("balanced").build();

        assertNull(selector.selectNext(context));
    }

    @Test
    void shouldReturnNullWhenOnlyCurrentFallbackCandidateRemains() {
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("weighted")
                .fallbacks(List.of(RuntimeConfig.TierFallback.builder().model("anthropic/claude-sonnet-4").build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("balanced").build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "anthropic/claude-sonnet-4");

        assertNull(selector.selectNext(context));
    }

    @Test
    void shouldUseFirstSequentialFallbackWhenCurrentModelIsMissing() {
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("sequential")
                .fallbacks(List.of(
                        RuntimeConfig.TierFallback.builder().model("anthropic/claude-sonnet-4").build(),
                        RuntimeConfig.TierFallback.builder().model("google/gemini-2.5-pro").build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("balanced").build();

        assertEquals("anthropic/claude-sonnet-4", selector.selectNext(context).model());
    }

    @Test
    void shouldUseDefaultCapWhenResilienceConfigIsNull() {
        when(runtimeConfigService.getResilienceConfig())
                .thenReturn(RuntimeConfig.ResilienceConfig.builder().build());
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("round_robin")
                .fallbacks(List.of(
                        RuntimeConfig.TierFallback.builder().model("a").build(),
                        RuntimeConfig.TierFallback.builder().model("b").build(),
                        RuntimeConfig.TierFallback.builder().model("c").build(),
                        RuntimeConfig.TierFallback.builder().model("d").build(),
                        RuntimeConfig.TierFallback.builder().model("e").build(),
                        RuntimeConfig.TierFallback.builder().model("f").build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("default").build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "openai/gpt-5");
        assertNotNull(selector.selectNext(context));
        context.setAttribute(ContextAttributes.LLM_MODEL, "a");
        assertNotNull(selector.selectNext(context));
        context.setAttribute(ContextAttributes.LLM_MODEL, "b");
        assertNotNull(selector.selectNext(context));
        context.setAttribute(ContextAttributes.LLM_MODEL, "c");
        assertNotNull(selector.selectNext(context));
        context.setAttribute(ContextAttributes.LLM_MODEL, "d");
        assertNotNull(selector.selectNext(context));
        context.setAttribute(ContextAttributes.LLM_MODEL, "e");
        assertNull(selector.selectNext(context));
    }

    @Test
    void shouldUseBalancedTierAndDefaultCapWhenConfigValuesAreMissing() {
        when(runtimeConfigService.getResilienceConfig()).thenReturn(RuntimeConfig.ResilienceConfig.builder()
                .l2ProviderFallbackMaxAttempts(null)
                .build());
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("sequential")
                .fallbacks(List.of(RuntimeConfig.TierFallback.builder().model("anthropic/claude-sonnet-4").build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier(" ").build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "openai/gpt-5");

        ProviderFallbackSelector.FallbackSelection selection = selector.selectNext(context);

        assertNotNull(selection);
        assertEquals("anthropic/claude-sonnet-4", selection.model());
    }

    @Test
    void shouldIgnoreInvalidFallbackEntriesAndUseFirstValidSequentialCandidate() {
        List<RuntimeConfig.TierFallback> fallbacks = new ArrayList<>();
        fallbacks.add(null);
        fallbacks.add(RuntimeConfig.TierFallback.builder().build());
        RuntimeConfig.TierFallback blankFallback = mock(RuntimeConfig.TierFallback.class);
        when(blankFallback.getModel()).thenReturn(" ");
        fallbacks.add(blankFallback);
        fallbacks.add(RuntimeConfig.TierFallback.builder().model("anthropic/claude-sonnet-4").build());
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("sequential")
                .fallbacks(fallbacks)
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("balanced").build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "unknown/provider");

        ProviderFallbackSelector.FallbackSelection selection = selector.selectNext(context);

        assertNotNull(selection);
        assertEquals("anthropic/claude-sonnet-4", selection.model());
    }

    @Test
    void shouldUseDefaultWeightWhenWeightedFallbackWeightIsMissingOrNonPositive() {
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("weighted")
                .fallbacks(List.of(
                        RuntimeConfig.TierFallback.builder().model("anthropic/claude-sonnet-4").weight(0.0).build(),
                        RuntimeConfig.TierFallback.builder().model("google/gemini-2.5-pro").weight(null).build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("balanced").build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "openai/gpt-5");

        assertEquals("anthropic/claude-sonnet-4", selector.selectNext(context).model());
        assertEquals("google/gemini-2.5-pro", selector.selectNext(context).model());
    }

    private RuntimeConfigService runtimeConfigService;
    private ProviderFallbackSelector selector;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getResilienceConfig()).thenReturn(RuntimeConfig.ResilienceConfig.builder()
                .l2ProviderFallbackMaxAttempts(5)
                .build());
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
    void shouldStopSequentialSelectionAtConfiguredCap() {
        when(runtimeConfigService.getResilienceConfig()).thenReturn(RuntimeConfig.ResilienceConfig.builder()
                .l2ProviderFallbackMaxAttempts(2)
                .build());
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("sequential")
                .fallbacks(List.of(
                        RuntimeConfig.TierFallback.builder().model("anthropic/claude-sonnet-4").build(),
                        RuntimeConfig.TierFallback.builder().model("google/gemini-2.5-pro").build(),
                        RuntimeConfig.TierFallback.builder().model("openrouter/openai/gpt-5-mini").build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("balanced").build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "openai/gpt-5");
        assertEquals("anthropic/claude-sonnet-4", selector.selectNext(context).model());

        context.setAttribute(ContextAttributes.LLM_MODEL, "anthropic/claude-sonnet-4");
        assertEquals("google/gemini-2.5-pro", selector.selectNext(context).model());

        context.setAttribute(ContextAttributes.LLM_MODEL, "google/gemini-2.5-pro");
        assertNull(selector.selectNext(context));
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

    @Test
    void shouldStopRoundRobinSelectionAtConfiguredCap() {
        when(runtimeConfigService.getResilienceConfig()).thenReturn(RuntimeConfig.ResilienceConfig.builder()
                .l2ProviderFallbackMaxAttempts(2)
                .build());
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("round_robin")
                .fallbacks(List.of(
                        RuntimeConfig.TierFallback.builder().model("anthropic/claude-sonnet-4").build(),
                        RuntimeConfig.TierFallback.builder().model("google/gemini-2.5-pro").build(),
                        RuntimeConfig.TierFallback.builder().model("openrouter/openai/gpt-5-mini").build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("balanced").build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "openai/gpt-5");
        assertEquals("anthropic/claude-sonnet-4", selector.selectNext(context).model());

        context.setAttribute(ContextAttributes.LLM_MODEL, "anthropic/claude-sonnet-4");
        assertEquals("google/gemini-2.5-pro", selector.selectNext(context).model());

        context.setAttribute(ContextAttributes.LLM_MODEL, "google/gemini-2.5-pro");
        assertNull(selector.selectNext(context));
    }

    @Test
    void shouldStopWeightedSelectionAtConfiguredCap() {
        when(runtimeConfigService.getResilienceConfig()).thenReturn(RuntimeConfig.ResilienceConfig.builder()
                .l2ProviderFallbackMaxAttempts(2)
                .build());
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("weighted")
                .fallbacks(List.of(
                        RuntimeConfig.TierFallback.builder().model("anthropic/claude-sonnet-4").weight(3.0).build(),
                        RuntimeConfig.TierFallback.builder().model("google/gemini-2.5-pro").weight(1.0).build(),
                        RuntimeConfig.TierFallback.builder().model("openrouter/openai/gpt-5-mini").weight(1.0).build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("balanced").build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "openai/gpt-5");
        assertEquals("anthropic/claude-sonnet-4", selector.selectNext(context).model());

        context.setAttribute(ContextAttributes.LLM_MODEL, "anthropic/claude-sonnet-4");
        assertNotNull(selector.selectNext(context));

        context.setAttribute(ContextAttributes.LLM_MODEL, "google/gemini-2.5-pro");
        assertNull(selector.selectNext(context));
    }

    @Test
    void clearOverrideShouldIgnoreNullContext() {
        selector.clearOverride(null);
    }

    @Test
    void shouldUseDefaultCapWhenConfiguredValueIsZero() {
        when(runtimeConfigService.getResilienceConfig()).thenReturn(RuntimeConfig.ResilienceConfig.builder()
                .l2ProviderFallbackMaxAttempts(0)
                .build());
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("round_robin")
                .fallbacks(List.of(
                        RuntimeConfig.TierFallback.builder().model("a").build(),
                        RuntimeConfig.TierFallback.builder().model("b").build(),
                        RuntimeConfig.TierFallback.builder().model("c").build(),
                        RuntimeConfig.TierFallback.builder().model("d").build(),
                        RuntimeConfig.TierFallback.builder().model("e").build(),
                        RuntimeConfig.TierFallback.builder().model("f").build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier(null).build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "openai/gpt-5");
        assertNotNull(selector.selectNext(context));
        context.setAttribute(ContextAttributes.LLM_MODEL, "a");
        assertNotNull(selector.selectNext(context));
        context.setAttribute(ContextAttributes.LLM_MODEL, "b");
        assertNotNull(selector.selectNext(context));
        context.setAttribute(ContextAttributes.LLM_MODEL, "c");
        assertNotNull(selector.selectNext(context));
        context.setAttribute(ContextAttributes.LLM_MODEL, "d");
        assertNotNull(selector.selectNext(context));
        context.setAttribute(ContextAttributes.LLM_MODEL, "e");
        assertNull(selector.selectNext(context));
    }

    @Test
    void shouldResolveDefaultTierAndUnknownCurrentModelForRoundRobin() {
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("round_robin")
                .fallbacks(List.of(
                        RuntimeConfig.TierFallback.builder().model("anthropic/claude-sonnet-4").build(),
                        RuntimeConfig.TierFallback.builder().model("google/gemini-2.5-pro").build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("default").build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "unknown/provider");

        ProviderFallbackSelector.FallbackSelection selection = selector.selectNext(context);

        assertNotNull(selection);
        assertEquals("anthropic/claude-sonnet-4", selection.model());
    }

    @Test
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void privateSelectorsShouldHandleEmptyFallbackCollections() throws Exception {
        Method sequential = ProviderFallbackSelector.class.getDeclaredMethod("selectSequential", List.class,
                String.class);
        sequential.setAccessible(true);
        Method roundRobin = ProviderFallbackSelector.class.getDeclaredMethod("selectRoundRobin", List.class,
                String.class);
        roundRobin.setAccessible(true);
        Method weighted = ProviderFallbackSelector.class.getDeclaredMethod("selectWeighted", AgentContext.class,
                List.class);
        weighted.setAccessible(true);

        AgentContext context = AgentContext.builder().build();

        assertNull(sequential.invoke(selector, List.of(), null));
        assertNull(roundRobin.invoke(selector, List.of(), null));
        assertNull(weighted.invoke(selector, context, List.of()));
    }

    @Test
    void shouldReturnNullWhenBindingFallbackListIsNull() {
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("sequential")
                .fallbacks(null)
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("balanced").build();

        assertNull(selector.selectNext(context));
    }

    @Test
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void privateHelpersShouldHandleNullAndBlankCurrentTierAndModelValues() throws Exception {
        Method resolveTier = ProviderFallbackSelector.class.getDeclaredMethod("resolveTier", AgentContext.class);
        resolveTier.setAccessible(true);
        Method resolveCandidates = ProviderFallbackSelector.class.getDeclaredMethod("resolveCandidates", List.class,
                String.class);
        resolveCandidates.setAccessible(true);
        Method selectSequential = ProviderFallbackSelector.class.getDeclaredMethod("selectSequential", List.class,
                String.class);
        selectSequential.setAccessible(true);
        Method selectRoundRobin = ProviderFallbackSelector.class.getDeclaredMethod("selectRoundRobin", List.class,
                String.class);
        selectRoundRobin.setAccessible(true);

        AgentContext nullTierContext = AgentContext.builder().modelTier(null).build();
        AgentContext blankTierContext = AgentContext.builder().modelTier(" ").build();
        assertEquals("balanced", resolveTier.invoke(selector, nullTierContext));
        assertEquals("balanced", resolveTier.invoke(selector, blankTierContext));

        List<RuntimeConfig.TierFallback> fallbacks = List.of(
                RuntimeConfig.TierFallback.builder().model("a").build(),
                RuntimeConfig.TierFallback.builder().model("b").build());
        @SuppressWarnings("unchecked")
        List<RuntimeConfig.TierFallback> candidates = (List<RuntimeConfig.TierFallback>) resolveCandidates.invoke(
                selector, fallbacks, null);
        assertEquals(2, candidates.size());
        assertEquals("a", ((RuntimeConfig.TierFallback) selectSequential.invoke(selector, fallbacks, " ")).getModel());
        assertEquals("a", ((RuntimeConfig.TierFallback) selectRoundRobin.invoke(selector, fallbacks, " ")).getModel());
        assertEquals("a",
                ((RuntimeConfig.TierFallback) selectRoundRobin.invoke(selector, fallbacks, "unknown")).getModel());
    }

    @Test
    void shouldUseFirstRoundRobinFallbackWhenCurrentModelIsNull() {
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("round_robin")
                .fallbacks(List.of(
                        RuntimeConfig.TierFallback.builder().model("anthropic/claude-sonnet-4").build(),
                        RuntimeConfig.TierFallback.builder().model("google/gemini-2.5-pro").build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("balanced").build();

        assertEquals("anthropic/claude-sonnet-4", selector.selectNext(context).model());
    }

    @Test
    void shouldReturnNullWhenSelectedFallbackModelBecomesNull() {
        RuntimeConfig.TierFallback fallback = mock(RuntimeConfig.TierFallback.class);
        when(fallback.getModel()).thenReturn("anthropic/claude-sonnet-4", (String) null);
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("sequential")
                .fallbacks(List.of(fallback))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);

        AgentContext context = AgentContext.builder().modelTier("balanced").build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "openai/gpt-5");

        assertNull(selector.selectNext(context));
    }

}
