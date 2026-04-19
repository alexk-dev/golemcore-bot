package me.golemcore.bot.domain.selfevolving.benchmark;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;

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

    @Test
    void shouldResolveEvolutionJudgeTierAfterNormalizingLane() {
        when(runtimeConfigService.getSelfEvolvingJudgeEvolutionTier()).thenReturn("coding");
        when(modelSelectionService.resolveExplicitTier("coding"))
                .thenReturn(new ModelSelectionService.ModelSelection("provider/judge-evolution", "medium"));

        ModelSelectionService.ModelSelection selection = judgeTierResolver.resolveSelection(" Evolution ");

        assertEquals("provider/judge-evolution", selection.model());
        assertEquals("medium", selection.reasoning());
    }

    @Test
    void shouldRejectUnknownJudgeLane() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> judgeTierResolver.resolveSelection("sidecar"));

        assertEquals("Unknown judge lane: sidecar", error.getMessage());
    }

    @Test
    void shouldRejectNullJudgeLane() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> judgeTierResolver.resolveSelection(null));

        assertEquals("Unknown judge lane: null", error.getMessage());
    }
}
