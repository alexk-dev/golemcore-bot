package me.golemcore.bot.domain.runtimeconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.golemcore.bot.domain.model.RuntimeConfig;
import org.junit.jupiter.api.Test;

class ToolLoopRuntimeConfigViewTest {

    @Test
    void fallsBackToTurnBudgetsWhenToolLoopSectionIsAbsent() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .turn(RuntimeConfig.TurnConfig.builder().maxLlmCalls(11).maxToolExecutions(22).build()).build();
        runtimeConfig.setToolLoop(null);
        ToolLoopRuntimeConfigView view = view(runtimeConfig);

        assertEquals(11, view.getToolLoopMaxLlmCalls());
        assertEquals(22, view.getToolLoopMaxToolExecutions());
    }

    @Test
    void usesToolLoopDefaultsWhenSectionExistsButValuesAreMissing() {
        ToolLoopRuntimeConfigView view = view(
                RuntimeConfig.builder().toolLoop(RuntimeConfig.ToolLoopConfig.builder().build()).build());

        assertEquals(20, view.getToolLoopMaxLlmCalls());
        assertEquals(80, view.getToolLoopMaxToolExecutions());
        assertTrue(view.isToolRepeatGuardEnabled());
        assertFalse(view.isToolRepeatGuardShadowMode());
        assertEquals(2, view.getToolRepeatGuardMaxSameObservePerTurn());
        assertEquals(2, view.getToolRepeatGuardMaxSameUnknownPerTurn());
        assertEquals(4, view.getToolRepeatGuardMaxBlockedRepeatsPerTurn());
        assertEquals(60L, view.getToolRepeatGuardMinPollIntervalSeconds());
        assertEquals(120L, view.getToolRepeatGuardAutoLedgerTtlMinutes());
    }

    @Test
    void returnsConfiguredToolLoopRepeatGuardValues() {
        ToolLoopRuntimeConfigView view = view(RuntimeConfig.builder()
                .toolLoop(RuntimeConfig.ToolLoopConfig.builder().maxLlmCalls(7).maxToolExecutions(9)
                        .repeatGuardEnabled(false).repeatGuardShadowMode(true).repeatGuardMaxSameObservePerTurn(3)
                        .repeatGuardMaxSameUnknownPerTurn(4).repeatGuardMaxBlockedRepeatsPerTurn(5)
                        .repeatGuardMinPollIntervalSeconds(30L).repeatGuardAutoLedgerTtlMinutes(45L).build())
                .build());

        assertEquals(7, view.getToolLoopMaxLlmCalls());
        assertEquals(9, view.getToolLoopMaxToolExecutions());
        assertFalse(view.isToolRepeatGuardEnabled());
        assertTrue(view.isToolRepeatGuardShadowMode());
        assertEquals(3, view.getToolRepeatGuardMaxSameObservePerTurn());
        assertEquals(4, view.getToolRepeatGuardMaxSameUnknownPerTurn());
        assertEquals(5, view.getToolRepeatGuardMaxBlockedRepeatsPerTurn());
        assertEquals(30L, view.getToolRepeatGuardMinPollIntervalSeconds());
        assertEquals(45L, view.getToolRepeatGuardAutoLedgerTtlMinutes());
    }

    private ToolLoopRuntimeConfigView view(RuntimeConfig runtimeConfig) {
        return () -> runtimeConfig;
    }
}
