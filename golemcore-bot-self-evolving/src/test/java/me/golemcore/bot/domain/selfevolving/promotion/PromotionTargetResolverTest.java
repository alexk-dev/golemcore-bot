package me.golemcore.bot.domain.selfevolving.promotion;

import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;

class PromotionTargetResolverTest {

    @Test
    void shouldResolveShadowedCandidateToCanaryWhenCanaryIsRequired() {
        SelfEvolvingRuntimeConfigPort runtimeConfigPort = mock(SelfEvolvingRuntimeConfigPort.class);
        when(runtimeConfigPort.getSelfEvolvingPromotionMode()).thenReturn("auto_accept");
        when(runtimeConfigPort.isSelfEvolvingPromotionCanaryRequired()).thenReturn(true);
        PromotionTargetResolver resolver = new PromotionTargetResolver(runtimeConfigPort);

        PromotionTarget target = resolver.resolve(EvolutionCandidate.builder()
                .id("candidate-1")
                .status("shadowed")
                .lifecycleState("candidate")
                .rolloutStage("shadowed")
                .build());

        assertEquals("canary", target.legacyState());
        assertEquals("candidate", target.lifecycleState());
        assertEquals("canary", target.rolloutStage());
    }

    @Test
    void shouldResolveProposedCandidateToShadowWhenShadowIsRequired() {
        SelfEvolvingRuntimeConfigPort runtimeConfigPort = mock(SelfEvolvingRuntimeConfigPort.class);
        when(runtimeConfigPort.getSelfEvolvingPromotionMode()).thenReturn("approval_gate");
        when(runtimeConfigPort.isSelfEvolvingPromotionShadowRequired()).thenReturn(true);
        PromotionTargetResolver resolver = new PromotionTargetResolver(runtimeConfigPort);

        PromotionTarget target = resolver.resolve(EvolutionCandidate.builder()
                .id("candidate-2")
                .status("approved_pending")
                .lifecycleState("approved")
                .rolloutStage("approved")
                .build());

        assertEquals("shadowed", target.legacyState());
        assertEquals("candidate", target.lifecycleState());
        assertEquals("shadowed", target.rolloutStage());
    }

    @Test
    void shouldThrowWhenPromotionModeIsUnsupported() {
        SelfEvolvingRuntimeConfigPort runtimeConfigPort = mock(SelfEvolvingRuntimeConfigPort.class);
        when(runtimeConfigPort.getSelfEvolvingPromotionMode()).thenReturn("invalid_mode");
        PromotionTargetResolver resolver = new PromotionTargetResolver(runtimeConfigPort);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(EvolutionCandidate.builder().id("candidate-3").build()));

        assertEquals("Unsupported promotion mode: invalid_mode", exception.getMessage());
    }
}
