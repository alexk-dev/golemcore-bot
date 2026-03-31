package me.golemcore.bot.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JudgeTierResolverTest {

    private RuntimeConfigService runtimeConfigService;
    private ModelSelectionService modelSelectionService;
    private JudgeTierResolver judgeTierResolver;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        judgeTierResolver = new JudgeTierResolver(runtimeConfigService, modelSelectionService);
    }

    @Test
    void shouldResolvePrimaryJudgeTierThroughExistingRouterBindings() {
        when(runtimeConfigService.getSelfEvolvingJudgePrimaryTier()).thenReturn("balanced");
        when(modelSelectionService.resolveExplicitTier("balanced"))
                .thenReturn(new ModelSelectionService.ModelSelection("provider/judge-standard", "medium"));

        String modelId = judgeTierResolver.resolveModel("primary");

        assertEquals("provider/judge-standard", modelId);
    }

    @Test
    void shouldResolveTiebreakerJudgeTierThroughExistingRouterBindings() {
        when(runtimeConfigService.getSelfEvolvingJudgeTiebreakerTier()).thenReturn("smart");
        when(modelSelectionService.resolveExplicitTier("smart"))
                .thenReturn(new ModelSelectionService.ModelSelection("provider/judge-premium", "high"));

        ModelSelectionService.ModelSelection selection = judgeTierResolver.resolveSelection("tiebreaker");

        assertEquals("provider/judge-premium", selection.model());
        assertEquals("high", selection.reasoning());
    }
}
