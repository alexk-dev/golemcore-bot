package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PlanExecutionCompletedEvent;
import me.golemcore.bot.domain.model.PlanStep;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.plugin.context.PluginToolCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanExecutionServiceTest {

    private static final String PLAN_ID = "plan-001";
    private static final String CHAT_ID = "chat-123";
    private static final String TOOL_FILESYSTEM = "filesystem";
    private static final String TOOL_SHELL = "shell";
    private static final String RESULT_DONE = "Done";
    private static final String NONEXISTENT = "nonexistent";
    private static final String BACKTICK_FILESYSTEM = "`filesystem`";
    private static final Instant NOW = Instant.parse("2026-02-11T10:00:00Z");

    private PlanService planService;
    private ToolComponent filesystemTool;
    private ToolComponent shellTool;
    private ApplicationEventPublisher eventPublisher;
    private BotProperties properties;
    private PlanExecutionService service;

    @BeforeEach
    void setUp() {
        planService = mock(PlanService.class);

        filesystemTool = mock(ToolComponent.class);
        when(filesystemTool.getToolName()).thenReturn(TOOL_FILESYSTEM);
        when(filesystemTool.isEnabled()).thenReturn(true);

        shellTool = mock(ToolComponent.class);
        when(shellTool.getToolName()).thenReturn(TOOL_SHELL);
        when(shellTool.isEnabled()).thenReturn(true);

        eventPublisher = mock(ApplicationEventPublisher.class);

        properties = new BotProperties();
        properties.getPlan().setStopOnFailure(true);

        service = new PlanExecutionService(
                planService,
                PluginToolCatalog.forTesting(List.of(filesystemTool, shellTool)),
                eventPublisher,
                properties);
    }

    @Test
    void shouldExecuteAllStepsSuccessfully() throws Exception {
        Plan plan = buildPlan(Plan.PlanStatus.APPROVED, List.of(
                buildStep("s1", TOOL_FILESYSTEM, 0, PlanStep.StepStatus.PENDING),
                buildStep("s2", TOOL_SHELL, 1, PlanStep.StepStatus.PENDING)));
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        when(filesystemTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.success("File created")));
        when(shellTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.success("Command OK")));

        service.executePlan(PLAN_ID).get(5, TimeUnit.SECONDS);

        verify(planService).markPlanExecuting(PLAN_ID);
        verify(planService).markStepInProgress(PLAN_ID, "s1");
        verify(planService).markStepCompleted(PLAN_ID, "s1", "File created");
        verify(planService).markStepInProgress(PLAN_ID, "s2");
        verify(planService).markStepCompleted(PLAN_ID, "s2", "Command OK");
        verify(planService).completePlan(PLAN_ID);
    }

    @Test
    void shouldStopOnFailureWhenConfigured() throws Exception {
        Plan plan = buildPlan(Plan.PlanStatus.APPROVED, List.of(
                buildStep("s1", TOOL_FILESYSTEM, 0, PlanStep.StepStatus.PENDING),
                buildStep("s2", TOOL_SHELL, 1, PlanStep.StepStatus.PENDING)));
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        when(filesystemTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.failure("Permission denied")));

        service.executePlan(PLAN_ID).get(5, TimeUnit.SECONDS);

        verify(planService).markStepFailed(PLAN_ID, "s1", "Permission denied");
        verify(planService, never()).markStepInProgress(PLAN_ID, "s2");
        verify(planService).markPlanPartiallyCompleted(PLAN_ID);
    }

    @Test
    void shouldContinueOnFailureWhenNotStopOnFailure() throws Exception {
        properties.getPlan().setStopOnFailure(false);

        Plan plan = buildPlan(Plan.PlanStatus.APPROVED, List.of(
                buildStep("s1", TOOL_FILESYSTEM, 0, PlanStep.StepStatus.PENDING),
                buildStep("s2", TOOL_SHELL, 1, PlanStep.StepStatus.PENDING)));
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        when(filesystemTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.failure("Error")));
        when(shellTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.success("OK")));

        service.executePlan(PLAN_ID).get(5, TimeUnit.SECONDS);

        verify(planService).markStepFailed(PLAN_ID, "s1", "Error");
        verify(planService).markStepInProgress(PLAN_ID, "s2");
        verify(planService).markStepCompleted(PLAN_ID, "s2", "OK");
        verify(planService).completePlan(PLAN_ID);
    }

    @Test
    void shouldSkipCompletedStepsOnResume() throws Exception {
        Plan plan = buildPlan(Plan.PlanStatus.APPROVED, List.of(
                buildStep("s1", TOOL_FILESYSTEM, 0, PlanStep.StepStatus.COMPLETED),
                buildStep("s2", TOOL_SHELL, 1, PlanStep.StepStatus.PENDING)));
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        when(shellTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.success(RESULT_DONE)));

        service.executePlan(PLAN_ID).get(5, TimeUnit.SECONDS);

        verify(planService, never()).markStepInProgress(PLAN_ID, "s1");
        verify(planService).markStepInProgress(PLAN_ID, "s2");
        verify(planService).markStepCompleted(PLAN_ID, "s2", RESULT_DONE);
        verify(planService).completePlan(PLAN_ID);
    }

    @Test
    void shouldHandleToolNotFound() throws Exception {
        PlanStep step = buildStep("s1", "unknown_tool", 0, PlanStep.StepStatus.PENDING);
        Plan plan = buildPlan(Plan.PlanStatus.APPROVED, List.of(step));
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        service.executePlan(PLAN_ID).get(5, TimeUnit.SECONDS);

        verify(planService).markStepFailed(eq(PLAN_ID), eq("s1"), contains("Tool not found"));
        verify(planService).markPlanPartiallyCompleted(PLAN_ID);
    }

    @Test
    void shouldHandleDisabledTool() throws Exception {
        when(filesystemTool.isEnabled()).thenReturn(false);

        PlanStep step = buildStep("s1", TOOL_FILESYSTEM, 0, PlanStep.StepStatus.PENDING);
        Plan plan = buildPlan(Plan.PlanStatus.APPROVED, List.of(step));
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        service.executePlan(PLAN_ID).get(5, TimeUnit.SECONDS);

        verify(planService).markStepFailed(eq(PLAN_ID), eq("s1"), contains("Tool is disabled"));
        verify(planService).markPlanPartiallyCompleted(PLAN_ID);
    }

    @Test
    void shouldHandleToolException() throws Exception {
        when(filesystemTool.execute(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Connection timeout")));

        PlanStep step = buildStep("s1", TOOL_FILESYSTEM, 0, PlanStep.StepStatus.PENDING);
        Plan plan = buildPlan(Plan.PlanStatus.APPROVED, List.of(step));
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        service.executePlan(PLAN_ID).get(5, TimeUnit.SECONDS);

        verify(planService).markStepFailed(eq(PLAN_ID), eq("s1"), contains("Execution failed"));
        verify(planService).markPlanPartiallyCompleted(PLAN_ID);
    }

    @Test
    void shouldNotExecuteNonApprovedPlan() throws Exception {
        Plan plan = buildPlan(Plan.PlanStatus.COLLECTING, List.of());
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        service.executePlan(PLAN_ID).get(5, TimeUnit.SECONDS);

        verify(planService, never()).markPlanExecuting(anyString());
        verify(planService, never()).completePlan(anyString());
    }

    @Test
    void shouldPublishExecutionSummaryEventAfterCompletion() throws Exception {
        Plan plan = buildPlan(Plan.PlanStatus.APPROVED, List.of(
                buildStep("s1", TOOL_FILESYSTEM, 0, PlanStep.StepStatus.PENDING)));
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        when(filesystemTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.success("OK")));

        service.executePlan(PLAN_ID).get(5, TimeUnit.SECONDS);

        ArgumentCaptor<PlanExecutionCompletedEvent> captor = ArgumentCaptor
                .forClass(PlanExecutionCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        PlanExecutionCompletedEvent event = captor.getValue();
        assertEquals(PLAN_ID, event.planId());
        assertEquals(CHAT_ID, event.chatId());
        assertTrue(event.summary().contains("Plan Execution"));
    }

    @Test
    void shouldNotPublishEventWhenNoChatId() throws Exception {
        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .chatId(null)
                .status(Plan.PlanStatus.APPROVED)
                .steps(new ArrayList<>(List.of(
                        buildStep("s1", TOOL_FILESYSTEM, 0, PlanStep.StepStatus.PENDING))))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        when(filesystemTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.success("OK")));

        service.executePlan(PLAN_ID).get(5, TimeUnit.SECONDS);

        verify(eventPublisher, never()).publishEvent(any(PlanExecutionCompletedEvent.class));
    }

    @Test
    void shouldResumePlanFromPartiallyCompleted() {
        Plan plan = buildPlan(Plan.PlanStatus.PARTIALLY_COMPLETED, List.of(
                buildStep("s1", TOOL_FILESYSTEM, 0, PlanStep.StepStatus.COMPLETED),
                buildStep("s2", TOOL_SHELL, 1, PlanStep.StepStatus.PENDING)));
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        service.resumePlan(PLAN_ID);

        verify(planService).approvePlan(PLAN_ID);
    }

    @Test
    void shouldThrowWhenResumingNonPartialPlan() {
        Plan plan = buildPlan(Plan.PlanStatus.COMPLETED, List.of());
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        assertThrows(IllegalStateException.class, () -> service.resumePlan(PLAN_ID));
    }

    @Test
    void shouldThrowWhenResumingNonExistentPlan() {
        when(planService.getPlan(NONEXISTENT)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.resumePlan(NONEXISTENT));
    }

    @Test
    void shouldBuildCompletedSummary() {
        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .chatId(CHAT_ID)
                .status(Plan.PlanStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(
                        PlanStep.builder().id("s1").toolName(TOOL_FILESYSTEM).description("write file")
                                .order(0).status(PlanStep.StepStatus.COMPLETED).result(RESULT_DONE).createdAt(NOW)
                                .build(),
                        PlanStep.builder().id("s2").toolName(TOOL_SHELL).description("run tests")
                                .order(1).status(PlanStep.StepStatus.COMPLETED).result("Passed").createdAt(NOW)
                                .build())))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();

        String summary = service.buildExecutionSummary(plan);

        assertTrue(summary.contains("Plan Execution Complete"));
        assertTrue(summary.contains(BACKTICK_FILESYSTEM));
        assertTrue(summary.contains("`shell`"));
        assertTrue(summary.contains("2/2 completed"));
    }

    @Test
    void shouldBuildPartialSummary() {
        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .chatId(CHAT_ID)
                .status(Plan.PlanStatus.PARTIALLY_COMPLETED)
                .steps(new ArrayList<>(List.of(
                        PlanStep.builder().id("s1").toolName(TOOL_FILESYSTEM).description("write file")
                                .order(0).status(PlanStep.StepStatus.COMPLETED).result(RESULT_DONE).createdAt(NOW)
                                .build(),
                        PlanStep.builder().id("s2").toolName(TOOL_SHELL).description("run deploy")
                                .order(1).status(PlanStep.StepStatus.FAILED).result("Timeout").createdAt(NOW).build(),
                        PlanStep.builder().id("s3").toolName(TOOL_SHELL).description("notify")
                                .order(2).status(PlanStep.StepStatus.PENDING).createdAt(NOW).build())))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();

        String summary = service.buildExecutionSummary(plan);

        assertTrue(summary.contains("Plan Execution Stopped"));
        assertTrue(summary.contains("1/3 completed"));
        assertTrue(summary.contains("1 failed"));
        assertTrue(summary.contains("/plan resume"));
    }

    @Test
    void shouldTruncateLongToolResults() throws Exception {
        String longOutput = "A".repeat(300);
        Plan plan = buildPlan(Plan.PlanStatus.APPROVED, List.of(
                buildStep("s1", TOOL_FILESYSTEM, 0, PlanStep.StepStatus.PENDING)));
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        when(filesystemTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.success(longOutput)));

        service.executePlan(PLAN_ID).get(5, TimeUnit.SECONDS);

        verify(planService).markStepCompleted(eq(PLAN_ID), eq("s1"),
                eq(longOutput.substring(0, 200) + "..."));
    }

    @Test
    void shouldSkipAlreadySkippedSteps() throws Exception {
        Plan plan = buildPlan(Plan.PlanStatus.APPROVED, List.of(
                buildStep("s1", TOOL_FILESYSTEM, 0, PlanStep.StepStatus.SKIPPED),
                buildStep("s2", TOOL_SHELL, 1, PlanStep.StepStatus.PENDING)));
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        when(shellTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.success(RESULT_DONE)));

        service.executePlan(PLAN_ID).get(5, TimeUnit.SECONDS);

        verify(planService, never()).markStepInProgress(PLAN_ID, "s1");
        verify(planService).markStepInProgress(PLAN_ID, "s2");
        verify(planService).completePlan(PLAN_ID);
    }

    @Test
    void shouldBuildSummaryWithAllStepStatuses() {
        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .chatId(CHAT_ID)
                .status(Plan.PlanStatus.PARTIALLY_COMPLETED)
                .steps(new ArrayList<>(List.of(
                        PlanStep.builder().id("s1").toolName(TOOL_FILESYSTEM).description("completed step")
                                .order(0).status(PlanStep.StepStatus.COMPLETED).result(RESULT_DONE).createdAt(NOW)
                                .build(),
                        PlanStep.builder().id("s2").toolName(TOOL_SHELL).description("failed step")
                                .order(1).status(PlanStep.StepStatus.FAILED).result("Error msg").createdAt(NOW).build(),
                        PlanStep.builder().id("s3").toolName(TOOL_SHELL).description("pending step")
                                .order(2).status(PlanStep.StepStatus.PENDING).createdAt(NOW).build(),
                        PlanStep.builder().id("s4").toolName(TOOL_SHELL).description("in progress step")
                                .order(3).status(PlanStep.StepStatus.IN_PROGRESS).createdAt(NOW).build(),
                        PlanStep.builder().id("s5").toolName(TOOL_SHELL).description("skipped step")
                                .order(4).status(PlanStep.StepStatus.SKIPPED).createdAt(NOW).build())))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();

        String summary = service.buildExecutionSummary(plan);

        assertTrue(summary.contains("\u2705")); // COMPLETED icon
        assertTrue(summary.contains("\u274C")); // FAILED icon
        assertTrue(summary.contains("\u23F3")); // PENDING icon
        assertTrue(summary.contains("\u25B6\uFE0F")); // IN_PROGRESS icon
        assertTrue(summary.contains("\u23ED\uFE0F")); // SKIPPED icon
    }

    @Test
    void shouldHandleNullErrorInFailedToolResult() throws Exception {
        when(filesystemTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.failure(null)));

        PlanStep step = buildStep("s1", TOOL_FILESYSTEM, 0, PlanStep.StepStatus.PENDING);
        Plan plan = buildPlan(Plan.PlanStatus.APPROVED, List.of(step));
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        service.executePlan(PLAN_ID).get(5, TimeUnit.SECONDS);

        verify(planService).markStepFailed(PLAN_ID, "s1", "Unknown error");
    }

    @Test
    void shouldBuildSummaryWithNullStepDescription() {
        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .chatId(CHAT_ID)
                .status(Plan.PlanStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(
                        PlanStep.builder().id("s1").toolName(TOOL_FILESYSTEM).description(null)
                                .order(0).status(PlanStep.StepStatus.COMPLETED).result(RESULT_DONE).createdAt(NOW)
                                .build())))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();

        String summary = service.buildExecutionSummary(plan);

        assertTrue(summary.contains(BACKTICK_FILESYSTEM));
        assertFalse(summary.contains(" — null"));
    }

    @Test
    void shouldBuildSummaryWithNullStepResult() {
        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .chatId(CHAT_ID)
                .status(Plan.PlanStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(
                        PlanStep.builder().id("s1").toolName(TOOL_FILESYSTEM).description("write")
                                .order(0).status(PlanStep.StepStatus.COMPLETED).result(null).createdAt(NOW)
                                .build())))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();

        String summary = service.buildExecutionSummary(plan);

        assertTrue(summary.contains(BACKTICK_FILESYSTEM));
        assertTrue(summary.contains("1/1 completed"));
    }

    @Test
    void shouldBuildSummaryWithBlankStepResult() {
        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .chatId(CHAT_ID)
                .status(Plan.PlanStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(
                        PlanStep.builder().id("s1").toolName(TOOL_FILESYSTEM).description("write")
                                .order(0).status(PlanStep.StepStatus.COMPLETED).result("   ").createdAt(NOW)
                                .build())))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();

        String summary = service.buildExecutionSummary(plan);

        assertTrue(summary.contains(BACKTICK_FILESYSTEM));
        assertTrue(summary.contains("1/1 completed"));
    }

    @Test
    void shouldCompleteWithHasFailureWhenStopOnFailureDisabled() throws Exception {
        properties.getPlan().setStopOnFailure(false);

        Plan plan = buildPlan(Plan.PlanStatus.APPROVED, List.of(
                buildStep("s1", TOOL_FILESYSTEM, 0, PlanStep.StepStatus.PENDING)));
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        when(filesystemTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.failure("Error")));

        service.executePlan(PLAN_ID).get(5, TimeUnit.SECONDS);

        // hasFailure=true but stopOnFailure=false → completePlan (not
        // partiallyCompleted)
        verify(planService).completePlan(PLAN_ID);
        verify(planService, never()).markPlanPartiallyCompleted(PLAN_ID);
    }

    @Test
    void shouldHandlePlanNotFound() {
        when(planService.getPlan(NONEXISTENT)).thenReturn(Optional.empty());

        assertThrows(Exception.class,
                () -> service.executePlan(NONEXISTENT).get(5, TimeUnit.SECONDS));
    }

    // ===== Helper Methods =====

    private Plan buildPlan(Plan.PlanStatus status, List<PlanStep> steps) {
        return Plan.builder()
                .id(PLAN_ID)
                .chatId(CHAT_ID)
                .status(status)
                .steps(new ArrayList<>(steps))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private PlanStep buildStep(String id, String toolName, int order, PlanStep.StepStatus status) {
        return PlanStep.builder()
                .id(id)
                .planId(PLAN_ID)
                .toolName(toolName)
                .description(toolName + " operation")
                .toolArguments(Map.of("key", "value"))
                .order(order)
                .status(status)
                .createdAt(NOW)
                .build();
    }
}
