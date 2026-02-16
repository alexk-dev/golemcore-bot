package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PlanStep;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanServiceTest {

    private static final String AUTO_DIR = "auto";
    private static final String PLANS_FILE = "plans.json";
    private static final String TEST_CHAT_ID = "chat-123";
    private static final String TEST_MODEL_TIER = "smart";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-02-11T10:00:00Z");

    private static final String PLAN_ABC = "plan-abc";
    private static final String NONEXISTENT_PLAN = "nonexistent-plan";
    private static final String PLAN_EXEC = "plan-exec";
    private static final String PLAN_COMPLETE = "plan-complete";
    private static final String PLAN_PARTIAL = "plan-partial";
    private static final String TOOL_FILESYSTEM = "filesystem";
    private static final String TOOL_SHELL = "shell";
    private static final String STEP_1 = "step-1";
    private static final String NONEXISTENT = "nonexistent";

    private StoragePort storagePort;
    private ObjectMapper objectMapper;
    private BotProperties properties;
    private Clock clock;
    private PlanService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        properties = new BotProperties();
        properties.getPlan().setEnabled(true);
        properties.getPlan().setMaxPlans(5);
        properties.getPlan().setMaxStepsPerPlan(50);

        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.getText(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        service = new PlanService(storagePort, objectMapper, properties, clock);
    }

    // ==================== 1. shouldCreatePlanSuccessfully ====================

    @Test
    void shouldCreatePlanSuccessfully() {
        // Arrange — storage returns empty (no existing plans)

        // Act
        Plan plan = service.createPlan("Deploy service", "Deploy to production", TEST_CHAT_ID, TEST_MODEL_TIER);

        // Assert ? plan work stays active until /plan done or /reset
        assertNotNull(plan);
        assertNotNull(plan.getId());
        assertEquals("Deploy service", plan.getTitle());
        assertEquals("Deploy to production", plan.getDescription());
        assertEquals(TEST_CHAT_ID, plan.getChatId());
        assertEquals(TEST_MODEL_TIER, plan.getModelTier());
        assertEquals(Plan.PlanStatus.COLLECTING, plan.getStatus());
        assertEquals(FIXED_INSTANT, plan.getCreatedAt());
        assertEquals(FIXED_INSTANT, plan.getUpdatedAt());
        assertNotNull(plan.getSteps());
        assertTrue(plan.getSteps().isEmpty());

        verify(storagePort).putText(eq(AUTO_DIR), eq(PLANS_FILE), anyString());
    }

    // ==================== 2. shouldThrowWhenMaxPlansReached ====================

    @Test
    void shouldThrowWhenMaxPlansReached() throws Exception {
        // Arrange — pre-load 5 active plans (maxPlans = 5)
        List<Plan> existingPlans = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            existingPlans.add(Plan.builder()
                    .id("plan-" + i)
                    .title("Plan " + i)
                    .status(Plan.PlanStatus.COLLECTING)
                    .steps(new ArrayList<>())
                    .createdAt(FIXED_INSTANT)
                    .updatedAt(FIXED_INSTANT)
                    .build());
        }
        String plansJson = objectMapper.writeValueAsString(existingPlans);
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.createPlan("One too many", "Should fail", TEST_CHAT_ID, TEST_MODEL_TIER));
        assertTrue(exception.getMessage().contains("Maximum active plans reached"));
    }

    @Test
    void shouldAllowCreationWhenCompletedPlansDoNotCountTowardMax() throws Exception {
        // Arrange — 5 plans but all COMPLETED (not counted as active)
        List<Plan> existingPlans = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            existingPlans.add(Plan.builder()
                    .id("plan-" + i)
                    .title("Plan " + i)
                    .status(Plan.PlanStatus.COMPLETED)
                    .steps(new ArrayList<>())
                    .createdAt(FIXED_INSTANT)
                    .updatedAt(FIXED_INSTANT)
                    .build());
        }
        String plansJson = objectMapper.writeValueAsString(existingPlans);
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        Plan plan = service.createPlan("New plan", "Should succeed", TEST_CHAT_ID, TEST_MODEL_TIER);

        // Assert ? plan work stays active until /plan done or /reset
        assertNotNull(plan);
        assertEquals(Plan.PlanStatus.COLLECTING, plan.getStatus());
    }

    // ==================== 3. shouldActivateAndDeactivatePlanMode
    // ====================

    @Test
    void shouldActivateAndDeactivatePlanMode() {
        // Arrange
        assertFalse(service.isPlanModeActive());
        assertNull(service.getActivePlanId());

        // Act ? activate
        service.activatePlanMode(TEST_CHAT_ID, TEST_MODEL_TIER);

        // Assert ? activated
        assertTrue(service.isPlanModeActive());
        assertNotNull(service.getActivePlanId());

        // Act ? deactivate
        service.deactivatePlanMode();

        // Assert ? deactivated
        assertFalse(service.isPlanModeActive());
        assertNull(service.getActivePlanId());
    }

    @Test
    void shouldCreatePlanWhenActivatingPlanMode() {
        // Act
        service.activatePlanMode(TEST_CHAT_ID, TEST_MODEL_TIER);

        // Assert ? plan work stays active until /plan done or /reset — plan was created
        // and is accessible
        String activePlanId = service.getActivePlanId();
        assertNotNull(activePlanId);

        Optional<Plan> activePlan = service.getActivePlan();
        assertTrue(activePlan.isPresent());
        assertEquals(activePlanId, activePlan.get().getId());
        assertEquals(Plan.PlanStatus.COLLECTING, activePlan.get().getStatus());
    }

    // ==================== 4. shouldAddStepsToExistingPlan ====================

    @Test
    void shouldAddStepsToExistingPlan() throws Exception {
        // Arrange — create a plan first
        Plan existingPlan = Plan.builder()
                .id(PLAN_ABC)
                .title("Build feature")
                .status(Plan.PlanStatus.COLLECTING)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(existingPlan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        Map<String, Object> args = Map.of("path", "/src/main/App.java", "content", "class App {}");

        // Act
        PlanStep step = service.addStep(PLAN_ABC, TOOL_FILESYSTEM, args, "Create main application file");

        // Assert ? plan work stays active until /plan done or /reset
        assertNotNull(step);
        assertNotNull(step.getId());
        assertEquals(PLAN_ABC, step.getPlanId());
        assertEquals(TOOL_FILESYSTEM, step.getToolName());
        assertEquals("Create main application file", step.getDescription());
        assertEquals(args, step.getToolArguments());
        assertEquals(0, step.getOrder());
        assertEquals(PlanStep.StepStatus.PENDING, step.getStatus());
        assertEquals(FIXED_INSTANT, step.getCreatedAt());

        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(PLANS_FILE), anyString());
    }

    @Test
    void shouldAssignIncrementingOrderToSteps() throws Exception {
        // Arrange — plan with one existing step
        PlanStep existingStep = PlanStep.builder()
                .id(STEP_1)
                .planId(PLAN_ABC)
                .toolName(TOOL_SHELL)
                .order(0)
                .status(PlanStep.StepStatus.PENDING)
                .createdAt(FIXED_INSTANT)
                .build();
        Plan existingPlan = Plan.builder()
                .id(PLAN_ABC)
                .title("Build feature")
                .status(Plan.PlanStatus.COLLECTING)
                .steps(new ArrayList<>(List.of(existingStep)))
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(existingPlan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        PlanStep secondStep = service.addStep(PLAN_ABC, TOOL_FILESYSTEM, Map.of("path", "/test"), "Add test file");

        // Assert ? plan work stays active until /plan done or /reset — order should be
        // 1 (second step)
        assertEquals(1, secondStep.getOrder());
    }

    @Test
    void shouldThrowWhenAddingStepToNonExistentPlan() {
        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> service.addStep(NONEXISTENT, TOOL_SHELL, Map.of(), "Run command"));
    }

    // ==================== 5. shouldThrowWhenMaxStepsReached ====================

    @Test
    void shouldThrowWhenMaxStepsReached() throws Exception {
        // Arrange — plan with maxStepsPerPlan (50) steps already
        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            steps.add(PlanStep.builder()
                    .id("step-" + i)
                    .planId("plan-full")
                    .toolName(TOOL_SHELL)
                    .description("Step " + i)
                    .order(i)
                    .status(PlanStep.StepStatus.PENDING)
                    .createdAt(FIXED_INSTANT)
                    .build());
        }
        Plan fullPlan = Plan.builder()
                .id("plan-full")
                .title("Full plan")
                .status(Plan.PlanStatus.COLLECTING)
                .steps(steps)
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(fullPlan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.addStep("plan-full", TOOL_FILESYSTEM, Map.of(), "Too many steps"));
        assertTrue(exception.getMessage().contains("Maximum steps per plan reached"));
    }

    // ==================== 6. shouldFinalizePlanFromCollectingToReady
    // ====================

    @Test
    void shouldFinalizePlanFromCollectingToReady() throws Exception {
        // Arrange
        Plan plan = Plan.builder()
                .id("plan-fin")
                .title("Finalize me")
                .status(Plan.PlanStatus.COLLECTING)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Also set this plan as active
        service.activatePlanMode(TEST_CHAT_ID, TEST_MODEL_TIER);
        // We need to use the plan id from the actual activated plan
        // Since activatePlanMode creates a new plan, let's reload and test differently

        // Reset and use a specific plan
        service = new PlanService(storagePort, objectMapper, properties, clock);
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        service.finalizePlan("plan-fin", "# Plan", null);

        // Assert ? plan work stays active until /plan done or /reset — verify saved
        // plan status changed to READY
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(PLANS_FILE), captor.capture());

        String savedJson = captor.getValue();
        List<Plan> savedPlans = objectMapper.readValue(savedJson, new TypeReference<>() {
        });
        Plan savedPlan = savedPlans.stream()
                .filter(p -> "plan-fin".equals(p.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(Plan.PlanStatus.READY, savedPlan.getStatus());
        assertEquals(FIXED_INSTANT, savedPlan.getUpdatedAt());
    }

    @Test
    void shouldKeepPlanModeActiveWhenFinalizing() throws Exception {
        // Arrange — create a plan via activatePlanMode so plan mode is active
        service.activatePlanMode(TEST_CHAT_ID, TEST_MODEL_TIER);
        String activePlanId = service.getActivePlanId();
        assertTrue(service.isPlanModeActive());

        // Act
        service.finalizePlan(activePlanId, "# Plan", null);

        // Assert ? plan work stays active until /plan done or /reset
        assertTrue(service.isPlanModeActive());
        assertNotNull(service.getActivePlanId());
    }

    // ==================== 7. shouldThrowWhenFinalizingNonCollectingPlan
    // ====================

    @Test
    void shouldAllowUpdatingReadyPlanMarkdown() throws Exception {
        // Arrange ? plan in READY state
        Plan plan = Plan.builder()
                .id("plan-ready")
                .title("Already ready")
                .status(Plan.PlanStatus.READY)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        service.finalizePlan("plan-ready", "# Updated plan", null);

        // Assert ? plan work stays active until /plan done or /reset
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(PLANS_FILE), captor.capture());

        String savedJson = captor.getValue();
        List<Plan> savedPlans = objectMapper.readValue(savedJson, new TypeReference<>() {
        });
        Plan savedPlan = savedPlans.stream()
                .filter(p -> "plan-ready".equals(p.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(Plan.PlanStatus.READY, savedPlan.getStatus());
        assertEquals("# Updated plan", savedPlan.getMarkdown());
    }

    @Test
    void shouldThrowWhenFinalizingNonExistentPlan() {
        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> service.finalizePlan(NONEXISTENT_PLAN, "# Plan", null));
    }

    // ==================== 8. shouldApprovePlanFromReady ====================

    @Test
    void shouldApprovePlanFromReady() throws Exception {
        // Arrange
        Plan plan = Plan.builder()
                .id("plan-approve")
                .title("Approve me")
                .status(Plan.PlanStatus.READY)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        service.approvePlan("plan-approve");

        // Assert ? plan work stays active until /plan done or /reset
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(PLANS_FILE), captor.capture());

        List<Plan> savedPlans = objectMapper.readValue(captor.getValue(), new TypeReference<>() {
        });
        Plan savedPlan = savedPlans.stream()
                .filter(p -> "plan-approve".equals(p.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(Plan.PlanStatus.APPROVED, savedPlan.getStatus());
        assertEquals(FIXED_INSTANT, savedPlan.getUpdatedAt());
    }

    @Test
    void shouldThrowWhenApprovingNonReadyPlan() throws Exception {
        // Arrange — plan in COLLECTING state
        Plan plan = Plan.builder()
                .id("plan-coll")
                .title("Still collecting")
                .status(Plan.PlanStatus.COLLECTING)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.approvePlan("plan-coll"));
        assertTrue(exception.getMessage().contains("Can only approve plans in READY state"));
    }

    @Test
    void shouldThrowWhenApprovingNonExistentPlan() {
        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> service.approvePlan(NONEXISTENT_PLAN));
    }

    // ==================== 9. shouldCancelPlan ====================

    @Test
    void shouldCancelPlan() throws Exception {
        // Arrange
        Plan plan = Plan.builder()
                .id("plan-cancel")
                .title("Cancel me")
                .status(Plan.PlanStatus.EXECUTING)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        service.cancelPlan("plan-cancel");

        // Assert ? plan work stays active until /plan done or /reset
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(PLANS_FILE), captor.capture());

        List<Plan> savedPlans = objectMapper.readValue(captor.getValue(), new TypeReference<>() {
        });
        Plan savedPlan = savedPlans.stream()
                .filter(p -> "plan-cancel".equals(p.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(Plan.PlanStatus.CANCELLED, savedPlan.getStatus());
    }

    @Test
    void shouldDeactivatePlanModeWhenCancellingActivePlan() throws Exception {
        // Arrange — activate plan mode first
        service.activatePlanMode(TEST_CHAT_ID, TEST_MODEL_TIER);
        String activePlanId = service.getActivePlanId();
        assertTrue(service.isPlanModeActive());

        // Act
        service.cancelPlan(activePlanId);

        // Assert
        assertFalse(service.isPlanModeActive());
        assertNull(service.getActivePlanId());
    }

    @Test
    void shouldNotDeactivatePlanModeWhenCancellingNonActivePlan() throws Exception {
        // Arrange — activate plan mode, then cancel a different plan
        service.activatePlanMode(TEST_CHAT_ID, TEST_MODEL_TIER);

        Plan otherPlan = Plan.builder()
                .id("plan-other")
                .title("Other plan")
                .status(Plan.PlanStatus.READY)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        // Need to reload plans cache with both plans
        List<Plan> allPlans = new ArrayList<>();
        allPlans.add(otherPlan);
        allPlans.addAll(service.getPlans());
        String plansJson = objectMapper.writeValueAsString(allPlans);
        // Reset the service to reload the cache
        service = new PlanService(storagePort, objectMapper, properties, clock);
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Recovery restores plan mode from the persisted COLLECTING plan
        // Cancelling "plan-other" (READY) should NOT affect the recovered plan mode
        // state
        service.cancelPlan("plan-other");

        // Assert ? plan work stays active until /plan done or /reset — plan mode
        // remains active (recovered from the COLLECTING plan)
        assertTrue(service.isPlanModeActive());
    }

    // ==================== 10. shouldGetNextPendingStep ====================

    @Test
    void shouldGetNextPendingStep() throws Exception {
        // Arrange — plan with completed step and two pending steps
        PlanStep completedStep = PlanStep.builder()
                .id("step-0")
                .planId(PLAN_EXEC)
                .toolName(TOOL_SHELL)
                .description("Run setup")
                .order(0)
                .status(PlanStep.StepStatus.COMPLETED)
                .createdAt(FIXED_INSTANT)
                .build();
        PlanStep pendingStep1 = PlanStep.builder()
                .id(STEP_1)
                .planId(PLAN_EXEC)
                .toolName(TOOL_FILESYSTEM)
                .description("Write config")
                .order(1)
                .status(PlanStep.StepStatus.PENDING)
                .createdAt(FIXED_INSTANT)
                .build();
        PlanStep pendingStep2 = PlanStep.builder()
                .id("step-2")
                .planId(PLAN_EXEC)
                .toolName(TOOL_SHELL)
                .description("Run deploy")
                .order(2)
                .status(PlanStep.StepStatus.PENDING)
                .createdAt(FIXED_INSTANT)
                .build();
        Plan plan = Plan.builder()
                .id(PLAN_EXEC)
                .title("Execute plan")
                .status(Plan.PlanStatus.EXECUTING)
                .steps(new ArrayList<>(List.of(completedStep, pendingStep1, pendingStep2)))
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        Optional<PlanStep> nextStep = service.getNextPendingStep(PLAN_EXEC);

        // Assert ? plan work stays active until /plan done or /reset — should return
        // step-1 (lowest order among pending)
        assertTrue(nextStep.isPresent());
        assertEquals(STEP_1, nextStep.get().getId());
        assertEquals(PlanStep.StepStatus.PENDING, nextStep.get().getStatus());
        assertEquals(1, nextStep.get().getOrder());
    }

    @Test
    void shouldReturnEmptyWhenNoPendingSteps() throws Exception {
        // Arrange — all steps completed
        PlanStep completedStep = PlanStep.builder()
                .id("step-0")
                .planId("plan-done")
                .toolName(TOOL_SHELL)
                .description("Done step")
                .order(0)
                .status(PlanStep.StepStatus.COMPLETED)
                .createdAt(FIXED_INSTANT)
                .build();
        Plan plan = Plan.builder()
                .id("plan-done")
                .title("Done plan")
                .status(Plan.PlanStatus.EXECUTING)
                .steps(new ArrayList<>(List.of(completedStep)))
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        Optional<PlanStep> nextStep = service.getNextPendingStep("plan-done");

        // Assert ? plan work stays active until /plan done or /reset
        assertTrue(nextStep.isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenPlanNotFoundForNextPendingStep() {
        // Act
        Optional<PlanStep> nextStep = service.getNextPendingStep(NONEXISTENT_PLAN);

        // Assert ? plan work stays active until /plan done or /reset
        assertTrue(nextStep.isEmpty());
    }

    // ==================== 11. shouldBuildPlanContext ====================

    @Test
    void shouldBuildPlanContext() throws Exception {
        // Arrange — activate plan mode with steps
        service.activatePlanMode(TEST_CHAT_ID, TEST_MODEL_TIER);
        String activePlanId = service.getActivePlanId();

        // Add steps to the active plan

        // Act
        String context = service.buildPlanContext();

        // Assert ? plan work stays active until /plan done or /reset
        assertNotNull(context);
        assertTrue(context.contains("# Plan Work"));
        assertTrue(context.contains("Plan work is ACTIVE"));
    }

    @Test
    void shouldReturnNullContextWhenNoPlanActive() {
        // Arrange — no plan mode active
        assertFalse(service.isPlanModeActive());

        // Act
        String context = service.buildPlanContext();

        // Assert ? plan work stays active until /plan done or /reset
        assertNull(context);
    }

    @Test
    void shouldBuildPlanContextWithNoSteps() {
        // Arrange — activate plan mode, no steps added
        service.activatePlanMode(TEST_CHAT_ID, TEST_MODEL_TIER);

        // Act
        String context = service.buildPlanContext();

        // Assert ? plan work stays active until /plan done or /reset
        assertNotNull(context);
        assertTrue(context.contains("# Plan Work"));
    }

    // ==================== 12. shouldDeletePlan ====================

    @Test
    void shouldDeletePlan() throws Exception {
        // Arrange
        Plan plan1 = Plan.builder()
                .id("plan-keep")
                .title("Keep this one")
                .status(Plan.PlanStatus.READY)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        Plan plan2 = Plan.builder()
                .id("plan-delete")
                .title("Delete this one")
                .status(Plan.PlanStatus.COLLECTING)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan1, plan2));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        service.deletePlan("plan-delete");

        // Assert ? plan work stays active until /plan done or /reset
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(PLANS_FILE), captor.capture());

        List<Plan> savedPlans = objectMapper.readValue(captor.getValue(), new TypeReference<>() {
        });
        assertEquals(1, savedPlans.size());
        assertEquals("plan-keep", savedPlans.get(0).getId());
    }

    @Test
    void shouldThrowWhenDeletingNonExistentPlan() {
        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> service.deletePlan(NONEXISTENT));
    }

    @Test
    void shouldDeactivatePlanModeWhenDeletingActivePlan() {
        // Arrange — activate plan mode
        service.activatePlanMode(TEST_CHAT_ID, TEST_MODEL_TIER);
        String activePlanId = service.getActivePlanId();
        assertTrue(service.isPlanModeActive());

        // Act
        service.deletePlan(activePlanId);

        // Assert
        assertFalse(service.isPlanModeActive());
        assertNull(service.getActivePlanId());
    }

    // ==================== 13. shouldMarkStepLifecycle ====================

    @Test
    void shouldMarkStepInProgress() throws Exception {
        // Arrange
        PlanStep step = PlanStep.builder()
                .id("step-ip")
                .planId("plan-lc")
                .toolName(TOOL_SHELL)
                .description("Run command")
                .order(0)
                .status(PlanStep.StepStatus.PENDING)
                .createdAt(FIXED_INSTANT)
                .build();
        Plan plan = Plan.builder()
                .id("plan-lc")
                .title("Lifecycle plan")
                .status(Plan.PlanStatus.EXECUTING)
                .steps(new ArrayList<>(List.of(step)))
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        service.markStepInProgress("plan-lc", "step-ip");

        // Assert ? plan work stays active until /plan done or /reset
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(PLANS_FILE), captor.capture());

        List<Plan> savedPlans = objectMapper.readValue(captor.getValue(), new TypeReference<>() {
        });
        PlanStep savedStep = savedPlans.get(0).getSteps().get(0);
        assertEquals(PlanStep.StepStatus.IN_PROGRESS, savedStep.getStatus());
    }

    @Test
    void shouldMarkStepCompleted() throws Exception {
        // Arrange
        PlanStep step = PlanStep.builder()
                .id("step-comp")
                .planId("plan-lc2")
                .toolName(TOOL_FILESYSTEM)
                .description("Write file")
                .order(0)
                .status(PlanStep.StepStatus.IN_PROGRESS)
                .createdAt(FIXED_INSTANT)
                .build();
        Plan plan = Plan.builder()
                .id("plan-lc2")
                .title("Lifecycle plan 2")
                .status(Plan.PlanStatus.EXECUTING)
                .steps(new ArrayList<>(List.of(step)))
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        service.markStepCompleted("plan-lc2", "step-comp", "File written successfully");

        // Assert ? plan work stays active until /plan done or /reset
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(PLANS_FILE), captor.capture());

        List<Plan> savedPlans = objectMapper.readValue(captor.getValue(), new TypeReference<>() {
        });
        PlanStep savedStep = savedPlans.get(0).getSteps().get(0);
        assertEquals(PlanStep.StepStatus.COMPLETED, savedStep.getStatus());
        assertEquals("File written successfully", savedStep.getResult());
        assertEquals(FIXED_INSTANT, savedStep.getExecutedAt());
    }

    @Test
    void shouldMarkStepFailed() throws Exception {
        // Arrange
        PlanStep step = PlanStep.builder()
                .id("step-fail")
                .planId("plan-lc3")
                .toolName(TOOL_SHELL)
                .description("Run failing command")
                .order(0)
                .status(PlanStep.StepStatus.IN_PROGRESS)
                .createdAt(FIXED_INSTANT)
                .build();
        Plan plan = Plan.builder()
                .id("plan-lc3")
                .title("Lifecycle plan 3")
                .status(Plan.PlanStatus.EXECUTING)
                .steps(new ArrayList<>(List.of(step)))
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        service.markStepFailed("plan-lc3", "step-fail", "Command exited with code 1");

        // Assert ? plan work stays active until /plan done or /reset
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(PLANS_FILE), captor.capture());

        List<Plan> savedPlans = objectMapper.readValue(captor.getValue(), new TypeReference<>() {
        });
        PlanStep savedStep = savedPlans.get(0).getSteps().get(0);
        assertEquals(PlanStep.StepStatus.FAILED, savedStep.getStatus());
        assertEquals("Command exited with code 1", savedStep.getResult());
        assertEquals(FIXED_INSTANT, savedStep.getExecutedAt());
    }

    @Test
    void shouldThrowWhenMarkingStepOnNonExistentPlan() {
        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> service.markStepInProgress(NONEXISTENT_PLAN, STEP_1));
        assertThrows(IllegalArgumentException.class,
                () -> service.markStepCompleted(NONEXISTENT_PLAN, STEP_1, "result"));
        assertThrows(IllegalArgumentException.class,
                () -> service.markStepFailed(NONEXISTENT_PLAN, STEP_1, "error"));
    }

    @Test
    void shouldThrowWhenMarkingNonExistentStep() throws Exception {
        // Arrange — plan exists but step does not
        Plan plan = Plan.builder()
                .id("plan-nostep")
                .title("No step plan")
                .status(Plan.PlanStatus.EXECUTING)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.markStepInProgress("plan-nostep", "nonexistent-step"));
        assertTrue(exception.getMessage().contains("Step not found"));
    }

    // ==================== 14. shouldCompletePlan ====================

    @Test
    void shouldCompletePlan() throws Exception {
        // Arrange
        PlanStep completedStep = PlanStep.builder()
                .id("step-done")
                .planId(PLAN_COMPLETE)
                .toolName(TOOL_SHELL)
                .description("Final step")
                .order(0)
                .status(PlanStep.StepStatus.COMPLETED)
                .result("Success")
                .createdAt(FIXED_INSTANT)
                .executedAt(FIXED_INSTANT)
                .build();
        Plan plan = Plan.builder()
                .id(PLAN_COMPLETE)
                .title("Complete plan")
                .status(Plan.PlanStatus.EXECUTING)
                .steps(new ArrayList<>(List.of(completedStep)))
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        service.completePlan(PLAN_COMPLETE);

        // Assert ? plan work stays active until /plan done or /reset
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(PLANS_FILE), captor.capture());

        List<Plan> savedPlans = objectMapper.readValue(captor.getValue(), new TypeReference<>() {
        });
        Plan savedPlan = savedPlans.stream()
                .filter(p -> PLAN_COMPLETE.equals(p.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(Plan.PlanStatus.COMPLETED, savedPlan.getStatus());
        assertEquals(FIXED_INSTANT, savedPlan.getUpdatedAt());
    }

    @Test
    void shouldThrowWhenCompletingNonExistentPlan() {
        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> service.completePlan(NONEXISTENT));
    }

    // ==================== 15. shouldMarkPlanPartiallyCompleted
    // ====================

    @Test
    void shouldMarkPlanPartiallyCompleted() throws Exception {
        // Arrange — plan with mix of completed and failed steps
        PlanStep completedStep = PlanStep.builder()
                .id("step-ok")
                .planId(PLAN_PARTIAL)
                .toolName(TOOL_FILESYSTEM)
                .description("Write file")
                .order(0)
                .status(PlanStep.StepStatus.COMPLETED)
                .result("Written")
                .createdAt(FIXED_INSTANT)
                .executedAt(FIXED_INSTANT)
                .build();
        PlanStep failedStep = PlanStep.builder()
                .id("step-bad")
                .planId(PLAN_PARTIAL)
                .toolName(TOOL_SHELL)
                .description("Run deploy")
                .order(1)
                .status(PlanStep.StepStatus.FAILED)
                .result("Timeout")
                .createdAt(FIXED_INSTANT)
                .executedAt(FIXED_INSTANT)
                .build();
        Plan plan = Plan.builder()
                .id(PLAN_PARTIAL)
                .title("Partial plan")
                .status(Plan.PlanStatus.EXECUTING)
                .steps(new ArrayList<>(List.of(completedStep, failedStep)))
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        service.markPlanPartiallyCompleted(PLAN_PARTIAL);

        // Assert ? plan work stays active until /plan done or /reset
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(PLANS_FILE), captor.capture());

        List<Plan> savedPlans = objectMapper.readValue(captor.getValue(), new TypeReference<>() {
        });
        Plan savedPlan = savedPlans.stream()
                .filter(p -> PLAN_PARTIAL.equals(p.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(Plan.PlanStatus.PARTIALLY_COMPLETED, savedPlan.getStatus());
        assertEquals(FIXED_INSTANT, savedPlan.getUpdatedAt());
    }

    @Test
    void shouldThrowWhenMarkingPartiallyCompletedNonExistentPlan() {
        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> service.markPlanPartiallyCompleted(NONEXISTENT));
    }

    // ==================== Additional edge case tests ====================

    @Test
    void shouldMarkPlanExecuting() throws Exception {
        // Arrange
        Plan plan = Plan.builder()
                .id(PLAN_EXEC)
                .title("Execute me")
                .status(Plan.PlanStatus.APPROVED)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        service.markPlanExecuting(PLAN_EXEC);

        // Assert ? plan work stays active until /plan done or /reset
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(PLANS_FILE), captor.capture());

        List<Plan> savedPlans = objectMapper.readValue(captor.getValue(), new TypeReference<>() {
        });
        assertEquals(Plan.PlanStatus.EXECUTING, savedPlans.get(0).getStatus());
    }

    @Test
    void shouldReturnFeatureEnabledFromProperties() {
        // Arrange & Act & Assert
        properties.getPlan().setEnabled(true);
        assertTrue(service.isFeatureEnabled());

        properties.getPlan().setEnabled(false);
        assertFalse(service.isFeatureEnabled());
    }

    @Test
    void shouldGetPlanById() throws Exception {
        // Arrange
        Plan plan = Plan.builder()
                .id("plan-find")
                .title("Find me")
                .status(Plan.PlanStatus.READY)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();
        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act
        Optional<Plan> found = service.getPlan("plan-find");
        Optional<Plan> notFound = service.getPlan(NONEXISTENT);

        // Assert ? plan work stays active until /plan done or /reset
        assertTrue(found.isPresent());
        assertEquals("plan-find", found.get().getId());
        assertTrue(notFound.isEmpty());
    }

    @Test
    void shouldReturnEmptyActivePlanWhenNoneActive() {
        // Act
        Optional<Plan> activePlan = service.getActivePlan();

        // Assert ? plan work stays active until /plan done or /reset
        assertTrue(activePlan.isEmpty());
    }

    @Test
    void shouldLoadEmptyPlansWhenStorageReturnsNull() {
        // Arrange — storagePort returns null (no file exists)
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        List<Plan> plans = service.getPlans();

        // Assert ? plan work stays active until /plan done or /reset
        assertNotNull(plans);
        assertTrue(plans.isEmpty());
    }

    @Test
    void shouldLoadEmptyPlansWhenStorageReturnsBlank() {
        // Arrange — storagePort returns blank string
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture("   "));

        // Act
        List<Plan> plans = service.getPlans();

        // Assert ? plan work stays active until /plan done or /reset
        assertNotNull(plans);
        assertTrue(plans.isEmpty());
    }

    @Test
    void shouldHandleCorruptedStorageGracefully() {
        // Arrange — storagePort returns invalid JSON
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture("{invalid json!!!}"));

        // Act — should not throw, returns empty list as fallback
        List<Plan> plans = service.getPlans();

        // Assert ? plan work stays active until /plan done or /reset
        assertNotNull(plans);
        assertTrue(plans.isEmpty());
    }

    @Test
    void shouldCountOnlyActiveStatusesForMaxPlansCheck() throws Exception {
        // Arrange — 4 active plans (two READY plus COLLECTING and APPROVED) and 3
        // terminal plans
        List<Plan> plans = new ArrayList<>();
        plans.add(Plan.builder().id("p1").status(Plan.PlanStatus.COLLECTING).steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT).updatedAt(FIXED_INSTANT).build());
        plans.add(Plan.builder().id("p2").status(Plan.PlanStatus.READY).steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT).updatedAt(FIXED_INSTANT).build());
        plans.add(Plan.builder().id("p3").status(Plan.PlanStatus.APPROVED).steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT).updatedAt(FIXED_INSTANT).build());
        plans.add(Plan.builder().id("p4").status(Plan.PlanStatus.READY).steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT).updatedAt(FIXED_INSTANT).build());
        plans.add(Plan.builder().id("p5").status(Plan.PlanStatus.COMPLETED).steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT).updatedAt(FIXED_INSTANT).build());
        plans.add(Plan.builder().id("p6").status(Plan.PlanStatus.CANCELLED).steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT).updatedAt(FIXED_INSTANT).build());
        plans.add(Plan.builder().id("p7").status(Plan.PlanStatus.PARTIALLY_COMPLETED).steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT).updatedAt(FIXED_INSTANT).build());

        String plansJson = objectMapper.writeValueAsString(plans);
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        // Act & Assert — 4 active plans out of maxPlans=5, should allow one more
        Plan newPlan = service.createPlan("Allowed", "Should succeed", TEST_CHAT_ID, TEST_MODEL_TIER);
        assertNotNull(newPlan);

        // Now 5 active plans — next one should fail
        // The service caches plans in-memory after save, so calling createPlan again
        // will check cached list
        assertThrows(IllegalStateException.class,
                () -> service.createPlan("Too many", "Should fail", TEST_CHAT_ID, TEST_MODEL_TIER));
    }

    @Test
    void shouldCreatePlanWithNullTitleAndDescription() {
        // Arrange — title and description can be null (as in activatePlanMode)

        // Act
        Plan plan = service.createPlan(null, null, TEST_CHAT_ID, TEST_MODEL_TIER);

        // Assert ? plan work stays active until /plan done or /reset
        assertNotNull(plan);
        assertNull(plan.getTitle());
        assertNull(plan.getDescription());
        assertEquals(Plan.PlanStatus.COLLECTING, plan.getStatus());
    }

    @Test
    void shouldRecoverCollectingPlanAsActiveOnFirstLoadAfterRestart() throws Exception {
        Plan collecting = Plan.builder()
                .id("plan-recover")
                .status(Plan.PlanStatus.COLLECTING)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();

        String plansJson = objectMapper.writeValueAsString(List.of(collecting));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        List<Plan> plans = service.getPlans();

        assertEquals(1, plans.size());
        assertTrue(service.isPlanModeActive());
        assertEquals("plan-recover", service.getActivePlanId());
    }

    @Test
    void shouldRecoverExecutingPlanAsPartiallyCompletedWithFailedInProgressStep() throws Exception {
        PlanStep inProgress = PlanStep.builder()
                .id("step-in-progress")
                .planId("plan-executing")
                .toolName(TOOL_SHELL)
                .description("Running tests")
                .order(0)
                .status(PlanStep.StepStatus.IN_PROGRESS)
                .createdAt(FIXED_INSTANT)
                .build();

        Plan executing = Plan.builder()
                .id("plan-executing")
                .status(Plan.PlanStatus.EXECUTING)
                .steps(new ArrayList<>(List.of(inProgress)))
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();

        String plansJson = objectMapper.writeValueAsString(List.of(executing));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        List<Plan> plans = service.getPlans();

        assertEquals(1, plans.size());
        Plan recovered = plans.get(0);
        assertEquals(Plan.PlanStatus.PARTIALLY_COMPLETED, recovered.getStatus());
        assertEquals(FIXED_INSTANT, recovered.getUpdatedAt());
        assertEquals(1, recovered.getSteps().size());
        PlanStep recoveredStep = recovered.getSteps().get(0);
        assertEquals(PlanStep.StepStatus.FAILED, recoveredStep.getStatus());
        assertEquals("Interrupted by restart/crash during execution", recoveredStep.getResult());
        assertEquals(FIXED_INSTANT, recoveredStep.getExecutedAt());

        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(PLANS_FILE), anyString());
    }

    @Test
    void shouldNotOverwriteExistingStepResultDuringRecovery() throws Exception {
        PlanStep inProgressWithResult = PlanStep.builder()
                .id("step-keep-result")
                .planId("plan-executing-2")
                .toolName(TOOL_FILESYSTEM)
                .description("Write file")
                .order(0)
                .status(PlanStep.StepStatus.IN_PROGRESS)
                .result("Partial output already captured")
                .createdAt(FIXED_INSTANT)
                .build();

        Plan executing = Plan.builder()
                .id("plan-executing-2")
                .status(Plan.PlanStatus.EXECUTING)
                .steps(new ArrayList<>(List.of(inProgressWithResult)))
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();

        String plansJson = objectMapper.writeValueAsString(List.of(executing));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        Plan recovered = service.getPlans().get(0);
        PlanStep recoveredStep = recovered.getSteps().get(0);

        assertEquals(PlanStep.StepStatus.FAILED, recoveredStep.getStatus());
        assertEquals("Partial output already captured", recoveredStep.getResult());
        assertEquals(FIXED_INSTANT, recoveredStep.getExecutedAt());
    }

    @Test
    void shouldReturnActivePlanIdOptional() {
        assertTrue(service.getActivePlanIdOptional().isEmpty());

        service.activatePlanMode(TEST_CHAT_ID, TEST_MODEL_TIER);

        assertTrue(service.getActivePlanIdOptional().isPresent());
        assertEquals(service.getActivePlanId(), service.getActivePlanIdOptional().orElseThrow());
    }

    @Test
    void shouldFinalizeWithDefaultMarkdownWhenCallingConvenienceFinalize() throws Exception {
        Plan plan = Plan.builder()
                .id("plan-default-md")
                .title("Plan without markdown")
                .status(Plan.PlanStatus.COLLECTING)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();

        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        service.finalizePlan("plan-default-md");

        Plan saved = service.getPlan("plan-default-md").orElseThrow();
        assertEquals(Plan.PlanStatus.READY, saved.getStatus());
        assertTrue(saved.getMarkdown().contains("# Plan"));
        assertTrue(saved.getMarkdown().contains("TODO: plan_markdown not provided"));
    }

    @Test
    void shouldThrowWhenFinalizingWithBlankMarkdown() throws Exception {
        Plan plan = Plan.builder()
                .id("plan-blank-md")
                .status(Plan.PlanStatus.COLLECTING)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();

        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.finalizePlan("plan-blank-md", "   ", null));
        assertTrue(ex.getMessage().contains("plan_markdown must not be blank"));
    }

    @Test
    void shouldThrowWhenFinalizingInTerminalStatus() throws Exception {
        Plan plan = Plan.builder()
                .id("plan-terminal")
                .status(Plan.PlanStatus.CANCELLED)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();

        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.finalizePlan("plan-terminal", "# Any", null));
        assertTrue(ex.getMessage().contains("Can only finalize/update plans"));
    }

    @Test
    void shouldCreateRevisionWhenFinalizingExecutingPlan() throws Exception {
        Plan plan = Plan.builder()
                .id("plan-executing-revision")
                .title("Old title")
                .chatId(TEST_CHAT_ID)
                .modelTier(TEST_MODEL_TIER)
                .status(Plan.PlanStatus.EXECUTING)
                .steps(new ArrayList<>())
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();

        String plansJson = objectMapper.writeValueAsString(List.of(plan));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        service.finalizePlan("plan-executing-revision", "# Revised", "Revised title");

        Plan oldPlan = service.getPlan("plan-executing-revision").orElseThrow();
        assertEquals(Plan.PlanStatus.CANCELLED, oldPlan.getStatus());

        String newPlanId = service.getActivePlanId();
        assertNotNull(newPlanId);
        assertNotSame("plan-executing-revision", newPlanId);

        Plan revision = service.getPlan(newPlanId).orElseThrow();
        assertEquals(Plan.PlanStatus.READY, revision.getStatus());
        assertEquals("# Revised", revision.getMarkdown());
        assertEquals("Revised title", revision.getTitle());
    }

    @Test
    void shouldKeepExecutedAtWhenRecoveringInProgressStepWithTimestamp() throws Exception {
        Instant originalExecutedAt = Instant.parse("2026-02-10T09:00:00Z");
        PlanStep inProgress = PlanStep.builder()
                .id("step-existing-executed-at")
                .planId("plan-executing-3")
                .toolName(TOOL_SHELL)
                .order(0)
                .status(PlanStep.StepStatus.IN_PROGRESS)
                .result("already has result")
                .createdAt(FIXED_INSTANT)
                .executedAt(originalExecutedAt)
                .build();

        Plan executing = Plan.builder()
                .id("plan-executing-3")
                .status(Plan.PlanStatus.EXECUTING)
                .steps(new ArrayList<>(List.of(inProgress)))
                .createdAt(FIXED_INSTANT)
                .updatedAt(FIXED_INSTANT)
                .build();

        String plansJson = objectMapper.writeValueAsString(List.of(executing));
        when(storagePort.getText(AUTO_DIR, PLANS_FILE))
                .thenReturn(CompletableFuture.completedFuture(plansJson));

        Plan recovered = service.getPlans().get(0);
        PlanStep recoveredStep = recovered.getSteps().get(0);

        assertEquals(PlanStep.StepStatus.FAILED, recoveredStep.getStatus());
        assertEquals("already has result", recoveredStep.getResult());
        assertEquals(originalExecutedAt, recoveredStep.getExecutedAt());
    }

}
