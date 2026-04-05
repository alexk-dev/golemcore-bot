package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromotionTargetResolverTest {

    @Test
    void shouldResolveShadowedCandidateToCanaryWhenCanaryIsRequired() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getSelfEvolvingPromotionMode()).thenReturn("auto_accept");
        when(runtimeConfigService.isSelfEvolvingPromotionCanaryRequired()).thenReturn(true);
        PromotionTargetResolver resolver = new PromotionTargetResolver(runtimeConfigService);

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
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getSelfEvolvingPromotionMode()).thenReturn("approval_gate");
        when(runtimeConfigService.isSelfEvolvingPromotionShadowRequired()).thenReturn(true);
        PromotionTargetResolver resolver = new PromotionTargetResolver(runtimeConfigService);

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
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getSelfEvolvingPromotionMode()).thenReturn("invalid_mode");
        PromotionTargetResolver resolver = new PromotionTargetResolver(runtimeConfigService);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(EvolutionCandidate.builder().id("candidate-3").build()));

        assertEquals("Unsupported promotion mode: invalid_mode", exception.getMessage());
    }
}
