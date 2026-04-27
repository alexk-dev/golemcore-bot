package me.golemcore.bot.adapter.inbound.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.planning.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

class PlansControllerTest {

    private PlanService planService;
    private PlansController controller;

    @BeforeEach
    void setUp() {
        planService = mock(PlanService.class);
        controller = new PlansController(planService);
    }

    @Test
    void getStateShouldReturnEphemeralModeStateWithoutPersistedPlans() {
        when(planService.isPlanModeActive(any(SessionIdentity.class))).thenReturn(true);
        when(planService.getActivePlanId(any(SessionIdentity.class))).thenReturn("plan-1");

        StepVerifier.create(controller.getState("chat-1"))
                .assertNext(resp -> {
                    assertEquals(HttpStatus.OK, resp.getStatusCode());
                    PlansController.PlanControlStateResponse body = resp.getBody();
                    assertNotNull(body);
                    assertTrue(body.featureEnabled());
                    assertEquals("chat-1", body.sessionId());
                    assertTrue(body.planModeActive());
                    assertEquals("plan-1", body.activePlanId());
                    assertTrue(body.plans().isEmpty());
                })
                .verifyComplete();
    }

    @Test
    void enablePlanModeShouldRejectBlankSessionId() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.enablePlanMode(new PlansController.PlanModeOnRequest("")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());

        verify(planService, never()).activatePlanMode(any(), any(), any());
    }

    @Test
    void enablePlanModeShouldActivateWhenNotActive() {
        when(planService.isPlanModeActive(any(SessionIdentity.class))).thenReturn(false, true);

        StepVerifier.create(controller.enablePlanMode(new PlansController.PlanModeOnRequest("chat-1")))
                .assertNext(resp -> assertEquals(HttpStatus.OK, resp.getStatusCode()))
                .verifyComplete();

        verify(planService).activatePlanMode(any(SessionIdentity.class), eq("chat-1"), eq(null));
    }

    @Test
    void disablePlanModeShouldDeactivateSession() {
        StepVerifier.create(controller.disablePlanMode("chat-1"))
                .assertNext(resp -> assertEquals(HttpStatus.OK, resp.getStatusCode()))
                .verifyComplete();

        verify(planService).deactivatePlanMode(new SessionIdentity("web", "chat-1"));
    }

    @Test
    void donePlanModeShouldCompleteSessionPlanMode() {
        StepVerifier.create(controller.donePlanMode("chat-1"))
                .assertNext(resp -> assertEquals(HttpStatus.OK, resp.getStatusCode()))
                .verifyComplete();

        verify(planService).completePlanMode(new SessionIdentity("web", "chat-1"));
    }
}
