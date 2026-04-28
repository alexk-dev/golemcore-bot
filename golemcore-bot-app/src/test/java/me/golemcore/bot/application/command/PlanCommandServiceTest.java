package me.golemcore.bot.application.command;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.planning.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlanCommandServiceTest {

    private PlanService planService;
    private PlanCommandService service;

    @BeforeEach
    void setUp() {
        planService = mock(PlanService.class);
        service = new PlanCommandService(planService);
    }

    @Test
    void shouldAlwaysReportFeatureEnabled() {
        assertTrue(service.isFeatureEnabled());
    }

    @Test
    void shouldActivateLegacyPlanModeWhenNoSessionIdentityExists() {
        PlanCommandService.PlanModeOutcome outcome = service.enablePlanMode(null, "chat-1");

        assertInstanceOf(PlanCommandService.Enabled.class, outcome);
        verify(planService).activatePlanMode("chat-1", null);
    }

    @Test
    void shouldActivateSessionScopedPlanMode() {
        SessionIdentity sessionIdentity = new SessionIdentity("telegram", "chat-1");

        PlanCommandService.PlanModeOutcome outcome = service.enablePlanMode(sessionIdentity, "transport-1");

        assertInstanceOf(PlanCommandService.Enabled.class, outcome);
        verify(planService).activatePlanMode(sessionIdentity, "transport-1", null);
    }

    @Test
    void shouldReturnAlreadyActiveForActiveSessionPlanMode() {
        SessionIdentity sessionIdentity = new SessionIdentity("telegram", "chat-1");
        when(planService.isPlanModeActive(sessionIdentity)).thenReturn(true);

        PlanCommandService.PlanModeOutcome outcome = service.enablePlanMode(sessionIdentity, "transport-1");

        assertInstanceOf(PlanCommandService.AlreadyActive.class, outcome);
    }

    @Test
    void shouldDeactivateActiveSessionPlanMode() {
        SessionIdentity sessionIdentity = new SessionIdentity("telegram", "chat-1");
        when(planService.isPlanModeActive(sessionIdentity)).thenReturn(true);

        PlanCommandService.PlanModeOutcome outcome = service.disablePlanMode(sessionIdentity);

        assertInstanceOf(PlanCommandService.Disabled.class, outcome);
        verify(planService).deactivatePlanMode(sessionIdentity);
    }

    @Test
    void shouldCompleteActiveSessionPlanMode() {
        SessionIdentity sessionIdentity = new SessionIdentity("telegram", "chat-1");
        when(planService.isPlanModeActive(sessionIdentity)).thenReturn(true);

        PlanCommandService.PlanModeOutcome outcome = service.completePlanMode(sessionIdentity);

        assertInstanceOf(PlanCommandService.Done.class, outcome);
        verify(planService).completePlanMode(sessionIdentity);
    }
}
