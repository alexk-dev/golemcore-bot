package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    void shouldApplyWhenExecutionContextIsPendingAfterPlanExit() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("web").chatId("chat-1").build())
                .build();
        when(planService.isPlanModeActive(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(planService.hasPendingExecutionContext(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        assertTrue(layer.appliesTo(context));
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
    void shouldMarkActiveModeOnContextWhenPlanModeIsActive() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("web").chatId("chat-1").build())
                .build();
        when(planService.isPlanModeActive(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(planService.buildPlanContext(org.mockito.ArgumentMatchers.any()))
                .thenReturn("# Plan\n## Steps\n1. Inspect");

        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.hasContent());
        assertEquals(ContextAttributes.ACTIVE_MODE_PLAN, context.getAttribute(ContextAttributes.ACTIVE_MODE));
    }

    @Test
    void shouldApplyConfiguredPlanTierOverSoftTierRouting() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getPlanModelTier()).thenReturn("deep");
        PlanService realPlanService = new PlanService(
                Clock.fixed(Instant.parse("2026-04-23T12:00:00Z"), ZoneOffset.UTC),
                runtimeConfigService);
        SessionIdentity sessionIdentity = new SessionIdentity("web", "chat-1");
        realPlanService.activatePlanMode(sessionIdentity, "chat-1", null);
        PlanModeLayer realLayer = new PlanModeLayer(realPlanService);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("web").chatId("chat-1").build())
                .build();
        context.setModelTier("balanced");
        context.setAttribute(ContextAttributes.MODEL_TIER_SOURCE, "user_pref");
        context.setAttribute(ContextAttributes.MODEL_TIER_MODEL_ID, "gpt-balanced");
        context.setAttribute(ContextAttributes.MODEL_TIER_REASONING, "medium");

        ContextLayerResult result = realLayer.assemble(context);

        assertTrue(result.hasContent());
        assertEquals("deep", context.getModelTier());
        assertEquals("plan_override", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
        assertNull(context.getAttribute(ContextAttributes.MODEL_TIER_MODEL_ID));
        assertNull(context.getAttribute(ContextAttributes.MODEL_TIER_REASONING));
    }

    @Test
    void shouldNotOverrideForcedTierWithPlanTier() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getPlanModelTier()).thenReturn("deep");
        PlanService realPlanService = new PlanService(
                Clock.fixed(Instant.parse("2026-04-23T12:00:00Z"), ZoneOffset.UTC),
                runtimeConfigService);
        SessionIdentity sessionIdentity = new SessionIdentity("web", "chat-1");
        realPlanService.activatePlanMode(sessionIdentity, "chat-1", null);
        PlanModeLayer realLayer = new PlanModeLayer(realPlanService);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("web").chatId("chat-1").build())
                .build();
        context.setModelTier("balanced");
        context.setAttribute(ContextAttributes.MODEL_TIER_SOURCE, "user_pref_forced");

        realLayer.assemble(context);

        assertEquals("balanced", context.getModelTier());
        assertEquals("user_pref_forced", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldNotOverridePreselectedTierWithoutExplicitSource() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getPlanModelTier()).thenReturn("deep");
        PlanService realPlanService = new PlanService(
                Clock.fixed(Instant.parse("2026-04-23T12:00:00Z"), ZoneOffset.UTC),
                runtimeConfigService);
        SessionIdentity sessionIdentity = new SessionIdentity("web", "chat-1");
        realPlanService.activatePlanMode(sessionIdentity, "chat-1", null);
        PlanModeLayer realLayer = new PlanModeLayer(realPlanService);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("web").chatId("chat-1").build())
                .build();
        context.setModelTier("coding");
        context.setAttribute(ContextAttributes.MODEL_TIER_SOURCE, "implicit_default");

        realLayer.assemble(context);

        assertEquals("coding", context.getModelTier());
        assertEquals("implicit_default", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldRenderPendingExecutionContextWithoutConsumingIt() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("web").chatId("chat-1").build())
                .build();
        when(planService.isPlanModeActive(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(planService.hasPendingExecutionContext(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(planService.peekExecutionContext(org.mockito.ArgumentMatchers.any()))
                .thenReturn("Plan mode has ended. Execute from `.golemcore/plans/plan.md`.");

        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("Plan mode has ended"));
        assertEquals(Boolean.TRUE, context.getAttribute(ContextAttributes.PLAN_EXECUTION_CONTEXT_PENDING));
        verify(planService).peekExecutionContext(org.mockito.ArgumentMatchers.any());
        verify(planService, never()).consumeExecutionContext(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldNotMarkActiveModeWhenOnlyExecutionContextIsPending() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("web").chatId("chat-1").build())
                .build();
        when(planService.isPlanModeActive(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(planService.hasPendingExecutionContext(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(planService.peekExecutionContext(org.mockito.ArgumentMatchers.any()))
                .thenReturn("Plan mode has ended. Execute from `.golemcore/plans/plan.md`.");

        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.hasContent());
        assertNull(context.getAttribute(ContextAttributes.ACTIVE_MODE));
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
