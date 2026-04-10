package me.golemcore.bot.application.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.service.PlanExecutionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlanCommandServiceTest {

    private PlanService planService;
    private PlanExecutionService planExecutionService;
    private RuntimeConfigService runtimeConfigService;
    private PlanCommandService service;

    @BeforeEach
    void setUp() {
        planService = mock(PlanService.class);
        planExecutionService = mock(PlanExecutionService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        service = new PlanCommandService(planService, planExecutionService, runtimeConfigService);
    }

    @Test
    void getModeStatusShouldReturnUnavailableWhenFeatureDisabled() {
        when(planService.isFeatureEnabled()).thenReturn(false);

        PlanCommandService.PlanModeOutcome outcome = service.getModeStatus(null);

        assertInstanceOf(PlanCommandService.FeatureUnavailable.class, outcome);
    }

    @Test
    void enablePlanModeShouldTranslatePlanLimitReached() {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(runtimeConfigService.getPlanMaxPlans()).thenReturn(5);
        when(planService.isPlanModeActive()).thenReturn(false);
        doThrow(new IllegalStateException("full")).when(planService).activatePlanMode("chat-1", "smart");

        PlanCommandService.PlanModeOutcome outcome = service.enablePlanMode(null, "chat-1", "smart");

        PlanCommandService.PlanLimitReached limit = assertInstanceOf(
                PlanCommandService.PlanLimitReached.class,
                outcome);
        assertEquals(5, limit.maxPlans());
    }

    @Test
    void approvePlanShouldExecuteResolvedReadyPlan() {
        Plan readyPlan = Plan.builder().id("plan-1").status(Plan.PlanStatus.READY).build();
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.getPlans()).thenReturn(List.of(readyPlan));
        when(planService.getPlan("plan-1")).thenReturn(Optional.of(readyPlan));

        PlanCommandService.PlanActionOutcome outcome = service.approvePlan(null, null);

        PlanCommandService.Approved approved = assertInstanceOf(PlanCommandService.Approved.class, outcome);
        assertEquals("plan-1", approved.planId());
        verify(planService).approvePlan("plan-1");
        verify(planExecutionService).executePlan("plan-1");
    }

    @Test
    void approvePlanShouldRejectMissingSessionScopedPlan() {
        SessionIdentity sessionIdentity = new SessionIdentity("telegram", "chat-1");
        Plan readyPlan = Plan.builder().id("plan-1").status(Plan.PlanStatus.READY).build();
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.getPlans(sessionIdentity)).thenReturn(List.of(readyPlan));
        when(planService.getPlan("plan-1", sessionIdentity)).thenReturn(Optional.empty());

        PlanCommandService.PlanActionOutcome outcome = service.approvePlan(sessionIdentity, null);

        PlanCommandService.PlanNotFound notFound = assertInstanceOf(
                PlanCommandService.PlanNotFound.class,
                outcome);
        assertEquals("plan-1", notFound.planId());
    }

    @Test
    void listPlansShouldReturnEmptyPlansWhenNothingExists() {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.getPlans()).thenReturn(List.of());

        PlanCommandService.PlanOverviewOutcome outcome = service.listPlans(null);

        assertInstanceOf(PlanCommandService.EmptyPlans.class, outcome);
    }
}
