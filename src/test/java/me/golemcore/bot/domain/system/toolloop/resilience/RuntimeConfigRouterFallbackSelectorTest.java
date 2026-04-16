package me.golemcore.bot.domain.system.toolloop.resilience;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FallbackModes;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeConfigRouterFallbackSelectorTest {

    private RuntimeConfigService runtimeConfigService;
    private AgentContext context;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        context = AgentContext.builder().modelTier("deep").build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "provider/primary");
    }

    @Test
    void shouldApplySequentialFallbackAndSkipAlreadyAttemptedModels() {
        when(runtimeConfigService.getModelTierBinding("deep")).thenReturn(binding(
                FallbackModes.SEQUENTIAL,
                fallback("provider/fallback-a", "low", null),
                fallback("provider/fallback-b", "medium", null)));
        RuntimeConfigRouterFallbackSelector selector = selector(new Random(0));

        Optional<RouterFallbackSelector.Selection> first = selector.selectNext(context);
        Optional<RouterFallbackSelector.Selection> second = selector.selectNext(context);

        assertTrue(first.isPresent());
        assertEquals("provider/fallback-a", first.get().model());
        assertEquals("low", first.get().reasoning());
        assertTrue(second.isPresent());
        assertEquals("provider/fallback-b", second.get().model());
        assertEquals("medium", context.getAttribute(ContextAttributes.LLM_REASONING));
        assertEquals(List.of("provider/fallback-a", "provider/fallback-b"),
                context.getAttribute(ContextAttributes.RESILIENCE_L2_ATTEMPTED_MODELS));
    }

    @Test
    void shouldUseRoundRobinCursor() {
        when(runtimeConfigService.getModelTierBinding("deep")).thenReturn(binding(
                FallbackModes.ROUND_ROBIN,
                fallback("provider/fallback-a", null, null),
                fallback("provider/fallback-b", null, null),
                fallback("provider/fallback-c", null, null)));
        context.setAttribute(ContextAttributes.RESILIENCE_L2_ROUND_ROBIN_CURSOR, 1);
        RuntimeConfigRouterFallbackSelector selector = selector(new Random(0));

        Optional<RouterFallbackSelector.Selection> selection = selector.selectNext(context);

        assertTrue(selection.isPresent());
        assertEquals("provider/fallback-b", selection.get().model());
        assertEquals(Integer.valueOf(2), context.getAttribute(ContextAttributes.RESILIENCE_L2_ROUND_ROBIN_CURSOR));
    }

    @Test
    void shouldSkipUnavailableFallbackCandidates() {
        when(runtimeConfigService.getModelTierBinding("deep")).thenReturn(binding(
                FallbackModes.SEQUENTIAL,
                fallback("provider/fallback-a", null, null),
                fallback("provider/fallback-b", null, null)));
        RuntimeConfigRouterFallbackSelector selector = selector(new Random(0));

        Optional<RouterFallbackSelector.Selection> selection = selector.selectNext(context,
                model -> !"provider/fallback-a".equals(model));

        assertTrue(selection.isPresent());
        assertEquals("provider/fallback-b", selection.get().model());
    }

    @Test
    void shouldUseWeightedSelection() {
        when(runtimeConfigService.getModelTierBinding("deep")).thenReturn(binding(
                FallbackModes.WEIGHTED,
                fallback("provider/fallback-a", null, 1.0d),
                fallback("provider/fallback-b", null, 9.0d)));
        RuntimeConfigRouterFallbackSelector selector = selector(new Random(0));

        Optional<RouterFallbackSelector.Selection> selection = selector.selectNext(context);

        assertTrue(selection.isPresent());
        assertEquals("provider/fallback-b", selection.get().model());
        assertEquals(FallbackModes.WEIGHTED, context.getAttribute(ContextAttributes.RESILIENCE_L2_FALLBACK_MODE));
    }

    @Test
    void shouldReturnEmptyWhenFallbacksAreExhausted() {
        when(runtimeConfigService.getModelTierBinding("deep")).thenReturn(binding(
                FallbackModes.SEQUENTIAL,
                fallback("provider/fallback-a", null, null),
                fallback("provider/fallback-b", null, null)));
        context.setAttribute(ContextAttributes.RESILIENCE_L2_ATTEMPTED_MODELS,
                List.of("provider/fallback-a", "provider/fallback-b"));
        RuntimeConfigRouterFallbackSelector selector = selector(new Random(0));

        Optional<RouterFallbackSelector.Selection> selection = selector.selectNext(context);

        assertTrue(selection.isEmpty());
    }

    @Test
    void shouldClearFallbackState() {
        when(runtimeConfigService.getModelTierBinding("deep")).thenReturn(binding(
                FallbackModes.SEQUENTIAL,
                fallback("provider/fallback-a", "low", null)));
        RuntimeConfigRouterFallbackSelector selector = selector(new Random(0));
        selector.selectNext(context);

        selector.clear(context);

        assertFalse(context.getAttributes().containsKey(ContextAttributes.RESILIENCE_L2_FALLBACK_MODEL));
        assertFalse(context.getAttributes().containsKey(ContextAttributes.RESILIENCE_L2_FALLBACK_REASONING));
        assertFalse(context.getAttributes().containsKey(ContextAttributes.RESILIENCE_L2_ATTEMPTED_MODELS));
    }

    private RuntimeConfigRouterFallbackSelector selector(Random random) {
        return new RuntimeConfigRouterFallbackSelector(runtimeConfigService, random);
    }

    private RuntimeConfig.TierBinding binding(String mode, RuntimeConfig.TierFallback... fallbacks) {
        return RuntimeConfig.TierBinding.builder()
                .model("provider/primary")
                .fallbackMode(mode)
                .fallbacks(List.of(fallbacks))
                .build();
    }

    private RuntimeConfig.TierFallback fallback(String model, String reasoning, Double weight) {
        return RuntimeConfig.TierFallback.builder()
                .model(model)
                .reasoning(reasoning)
                .weight(weight)
                .build();
    }
}
