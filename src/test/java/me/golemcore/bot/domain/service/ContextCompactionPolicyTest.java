package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextCompactionPolicyTest {

    private RuntimeConfigService runtimeConfigService;
    private ModelSelectionService modelSelectionService;
    private ContextCompactionPolicy policy;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionModelThresholdRatio()).thenReturn(0.95d);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(10_000);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(20_000);
        policy = new ContextCompactionPolicy(runtimeConfigService, modelSelectionService);
    }

    @Test
    void constructor_shouldRequireRuntimeConfigService() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> new ContextCompactionPolicy(null, modelSelectionService));

        assertEquals("runtimeConfigService", thrown.getMessage());
    }

    @Test
    void constructor_shouldRequireModelSelectionService() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> new ContextCompactionPolicy(runtimeConfigService, null));

        assertEquals("modelSelectionService", thrown.getMessage());
    }

    @Test
    void shouldExposeCompactionEnabledFlagFromRuntimeConfig() {
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(true);

        assertTrue(policy.isCompactionEnabled());
    }

    @Test
    void shouldResolveCompactionKeepLastFromRuntimeConfig() {
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(7);

        assertEquals(7, policy.resolveCompactionKeepLast());
    }

    @Test
    void shouldResolvePreflightKeepLastByHalvingConfiguredKeepLastAcrossAttempts() {
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(8);

        assertEquals(8, policy.resolvePreflightKeepLast(20, 1));
        assertEquals(4, policy.resolvePreflightKeepLast(20, 2));
        assertEquals(2, policy.resolvePreflightKeepLast(20, 3));
    }

    @Test
    void shouldClampPreflightKeepLastBelowTotalMessages() {
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(20);

        assertEquals(4, policy.resolvePreflightKeepLast(5, 1));
    }

    @Test
    void shouldResolveHistoryBudgetFromModelRatioIgnoringLegacyAbsoluteThreshold() {
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(1_000);

        int threshold = policy.resolveHistoryThreshold(AgentContext.builder().build());

        assertEquals(19_000, threshold);
    }

    @Test
    void shouldResolveHistoryTokenThresholdWithLegacyModelSafetyCap() {
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("token_threshold");
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(20_000);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(10_000);

        int threshold = policy.resolveHistoryThreshold(AgentContext.builder().build());

        assertEquals(8_000, threshold);
    }

    @Test
    void shouldResolveFullRequestBudgetWithOutputReserve() {
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionModelThresholdRatio()).thenReturn(0.95d);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(10_000);

        int threshold = policy.resolveFullRequestThreshold(AgentContext.builder().build());

        assertEquals(8_976, threshold);
    }

    @Test
    void shouldBypassFullRequestBudgetWhenModelAndConfiguredThresholdAreMissing() {
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(0);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(0);

        int threshold = policy.resolveFullRequestThreshold(AgentContext.builder().build());

        assertEquals(Integer.MAX_VALUE, threshold);
    }

    @Test
    void shouldFallBackToConfiguredThresholdWhenModelLookupFails() {
        when(modelSelectionService.resolveMaxInputTokensForContext(any()))
                .thenThrow(new IllegalStateException("missing model"));
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(12_345);

        assertEquals(12_345, policy.resolveHistoryThreshold(AgentContext.builder().build()));
        // The configured fallback is a user-declared wire cap, not a model
        // max-input-tokens value. Preflight must return it verbatim — treating
        // it as modelMax and subtracting an output reserve would silently
        // shrink the operator's intended budget by 1024-32768 tokens.
        assertEquals(12_345, policy.resolveFullRequestThreshold(AgentContext.builder().build()));
    }

    @Test
    void shouldUseConfiguredFullRequestThresholdVerbatimWhenRegistryReturnsZero() {
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(0);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(200_000);

        int threshold = policy.resolveFullRequestThreshold(AgentContext.builder().build());

        assertEquals(200_000, threshold,
                "configured fallback must be used verbatim — preflight must not subtract an output reserve "
                        + "from a user-declared wire cap when the model registry is silent");
    }

    @Test
    void shouldClampInvalidRatioToDefaultInHistoryPath() {
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionModelThresholdRatio()).thenReturn(-0.5d);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(20_000);

        int threshold = policy.resolveHistoryThreshold(AgentContext.builder().build());

        assertEquals(19_000, threshold);
    }

    @Test
    void shouldClampRatioAboveOneToDefaultInHistoryPath() {
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionModelThresholdRatio()).thenReturn(2.5d);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(20_000);

        int threshold = policy.resolveHistoryThreshold(AgentContext.builder().build());

        assertEquals(19_000, threshold);
    }

    @Test
    void shouldBypassHistoryThresholdWhenModelAndConfiguredThresholdAreMissing() {
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(0);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(0);

        int threshold = policy.resolveHistoryThreshold(AgentContext.builder().build());

        assertEquals(Integer.MAX_VALUE, threshold);
    }

    @Test
    void shouldBypassHistoryThresholdWhenModelLookupFailsAndConfiguredIsZero() {
        when(modelSelectionService.resolveMaxInputTokensForContext(any()))
                .thenThrow(new IllegalStateException("missing model"));
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(0);

        int threshold = policy.resolveHistoryThreshold(AgentContext.builder().build());

        assertEquals(Integer.MAX_VALUE, threshold);
    }

    @Test
    void shouldFloorHistoryTokenThresholdToAtLeastOne() {
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("token_threshold");
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(1);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(1);

        int threshold = policy.resolveHistoryThreshold(AgentContext.builder().build());

        assertTrue(threshold >= 1, "expected floor clamp to 1, got " + threshold);
    }
}
