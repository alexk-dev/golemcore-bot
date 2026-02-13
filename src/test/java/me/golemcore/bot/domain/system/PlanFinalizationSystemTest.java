package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PlanReadyEvent;
import me.golemcore.bot.domain.model.PlanStep;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
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
    private UserPreferencesService preferencesService;
    private PlanFinalizationSystem system;

    @BeforeEach
    void setUp() {
        planService = mock(PlanService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        preferencesService = mock(UserPreferencesService.class);

        // Avoid coupling tests to i18n message catalog contents
        when(preferencesService.getMessage(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenAnswer(inv -> inv.getArgument(0));

        system = new PlanFinalizationSystem(planService, eventPublisher, preferencesService);
    }

    @Test
    void shouldReturnCorrectNameAndOrder() {
        assertEquals("PlanFinalizationSystem", system.getName());
        assertEquals(58, system.getOrder());
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
        AgentContext context = buildContext("Some response");
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
    void shouldProcessWhenPlanActiveAndTextResponse() {
        when(planService.isPlanModeActive()).thenReturn(true);
        AgentContext context = buildContext("Here is my plan summary");
        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldCancelEmptyPlan() {
        when(planService.isPlanModeActive()).thenReturn(true);
        Plan emptyPlan = Plan.builder()
                .id(PLAN_ID)
                .status(Plan.PlanStatus.COLLECTING)
                .steps(new ArrayList<>())
                .build();
        when(planService.getActivePlan()).thenReturn(Optional.of(emptyPlan));

        AgentContext context = buildContext("No plan needed");
        system.process(context);

        verify(planService).cancelPlan(PLAN_ID);
        verify(planService, never()).finalizePlan(any());
        verify(eventPublisher, never()).publishEvent(any());
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

        AgentContext context = buildContext("Here is my plan");
        system.process(context);

        verify(planService).finalizePlan(PLAN_ID);
        verify(eventPublisher).publishEvent(new PlanReadyEvent(PLAN_ID, CHAT_ID));

        // Check that plan approval attribute was set
        String approvalNeeded = context.getAttribute(ContextAttributes.PLAN_APPROVAL_NEEDED);
        assertEquals(PLAN_ID, approvalNeeded);
    }

    @Test
    void shouldAppendPlanSummaryToResponse() {
        when(planService.isPlanModeActive()).thenReturn(true);

        List<PlanStep> steps = List.of(
                PlanStep.builder().id("s1").toolName(TOOL_FILESYSTEM).description("write file").order(0).build());

        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .status(Plan.PlanStatus.COLLECTING)
                .steps(new ArrayList<>(steps))
                .build();
        when(planService.getActivePlan()).thenReturn(Optional.of(plan));

        AgentContext context = buildContext("Original response");
        system.process(context);

        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        assertTrue(response.getContent().contains("Original response"));
        assertTrue(response.getContent().contains(TOOL_FILESYSTEM));
        assertTrue(response.getContent().contains("plan.ready.card.waiting"));
    }

    @Test
    void shouldDeactivateWhenNoActivePlanFound() {
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.getActivePlan()).thenReturn(Optional.empty());

        AgentContext context = buildContext("Some response");
        system.process(context);

        verify(planService).deactivatePlanMode();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldNotProcessWhenResponseContentIsNull() {
        when(planService.isPlanModeActive()).thenReturn(true);
        AgentContext context = buildContext(null);
        // Explicitly set a response with null content (different from no response)
        LlmResponse response = LlmResponse.builder().content(null).build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenResponseContentIsBlank() {
        when(planService.isPlanModeActive()).thenReturn(true);
        AgentContext context = buildContext(null);
        LlmResponse response = LlmResponse.builder().content("   ").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWhenToolCallsListIsEmpty() {
        when(planService.isPlanModeActive()).thenReturn(true);
        AgentContext context = buildContext("Some text response");
        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldHandleStepWithNullDescription() {
        when(planService.isPlanModeActive()).thenReturn(true);

        List<PlanStep> steps = List.of(
                PlanStep.builder().id("s1").toolName(TOOL_FILESYSTEM).description(null).order(0).build());

        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .status(Plan.PlanStatus.COLLECTING)
                .steps(new ArrayList<>(steps))
                .build();
        when(planService.getActivePlan()).thenReturn(Optional.of(plan));

        AgentContext context = buildContext("Plan summary here");
        system.process(context);

        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        assertTrue(response.getContent().contains(TOOL_FILESYSTEM));
        assertFalse(response.getContent().contains(" — null"));
    }

    @Test
    void shouldHandleStepWithBlankDescription() {
        when(planService.isPlanModeActive()).thenReturn(true);

        List<PlanStep> steps = List.of(
                PlanStep.builder().id("s1").toolName(TOOL_FILESYSTEM).description("   ").order(0).build());

        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .status(Plan.PlanStatus.COLLECTING)
                .steps(new ArrayList<>(steps))
                .build();
        when(planService.getActivePlan()).thenReturn(Optional.of(plan));

        AgentContext context = buildContext("Plan summary here");
        system.process(context);

        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        assertTrue(response.getContent().contains(TOOL_FILESYSTEM));
        // Blank description should not have " — " separator
        assertFalse(response.getContent().contains(" — "));
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
}
