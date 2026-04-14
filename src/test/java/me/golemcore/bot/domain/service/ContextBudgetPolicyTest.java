package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextBudgetPolicyTest {

    private RuntimeConfigService runtimeConfigService;
    private ModelSelectionService modelSelectionService;
    private ContextBudgetPolicy policy;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionModelThresholdRatio()).thenReturn(0.95d);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(10_000);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(20_000);
        policy = new ContextBudgetPolicy(runtimeConfigService, modelSelectionService);
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
        assertEquals(11_321, policy.resolveFullRequestThreshold(AgentContext.builder().build()));
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
