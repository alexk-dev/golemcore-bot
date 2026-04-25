package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.SessionIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlanServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-04-23T12:00:00Z"), ZoneOffset.UTC);
    private static final SessionIdentity SESSION = new SessionIdentity("telegram", "chat-1");

    private PlanService service;

    @BeforeEach
    void setUp() {
        service = new PlanService(CLOCK);
    }

    @Test
    void shouldBeAlwaysAvailableWithoutRuntimeSettings() {
        assertTrue(service.isFeatureEnabled());
    }

    @Test
    void shouldActivateAndDeactivateSessionScopedPlanMode() {
        service.activatePlanMode(SESSION, "transport-chat", "smart");

        assertTrue(service.isPlanModeActive(SESSION));
        assertNotNull(service.getActivePlanId(SESSION));
        assertTrue(service.hasActivePlans(SESSION));

        service.deactivatePlanMode(SESSION);

        assertFalse(service.isPlanModeActive(SESSION));
        assertNull(service.getActivePlanId(SESSION));
        assertFalse(service.hasActivePlans(SESSION));
    }

    @Test
    void shouldKeepPlanModeScopedToSession() {
        SessionIdentity otherSession = new SessionIdentity("telegram", "chat-2");

        service.activatePlanMode(SESSION, "transport-chat", null);

        assertTrue(service.isPlanModeActive(SESSION));
        assertFalse(service.isPlanModeActive(otherSession));
    }

    @Test
    void shouldCreateOnlyInMemoryEphemeralPlanRecord() {
        service.activatePlanMode(SESSION, "transport-chat", "smart");

        Plan plan = service.getActivePlan(SESSION).orElseThrow();

        assertEquals("telegram", plan.getChannelType());
        assertEquals("chat-1", plan.getChatId());
        assertEquals("transport-chat", plan.getTransportChatId());
        assertEquals("smart", plan.getModelTier());
        assertEquals(Plan.PlanStatus.COLLECTING, plan.getStatus());
        assertEquals(1, service.getPlans(SESSION).size());
    }

    @Test
    void shouldUseConfiguredPlanTierWhenActivationDoesNotProvideOne() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getPlanModelTier()).thenReturn("deep");
        PlanService configuredService = new PlanService(CLOCK, runtimeConfigService);

        configuredService.activatePlanMode(SESSION, "transport-chat", null);

        assertEquals("deep", configuredService.getActivePlan(SESSION).orElseThrow().getModelTier());
    }

    @Test
    void shouldPreferExplicitPlanTierOverConfiguredDefault() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getPlanModelTier()).thenReturn("deep");
        PlanService configuredService = new PlanService(CLOCK, runtimeConfigService);

        configuredService.activatePlanMode(SESSION, "transport-chat", "coding");

        assertEquals("coding", configuredService.getActivePlan(SESSION).orElseThrow().getModelTier());
    }

    @Test
    void shouldBuildEphemeralPlanContextThatDelegatesDurableTrackingToGoalsTasks() {
        service.activatePlanMode(SESSION, "transport-chat", null);

        String context = service.buildPlanContext(SESSION);
        String planFilePath = service.getActivePlanFilePath(SESSION).orElseThrow();

        assertNotNull(context);
        assertTrue(context.contains("Plan mode is ACTIVE"));
        assertTrue(context.contains("ephemeral"));
        assertTrue(context.contains(planFilePath));
        assertTrue(context.contains("plan_exit"));
        assertTrue(context.contains("plan_markdown"));
        assertTrue(context.contains("Use session goals/tasks"));
        assertTrue(context.contains("no separate plan storage"));
        assertFalse(context.contains("Filesystem access is limited"));
        assertFalse(context.contains("goal_management` tool remains available"));
    }

    @Test
    void shouldExposeStableSessionPlanFilePath() {
        service.activatePlanMode(SESSION, "transport-chat", null);

        String planFilePath = service.getActivePlanFilePath(SESSION).orElseThrow();

        assertTrue(planFilePath.startsWith(".golemcore/plans/"));
        assertTrue(planFilePath.endsWith(".md"));
        assertEquals(planFilePath, service.getActivePlanFilePath(SESSION).orElseThrow());
    }

    @Test
    void shouldCompletePlanModeWithOneShotExecutionReminder() {
        service.activatePlanMode(SESSION, "transport-chat", null);
        String planFilePath = service.getActivePlanFilePath(SESSION).orElseThrow();

        service.completePlanMode(SESSION);

        assertFalse(service.isPlanModeActive(SESSION));
        assertFalse(service.hasActivePlans(SESSION));
        assertTrue(service.hasPendingExecutionContext(SESSION));
        String context = service.consumeExecutionContext(SESSION);
        assertNotNull(context);
        assertTrue(context.contains("Plan mode has ended"));
        assertTrue(context.contains(planFilePath));
        assertFalse(context.contains("file exists"));
        assertTrue(context.contains("if it exists"));
        assertFalse(service.hasPendingExecutionContext(SESSION));
        assertNull(service.consumeExecutionContext(SESSION));
    }

    @Test
    void shouldPeekExecutionReminderWithoutConsumingIt() {
        service.activatePlanMode(SESSION, "transport-chat", null);
        String planFilePath = service.getActivePlanFilePath(SESSION).orElseThrow();
        service.completePlanMode(SESSION);

        String context = service.peekExecutionContext(SESSION);

        assertNotNull(context);
        assertTrue(context.contains(planFilePath));
        assertTrue(service.hasPendingExecutionContext(SESSION));
        assertNotNull(service.consumeExecutionContext(SESSION));
        assertFalse(service.hasPendingExecutionContext(SESSION));
    }

    @Test
    void shouldNotRetainInactiveEphemeralPlanRecordsAsActivePlans() {
        service.activatePlanMode(SESSION, "transport-chat", null);

        service.completePlanMode(SESSION);

        assertFalse(service.hasActivePlans(SESSION));
        assertTrue(service.getPlans(SESSION).stream()
                .noneMatch(plan -> plan.getStatus() == Plan.PlanStatus.COLLECTING));
    }
}
