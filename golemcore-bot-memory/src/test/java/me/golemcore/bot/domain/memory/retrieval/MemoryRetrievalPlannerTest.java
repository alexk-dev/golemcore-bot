package me.golemcore.bot.domain.memory.retrieval;

import me.golemcore.bot.domain.memory.model.MemoryRetrievalPlan;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryRetrievalPlannerTest {

    private RuntimeConfigService runtimeConfigService;
    private MemoryRetrievalPlanner memoryRetrievalPlanner;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getMemorySoftPromptBudgetTokens()).thenReturn(1800);
        when(runtimeConfigService.getMemoryMaxPromptBudgetTokens()).thenReturn(3500);
        when(runtimeConfigService.getMemoryWorkingTopK()).thenReturn(6);
        when(runtimeConfigService.getMemoryEpisodicTopK()).thenReturn(8);
        when(runtimeConfigService.getMemorySemanticTopK()).thenReturn(6);
        when(runtimeConfigService.getMemoryProceduralTopK()).thenReturn(4);
        when(runtimeConfigService.getMemoryRetrievalLookbackDays()).thenReturn(120);
        memoryRetrievalPlanner = new MemoryRetrievalPlanner(runtimeConfigService);
    }

    @Test
    void shouldUseRuntimeDefaultsAndClampLookbackForNullQuery() {
        MemoryRetrievalPlan plan = memoryRetrievalPlanner.plan(null);

        assertEquals("global", plan.getRequestedScope());
        assertEquals(List.of("global"), plan.getRequestedScopes());
        assertEquals(90, plan.getEpisodicLookbackDays());
        assertEquals(1800, plan.getQuery().getSoftPromptBudgetTokens());
        assertEquals(3500, plan.getQuery().getMaxPromptBudgetTokens());
    }

    @Test
    void shouldNormalizeScopeChainAndKeepGlobalFallback() {
        MemoryRetrievalPlan plan = memoryRetrievalPlanner.plan(MemoryQuery.builder().scope("global")
                .scopeChain(List.of("task:build-cache", "task:build-cache")).build());

        assertEquals("task:build-cache", plan.getRequestedScope());
        assertEquals(List.of("task:build-cache", "global"), plan.getRequestedScopes());
    }
}
