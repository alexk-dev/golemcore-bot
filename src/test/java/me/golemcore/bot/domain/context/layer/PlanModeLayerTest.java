package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.service.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanModeLayerTest {

    private PlanService planService;
    private PlanModeLayer layer;

    @BeforeEach
    void setUp() {
        planService = mock(PlanService.class);
        layer = new PlanModeLayer(planService);
    }

    @Test
    void shouldApplyOnlyWhenPlanModeIsActive() {
        when(planService.isPlanModeActive()).thenReturn(true);
        assertTrue(layer.appliesTo(AgentContext.builder().build()));

        when(planService.isPlanModeActive()).thenReturn(false);
        assertFalse(layer.appliesTo(AgentContext.builder().build()));
    }

    @Test
    void shouldRenderPlanContext() {
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.buildPlanContext()).thenReturn("# Plan\n## Steps\n1. Implement feature");

        ContextLayerResult result = layer.assemble(AgentContext.builder().build());

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("Implement feature"));
    }

    @Test
    void shouldReturnEmptyWhenPlanContextIsBlank() {
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.buildPlanContext()).thenReturn("");

        ContextLayerResult result = layer.assemble(AgentContext.builder().build());
        assertFalse(result.hasContent());
    }

    @Test
    void shouldHaveCorrectNameAndOrder() {
        assertEquals("plan_mode", layer.getName());
        assertEquals(72, layer.getOrder());
    }
}
