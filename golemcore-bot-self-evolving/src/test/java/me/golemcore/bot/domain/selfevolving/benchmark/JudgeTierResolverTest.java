package me.golemcore.bot.domain.selfevolving.benchmark;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import me.golemcore.bot.port.outbound.ModelSelectionQueryPort;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;

class JudgeTierResolverTest {

    private SelfEvolvingRuntimeConfigPort runtimeConfigPort;
    private ModelSelectionQueryPort modelSelectionQueryPort;
    private JudgeTierResolver judgeTierResolver;

    @BeforeEach
    void setUp() {
        runtimeConfigPort = mock(SelfEvolvingRuntimeConfigPort.class);
        modelSelectionQueryPort = mock(ModelSelectionQueryPort.class);
        judgeTierResolver = new JudgeTierResolver(runtimeConfigPort, modelSelectionQueryPort);
    }

    @Test
    void shouldResolvePrimaryJudgeTierThroughExistingRouterBindings() {
        when(runtimeConfigPort.getSelfEvolvingJudgePrimaryTier()).thenReturn("balanced");
        when(modelSelectionQueryPort.resolveExplicitSelection("balanced"))
                .thenReturn(new ModelSelectionQueryPort.ModelSelection("provider/judge-standard", "medium"));

        String modelId = judgeTierResolver.resolveModel("primary");

        assertEquals("provider/judge-standard", modelId);
    }

    @Test
    void shouldResolveTiebreakerJudgeTierThroughExistingRouterBindings() {
        when(runtimeConfigPort.getSelfEvolvingJudgeTiebreakerTier()).thenReturn("smart");
        when(modelSelectionQueryPort.resolveExplicitSelection("smart"))
                .thenReturn(new ModelSelectionQueryPort.ModelSelection("provider/judge-premium", "high"));

        ModelSelectionQueryPort.ModelSelection selection = judgeTierResolver.resolveSelection("tiebreaker");

        assertEquals("provider/judge-premium", selection.model());
        assertEquals("high", selection.reasoning());
    }

    @Test
    void shouldResolveEvolutionJudgeTierAfterNormalizingLane() {
        when(runtimeConfigPort.getSelfEvolvingJudgeEvolutionTier()).thenReturn("coding");
        when(modelSelectionQueryPort.resolveExplicitSelection("coding"))
                .thenReturn(new ModelSelectionQueryPort.ModelSelection("provider/judge-evolution", "medium"));

        ModelSelectionQueryPort.ModelSelection selection = judgeTierResolver.resolveSelection(" Evolution ");

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
