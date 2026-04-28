package me.golemcore.bot.domain.memory.retrieval;

import me.golemcore.bot.domain.memory.model.MemoryContextRequest;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryQueryFactoryTest {

    private RuntimeConfigService runtimeConfigService;
    private MemoryQueryFactory memoryQueryFactory;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getMemorySoftPromptBudgetTokens()).thenReturn(1800);
        when(runtimeConfigService.getMemoryMaxPromptBudgetTokens()).thenReturn(3500);
        when(runtimeConfigService.getMemoryWorkingTopK()).thenReturn(6);
        when(runtimeConfigService.getMemoryEpisodicTopK()).thenReturn(8);
        when(runtimeConfigService.getMemorySemanticTopK()).thenReturn(6);
        when(runtimeConfigService.getMemoryProceduralTopK()).thenReturn(4);

        memoryQueryFactory = new MemoryQueryFactory(runtimeConfigService);
    }

    @Test
    void shouldCreatePromptQueryFromRuntimeDefaultsWhenRequestMissingQuery() {
        MemoryQuery query = memoryQueryFactory.createForPrompt(MemoryContextRequest.builder().build());

        assertNotNull(query);
        assertEquals("global", query.getScope());
        assertEquals(List.of(), query.getScopeChain());
        assertEquals(1800, query.getSoftPromptBudgetTokens());
        assertEquals(3500, query.getMaxPromptBudgetTokens());
        assertEquals(6, query.getWorkingTopK());
        assertEquals(8, query.getEpisodicTopK());
        assertEquals(6, query.getSemanticTopK());
        assertEquals(4, query.getProceduralTopK());
    }

    @Test
    void shouldBackfillMissingFieldsWhilePreservingExplicitQueryValues() {
        MemoryQuery query = memoryQueryFactory.createForTool(MemoryQuery.builder().queryText("redis")
                .activeSkill("coding").scope("global").scopeChain(List.of("task:build-cache", "global"))
                .softPromptBudgetTokens(null).maxPromptBudgetTokens(-1).workingTopK(null).episodicTopK(2)
                .semanticTopK(9).proceduralTopK(1).build());

        assertEquals("redis", query.getQueryText());
        assertEquals("coding", query.getActiveSkill());
        assertEquals("global", query.getScope());
        assertEquals(List.of("task:build-cache", "global"), query.getScopeChain());
        assertEquals(1800, query.getSoftPromptBudgetTokens());
        assertEquals(3500, query.getMaxPromptBudgetTokens());
        assertEquals(6, query.getWorkingTopK());
        assertEquals(2, query.getEpisodicTopK());
        assertEquals(9, query.getSemanticTopK());
        assertEquals(1, query.getProceduralTopK());
    }

    @Test
    void shouldPreserveExplicitZeroTopKValuesToDisableLayers() {
        MemoryQuery query = memoryQueryFactory.createForTool(
                MemoryQuery.builder().workingTopK(0).episodicTopK(0).semanticTopK(0).proceduralTopK(0).build());

        assertEquals(0, query.getWorkingTopK());
        assertEquals(0, query.getEpisodicTopK());
        assertEquals(0, query.getSemanticTopK());
        assertEquals(0, query.getProceduralTopK());
    }
}
