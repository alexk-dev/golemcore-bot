package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PlanReadyEvent;
import me.golemcore.bot.domain.model.PlanStep;
import me.golemcore.bot.domain.service.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanFinalizationSystemTest {

    private static final String PLAN_ID = "plan-123";
    private static final String CHAT_ID = "chat-456";
    private static final String TOOL_FILESYSTEM = "filesystem";

    private PlanService planService;
    private ApplicationEventPublisher eventPublisher;
    private PlanFinalizationSystem system;

    @BeforeEach
    void setUp() {
        planService = mock(PlanService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        system = new PlanFinalizationSystem(planService, eventPublisher);
    }

    @Test
    void shouldBeDisabledWhenFeatureDisabled() {
        when(planService.isFeatureEnabled()).thenReturn(false);
        assertFalse(system.isEnabled());
    }

    @Test
    void shouldBeEnabledWhenFeatureEnabled() {
        when(planService.isFeatureEnabled()).thenReturn(true);
        assertTrue(system.isEnabled());
    }

    @Test
    void shouldNotProcessWhenPlanModeInactive() {
        when(planService.isPlanModeActive()).thenReturn(false);
        LlmResponse response = LlmResponse.builder()
                .content("Some response")
                .toolCalls(List.of(me.golemcore.bot.domain.model.Message.ToolCall.builder()
                        .id("tc1")
                        .name(me.golemcore.bot.tools.PlanSetContentTool.TOOL_NAME)
                        .arguments(java.util.Map.of("plan_markdown", "# Plan"))
                        .build()))
                .build();
        AgentContext context = buildContext(null);
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenToolCallsPresent() {
        when(planService.isPlanModeActive()).thenReturn(true);
        LlmResponse responseWithTools = LlmResponse.builder()
                .content("Some response")
                .toolCalls(List.of(me.golemcore.bot.domain.model.Message.ToolCall.builder()
                        .id("tc1").name("shell").build()))
                .build();
        AgentContext context = buildContext(null);
        context.setAttribute(ContextAttributes.LLM_RESPONSE, responseWithTools);
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenNoLlmResponse() {
        when(planService.isPlanModeActive()).thenReturn(true);
        AgentContext context = buildContext(null);
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWhenPlanActiveAndPlanSetContentToolCallPresent() {
        when(planService.isPlanModeActive()).thenReturn(true);
        LlmResponse response = LlmResponse.builder()
                .content("irrelevant")
                .toolCalls(List.of(me.golemcore.bot.domain.model.Message.ToolCall.builder()
                        .id("tc1").name(me.golemcore.bot.tools.PlanSetContentTool.TOOL_NAME).build()))
                .build();
        AgentContext context = buildContext(null);
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        assertTrue(system.shouldProcess(context));
    }

    @Test

    void shouldFinalizeEvenIfPlanHasNoSteps() {
        when(planService.isPlanModeActive()).thenReturn(true);
        Plan emptyPlan = Plan.builder()
                .id(PLAN_ID)
                .status(Plan.PlanStatus.COLLECTING)
                .steps(new ArrayList<>())
                .build();
        when(planService.getActivePlan()).thenReturn(Optional.of(emptyPlan));

        LlmResponse response = LlmResponse.builder()
                .content("No plan needed")
                .toolCalls(List.of(me.golemcore.bot.domain.model.Message.ToolCall.builder()
                        .id("tc1")
                        .name(me.golemcore.bot.tools.PlanSetContentTool.TOOL_NAME)
                        .arguments(java.util.Map.of("plan_markdown", "# Plan"))
                        .build()))
                .build();
        AgentContext context = buildContext(null);
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        system.process(context);

        verify(planService).finalizePlan(eq(PLAN_ID), eq("# Plan"), any());
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(PlanReadyEvent.class));
    }

    @Test
    void shouldFinalizePlanWithSteps() {
        when(planService.isPlanModeActive()).thenReturn(true);

        List<PlanStep> steps = List.of(
                PlanStep.builder().id("s1").toolName(TOOL_FILESYSTEM).description("write file").order(0).build(),
                PlanStep.builder().id("s2").toolName("shell").description("run tests").order(1).build());

        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .status(Plan.PlanStatus.COLLECTING)
                .steps(new ArrayList<>(steps))
                .build();
        when(planService.getActivePlan()).thenReturn(Optional.of(plan));

        LlmResponse response = LlmResponse.builder()
                .content("Here is my plan")
                .toolCalls(List.of(me.golemcore.bot.domain.model.Message.ToolCall.builder()
                        .id("tc1")
                        .name(me.golemcore.bot.tools.PlanSetContentTool.TOOL_NAME)
                        .arguments(java.util.Map.of("plan_markdown", "# Plan"))
                        .build()))
                .build();
        AgentContext context = buildContext(null);
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        system.process(context);

        verify(planService).finalizePlan(any(), any(), any());
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(PlanReadyEvent.class));

        // Check that plan approval attribute was set
        String approvalNeeded = context.getAttribute(ContextAttributes.PLAN_APPROVAL_NEEDED);
        assertEquals(PLAN_ID, approvalNeeded);
    }

    @Test
    void shouldDeactivateWhenNoActivePlanFound() {
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.getActivePlan()).thenReturn(Optional.empty());

        LlmResponse response = LlmResponse.builder()
                .content("Some response")
                .toolCalls(List.of(me.golemcore.bot.domain.model.Message.ToolCall.builder()
                        .id("tc1")
                        .name(me.golemcore.bot.tools.PlanSetContentTool.TOOL_NAME)
                        .arguments(java.util.Map.of("plan_markdown", "# Plan"))
                        .build()))
                .build();
        AgentContext context = buildContext(null);
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        system.process(context);

        verify(planService).deactivatePlanMode();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldNotProcessWhenTextOnlyResponseWithoutToolCalls() {
        when(planService.isPlanModeActive()).thenReturn(true);
        AgentContext context = buildContext(null);
        LlmResponse response = LlmResponse.builder().content("some text").toolCalls(List.of()).build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenResponseHasNoToolCallsField() {
        when(planService.isPlanModeActive()).thenReturn(true);
        AgentContext context = buildContext(null);
        LlmResponse response = LlmResponse.builder().content("whatever").toolCalls(null).build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenToolCallsListIsEmpty() {
        when(planService.isPlanModeActive()).thenReturn(true);
        AgentContext context = buildContext(null);
        LlmResponse response = LlmResponse.builder().content("Some text response").toolCalls(List.of()).build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        assertFalse(system.shouldProcess(context));
    }

    private AgentContext buildContext(String responseContent) {
        AgentSession session = mock(AgentSession.class);
        when(session.getChatId()).thenReturn(CHAT_ID);

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();

        if (responseContent != null) {
            LlmResponse response = LlmResponse.builder().content(responseContent).build();
            context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        }

        return context;
    }

    @Test
    void shouldPublishReadyEventForRevisionIdWhenExecutingPlanFinalized() {
        when(planService.isPlanModeActive()).thenReturn(true);

        Plan executingPlan = Plan.builder()
                .id(PLAN_ID)
                .status(Plan.PlanStatus.EXECUTING)
                .steps(new ArrayList<>())
                .build();
        Plan revisedPlan = Plan.builder()
                .id("plan-456")
                .status(Plan.PlanStatus.READY)
                .steps(new ArrayList<>())
                .build();

        when(planService.getActivePlan())
                .thenReturn(Optional.of(executingPlan))
                .thenReturn(Optional.of(revisedPlan));

        LlmResponse response = LlmResponse.builder()
                .toolCalls(List.of(me.golemcore.bot.domain.model.Message.ToolCall.builder()
                        .id("tc1")
                        .name(me.golemcore.bot.tools.PlanSetContentTool.TOOL_NAME)
                        .arguments(java.util.Map.of("plan_markdown", "# Revised plan"))
                        .build()))
                .build();

        AgentContext context = buildContext(null);
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

        system.process(context);

        verify(planService).finalizePlan(eq(PLAN_ID), eq("# Revised plan"), any());
        verify(eventPublisher).publishEvent(eq(new PlanReadyEvent("plan-456", CHAT_ID)));
        assertEquals("plan-456", context.getAttribute(ContextAttributes.PLAN_APPROVAL_NEEDED));
    }

}
