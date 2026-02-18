package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.service.PlanExecutionService;
import me.golemcore.bot.domain.service.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlansControllerTest {

    private PlanService planService;
    private PlanExecutionService planExecutionService;
    private PlansController controller;

    @BeforeEach
    void setUp() {
        planService = mock(PlanService.class);
        planExecutionService = mock(PlanExecutionService.class);
        controller = new PlansController(planService, planExecutionService);
    }

    @Test
    void getStateShouldReturnFeatureDisabledWhenPlanFeatureOff() {
        when(planService.isFeatureEnabled()).thenReturn(false);

        StepVerifier.create(controller.getState())
                .assertNext(resp -> {
                    assertEquals(HttpStatus.OK, resp.getStatusCode());
                    PlansController.PlanControlStateResponse body = resp.getBody();
                    assertNotNull(body);
                    assertFalse(body.featureEnabled());
                    assertFalse(body.planModeActive());
                    assertEquals(0, body.plans().size());
                })
                .verifyComplete();
    }

    @Test
    void getStateShouldReturnPlansSortedByUpdatedAtDesc() {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.getActivePlanId()).thenReturn("plan-2");

        Plan older = Plan.builder()
                .id("plan-1")
                .status(Plan.PlanStatus.COLLECTING)
                .createdAt(Instant.parse("2026-02-18T05:00:00Z"))
                .updatedAt(Instant.parse("2026-02-18T05:10:00Z"))
                .build();

        Plan newer = Plan.builder()
                .id("plan-2")
                .status(Plan.PlanStatus.READY)
                .createdAt(Instant.parse("2026-02-18T06:00:00Z"))
                .updatedAt(Instant.parse("2026-02-18T06:10:00Z"))
                .build();

        when(planService.getPlans()).thenReturn(List.of(older, newer));

        StepVerifier.create(controller.getState())
                .assertNext(resp -> {
                    PlansController.PlanControlStateResponse body = resp.getBody();
                    assertNotNull(body);
                    assertTrue(body.featureEnabled());
                    assertTrue(body.planModeActive());
                    assertEquals("plan-2", body.activePlanId());
                    assertEquals(2, body.plans().size());
                    assertEquals("plan-2", body.plans().get(0).id());
                    assertTrue(body.plans().get(0).active());
                })
                .verifyComplete();
    }

    @Test
    void enablePlanModeShouldRejectBlankChatId() {
        when(planService.isFeatureEnabled()).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.enablePlanMode(new PlansController.PlanModeOnRequest("", null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());

        verify(planService, never()).activatePlanMode(any(), any());
    }

    @Test
    void enablePlanModeShouldActivateWhenNotActive() {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive()).thenReturn(false, true);
        when(planService.getPlans()).thenReturn(List.of());

        StepVerifier.create(controller.enablePlanMode(new PlansController.PlanModeOnRequest("chat-1", "smart")))
                .assertNext(resp -> assertEquals(HttpStatus.OK, resp.getStatusCode()))
                .verifyComplete();

        verify(planService).activatePlanMode("chat-1", "smart");
    }

    @Test
    void disablePlanModeShouldDeactivateWhenActive() {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive()).thenReturn(true, false);
        when(planService.getPlans()).thenReturn(List.of());

        StepVerifier.create(controller.disablePlanMode())
                .assertNext(resp -> assertEquals(HttpStatus.OK, resp.getStatusCode()))
                .verifyComplete();

        verify(planService).deactivatePlanMode();
    }

    @Test
    void approvePlanShouldRunExecution() {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive()).thenReturn(false);
        when(planService.getPlans()).thenReturn(List.of());

        StepVerifier.create(controller.approvePlan("plan-1"))
                .assertNext(resp -> assertEquals(HttpStatus.OK, resp.getStatusCode()))
                .verifyComplete();

        verify(planService).approvePlan("plan-1");
        verify(planExecutionService).executePlan("plan-1");
    }

    @Test
    void approvePlanShouldMapIllegalStateToBadRequest() {
        when(planService.isFeatureEnabled()).thenReturn(true);
        doThrow(new IllegalStateException("wrong status")).when(planService).approvePlan("plan-1");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.approvePlan("plan-1"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void resumePlanShouldMapErrorsToBadRequest() {
        when(planService.isFeatureEnabled()).thenReturn(true);
        doThrow(new IllegalArgumentException("missing")).when(planExecutionService).resumePlan("plan-x");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resumePlan("plan-x"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void featureDisabledShouldRejectMutations() {
        when(planService.isFeatureEnabled()).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.donePlanMode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}
