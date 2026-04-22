package me.golemcore.bot.auto;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.AutoRunKind;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionRunCoordinator;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledRunExecutorTest {

    private AutoModeService autoModeService;
    private SessionRunCoordinator sessionRunCoordinator;
    private RuntimeConfigService runtimeConfigService;
    private SessionPort sessionPort;
    private ScheduledRunMessageFactory scheduledRunMessageFactory;
    private ScheduleReportSender reportSender;
    private ScheduledRunExecutor executor;

    private static final String GOAL_ID = "goal-1";
    private static final String TASK_ID = "task-1";
    private static final int TIMEOUT = 5;

    @BeforeEach
    void setUp() {
        autoModeService = mock(AutoModeService.class);
        sessionRunCoordinator = mock(SessionRunCoordinator.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        sessionPort = mock(SessionPort.class);
        scheduledRunMessageFactory = mock(ScheduledRunMessageFactory.class);
        reportSender = mock(ScheduleReportSender.class);

        executor = new ScheduledRunExecutor(
                autoModeService,
                sessionRunCoordinator,
                runtimeConfigService,
                sessionPort,
                scheduledRunMessageFactory,
                reportSender);
    }

    @Test
    void shouldSkipWhenNoMessageBuilt() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.empty());

        executor.executeSchedule(schedule, null, TIMEOUT);

        verify(sessionRunCoordinator, never()).submit(any());
    }

    @Test
    void shouldUseAutoDeliveryContextWhenNullPassed() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = goalRunMessage();
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ScheduleDeliveryContext.auto());
        stubSuccessfulRun(syntheticMessage, "done");

        executor.executeSchedule(schedule, null, TIMEOUT);

        verify(sessionRunCoordinator).submit(syntheticMessage);
    }

    @Test
    void shouldSendReportOnSuccess() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = goalRunMessage();
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));
        when(scheduledRunMessageFactory.buildReportHeader(runMessage)).thenReturn("header");

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubSuccessfulRun(syntheticMessage, "summary text");

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(reportSender).sendReport(eq(schedule), eq("header"), eq("summary text"), eq(ctx));
        verify(autoModeService).recordAutoRunSuccess(eq(GOAL_ID), eq(TASK_ID), isNull());
    }

    @Test
    void shouldSendReportOnFailedRunStatus() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = goalRunMessage();
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));
        when(scheduledRunMessageFactory.buildReportHeader(runMessage)).thenReturn("header");
        when(runtimeConfigService.isAutoReflectionEnabled()).thenReturn(false);

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubFailedRun(syntheticMessage, "something broke", "fingerprint-1");

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(reportSender).sendReport(eq(schedule), eq("header"), eq("something broke"), eq(ctx));
        verify(autoModeService).recordAutoRunFailure(eq(GOAL_ID), eq(TASK_ID),
                eq("something broke"), eq("fingerprint-1"), isNull());
    }

    @Test
    void shouldHandleTimeoutException() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = goalRunMessage();
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));
        when(runtimeConfigService.isAutoReflectionEnabled()).thenReturn(false);

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubNeverCompletingFuture(syntheticMessage);

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(autoModeService).recordAutoRunFailure(
                eq(GOAL_ID), eq(TASK_ID),
                eq("Run timed out after 5 minutes"), eq("timeout"), isNull());
    }

    @Test
    void shouldHandleExecutionException() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = goalRunMessage();
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));
        when(runtimeConfigService.isAutoReflectionEnabled()).thenReturn(false);

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        CompletableFuture<Void> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("boom"));
        when(sessionRunCoordinator.submit(syntheticMessage)).thenReturn(future);

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(autoModeService).recordAutoRunFailure(
                eq(GOAL_ID), eq(TASK_ID),
                any(String.class), eq("execution_exception"), isNull());
    }

    @Test
    void shouldClearSessionContextWhenConfigured() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        schedule.setClearContextBeforeRun(true);

        ScheduledRunMessage runMessage = goalRunMessage();
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));

        AgentSession session = AgentSession.builder().id("session-id").build();
        when(sessionPort.getOrCreate("telegram", "sess")).thenReturn(session);

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubSuccessfulRun(syntheticMessage, "done");

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(sessionPort).getOrCreate("telegram", "sess");
        verify(sessionPort).clearMessages("session-id");
    }

    @Test
    void shouldResetCompletedTaskBeforeRun() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.TASK);
        ScheduledRunMessage runMessage = new ScheduledRunMessage(
                "prompt", AutoRunKind.GOAL_RUN, null, GOAL_ID, TASK_ID,
                "Goal", "Task", false, null, false, true);
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubSuccessfulRun(syntheticMessage, "done");

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(autoModeService).updateTaskStatus(
                eq(GOAL_ID), eq(TASK_ID), eq(AutoTask.TaskStatus.PENDING), isNull());
    }

    @Test
    void shouldNotResetTaskWhenResetFlagIsFalse() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = goalRunMessage();
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubSuccessfulRun(syntheticMessage, "done");

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(autoModeService, never()).updateTaskStatus(any(), any(), any(), any());
    }

    @Test
    void shouldSkipRecordFailureWhenGoalIdIsBlank() {
        ScheduleEntry schedule = buildSchedule(null, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = new ScheduledRunMessage(
                "prompt", AutoRunKind.GOAL_RUN, null, "", null,
                null, null, false, null, false, false);
        ScheduleDeliveryContext ctx = ScheduleDeliveryContext.auto();
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));
        when(runtimeConfigService.isAutoReflectionEnabled()).thenReturn(false);

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubFailedRun(syntheticMessage, "error", "fp");

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(autoModeService, never()).recordAutoRunFailure(any(), any(), any(), any(), any());
    }

    @Test
    void shouldRecordScheduledTaskSuccessWhenScheduledTaskRunCompletes() {
        ScheduleEntry schedule = buildSchedule("scheduled-task-1", ScheduleEntry.ScheduleType.SCHEDULED_TASK);
        ScheduledRunMessage runMessage = new ScheduledRunMessage(
                "prompt", AutoRunKind.SCHEDULED_TASK_RUN, "scheduled-task-1", null, null,
                null, "Nightly cleanup", false, null, false, false);
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubSuccessfulRun(syntheticMessage, "done");

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(autoModeService).recordScheduledTaskSuccess(eq("scheduled-task-1"), isNull());
    }

    @Test
    void shouldRecordScheduledTaskFailureWhenScheduledTaskRunFails() {
        ScheduleEntry schedule = buildSchedule("scheduled-task-1", ScheduleEntry.ScheduleType.SCHEDULED_TASK);
        ScheduledRunMessage runMessage = new ScheduledRunMessage(
                "prompt", AutoRunKind.SCHEDULED_TASK_RUN, "scheduled-task-1", null, null,
                null, "Nightly cleanup", false, null, false, false);
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));
        when(runtimeConfigService.isAutoReflectionEnabled()).thenReturn(false);

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubFailedRun(syntheticMessage, "boom", "fp");

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(autoModeService).recordScheduledTaskFailure(eq("scheduled-task-1"), eq("boom"), eq("fp"), isNull());
    }

    @Test
    void shouldApplyScheduledTaskReflectionResultWhenReflectionIsActive() {
        ScheduleEntry schedule = buildSchedule("scheduled-task-1", ScheduleEntry.ScheduleType.SCHEDULED_TASK);
        ScheduledRunMessage runMessage = new ScheduledRunMessage(
                "prompt", AutoRunKind.SCHEDULED_TASK_RUN, "scheduled-task-1", null, null,
                null, "Nightly cleanup", true, null, false, false);
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubSuccessfulRun(syntheticMessage, "new strategy");

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(autoModeService).applyScheduledTaskReflectionResult("scheduled-task-1", "new strategy");
    }

    @Test
    void shouldApplyReflectionResultWhenReflectionIsActive() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = new ScheduledRunMessage(
                "prompt", AutoRunKind.GOAL_RUN, null, GOAL_ID, TASK_ID,
                "Goal", "Task", true, null, false, false);
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubSuccessfulRun(syntheticMessage, "reflection result");

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(autoModeService).applyReflectionResult(GOAL_ID, TASK_ID, "reflection result");
        verify(reportSender, never()).sendReport(any(), any(), any(), any());
    }

    @Test
    void shouldTriggerReflectionOnFailureWhenEnabled() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = goalRunMessage();
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));
        when(scheduledRunMessageFactory.buildReportHeader(runMessage)).thenReturn("header");
        when(runtimeConfigService.isAutoReflectionEnabled()).thenReturn(true);
        when(autoModeService.shouldTriggerReflection(GOAL_ID, TASK_ID)).thenReturn(true);

        ScheduledRunMessage reflectionMessage = new ScheduledRunMessage(
                "reflection prompt", AutoRunKind.GOAL_RUN, null, GOAL_ID, TASK_ID,
                "Goal", "Task", true, null, false, false);
        when(scheduledRunMessageFactory.buildReflectionMessage(runMessage, schedule.getId()))
                .thenReturn(Optional.of(reflectionMessage));

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubFailedRun(syntheticMessage, "error", "fp");

        Message reflectionSynthetic = stubSyntheticMessage(reflectionMessage, schedule, ctx);
        stubSuccessfulRun(reflectionSynthetic, "new strategy");

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(autoModeService).applyReflectionResult(GOAL_ID, TASK_ID, "new strategy");
    }

    @Test
    void shouldSkipReflectionWhenMessageFactoryReturnsEmpty() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = goalRunMessage();
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));
        when(scheduledRunMessageFactory.buildReportHeader(runMessage)).thenReturn("header");
        when(runtimeConfigService.isAutoReflectionEnabled()).thenReturn(true);
        when(autoModeService.shouldTriggerReflection(GOAL_ID, TASK_ID)).thenReturn(true);
        when(scheduledRunMessageFactory.buildReflectionMessage(runMessage, schedule.getId()))
                .thenReturn(Optional.empty());

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubFailedRun(syntheticMessage, "error", "fp");

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(autoModeService, never()).applyReflectionResult(any(), any(), any());
    }

    @Test
    void shouldNotTriggerReflectionWhenDisabled() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = goalRunMessage();
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));
        when(scheduledRunMessageFactory.buildReportHeader(runMessage)).thenReturn("header");
        when(runtimeConfigService.isAutoReflectionEnabled()).thenReturn(false);

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubFailedRun(syntheticMessage, "error", "fp");

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(scheduledRunMessageFactory, never()).buildReflectionMessage(any(), any());
    }

    @Test
    void shouldHandleReflectionTimeout() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = goalRunMessage();
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));
        when(scheduledRunMessageFactory.buildReportHeader(runMessage)).thenReturn("header");
        when(runtimeConfigService.isAutoReflectionEnabled()).thenReturn(true);
        when(autoModeService.shouldTriggerReflection(GOAL_ID, TASK_ID)).thenReturn(true);

        ScheduledRunMessage reflectionMessage = new ScheduledRunMessage(
                "reflection prompt", AutoRunKind.GOAL_RUN, null, GOAL_ID, TASK_ID,
                "Goal", "Task", true, null, false, false);
        when(scheduledRunMessageFactory.buildReflectionMessage(runMessage, schedule.getId()))
                .thenReturn(Optional.of(reflectionMessage));

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubFailedRun(syntheticMessage, "error", "fp");

        Message reflectionSynthetic = stubSyntheticMessage(reflectionMessage, schedule, ctx);
        stubNeverCompletingFuture(reflectionSynthetic);

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(autoModeService, never()).applyReflectionResult(any(), any(), any());
    }

    @Test
    void shouldHandleReflectionExecutionException() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = goalRunMessage();
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));
        when(scheduledRunMessageFactory.buildReportHeader(runMessage)).thenReturn("header");
        when(runtimeConfigService.isAutoReflectionEnabled()).thenReturn(true);
        when(autoModeService.shouldTriggerReflection(GOAL_ID, TASK_ID)).thenReturn(true);

        ScheduledRunMessage reflectionMessage = new ScheduledRunMessage(
                "reflection prompt", AutoRunKind.GOAL_RUN, null, GOAL_ID, TASK_ID,
                "Goal", "Task", true, null, false, false);
        when(scheduledRunMessageFactory.buildReflectionMessage(runMessage, schedule.getId()))
                .thenReturn(Optional.of(reflectionMessage));

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubFailedRun(syntheticMessage, "error", "fp");

        Message reflectionSynthetic = stubSyntheticMessage(reflectionMessage, schedule, ctx);
        CompletableFuture<Void> reflectionFuture = new CompletableFuture<>();
        reflectionFuture.completeExceptionally(new RuntimeException("boom"));
        when(sessionRunCoordinator.submit(reflectionSynthetic)).thenReturn(reflectionFuture);

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(autoModeService, never()).applyReflectionResult(any(), any(), any());
    }

    @Test
    void shouldHandleNullMetadataGracefully() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = goalRunMessage();
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));

        Message syntheticMessage = Message.builder()
                .role("user").content("prompt")
                .channelType("telegram").chatId("sess").senderId("auto")
                .build();
        when(scheduledRunMessageFactory.buildSyntheticMessage(eq(runMessage), eq(schedule), eq(ctx), anyString()))
                .thenReturn(syntheticMessage);
        when(sessionRunCoordinator.submit(syntheticMessage))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(runtimeConfigService.isAutoReflectionEnabled()).thenReturn(false);

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(autoModeService, never()).recordAutoRunFailure(any(), any(), any(), any(), any());
    }

    @Test
    void shouldHandleReflectionFailedRunStatus() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = goalRunMessage();
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));
        when(scheduledRunMessageFactory.buildReportHeader(runMessage)).thenReturn("header");
        when(runtimeConfigService.isAutoReflectionEnabled()).thenReturn(true);
        when(autoModeService.shouldTriggerReflection(GOAL_ID, TASK_ID)).thenReturn(true);

        ScheduledRunMessage reflectionMessage = new ScheduledRunMessage(
                "reflection prompt", AutoRunKind.GOAL_RUN, null, GOAL_ID, TASK_ID,
                "Goal", "Task", true, null, false, false);
        when(scheduledRunMessageFactory.buildReflectionMessage(runMessage, schedule.getId()))
                .thenReturn(Optional.of(reflectionMessage));

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubFailedRun(syntheticMessage, "error", "fp");

        Message reflectionSynthetic = stubSyntheticMessage(reflectionMessage, schedule, ctx);
        stubFailedRun(reflectionSynthetic, "reflection also failed", "reflection-fp");

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(autoModeService, never()).applyReflectionResult(any(), any(), any());
    }

    @Test
    void shouldNotTriggerReflectionWhenAlreadyInReflection() {
        ScheduleEntry schedule = buildSchedule(GOAL_ID, ScheduleEntry.ScheduleType.GOAL);
        ScheduledRunMessage runMessage = new ScheduledRunMessage(
                "prompt", AutoRunKind.GOAL_RUN, null, GOAL_ID, TASK_ID,
                "Goal", "Task", true, null, false, false);
        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("telegram", "sess", "trans");
        when(scheduledRunMessageFactory.buildForSchedule(schedule)).thenReturn(Optional.of(runMessage));
        when(scheduledRunMessageFactory.buildReportHeader(runMessage)).thenReturn("header");

        Message syntheticMessage = stubSyntheticMessage(runMessage, schedule, ctx);
        stubFailedRun(syntheticMessage, "error", "fp");

        executor.executeSchedule(schedule, ctx, TIMEOUT);

        verify(scheduledRunMessageFactory, never()).buildReflectionMessage(any(), any());
    }

    // --- helpers ---

    private static ScheduleEntry buildSchedule(String targetId, ScheduleEntry.ScheduleType type) {
        return ScheduleEntry.builder()
                .id("sched-1")
                .type(type)
                .targetId(targetId != null ? targetId : "target")
                .cronExpression("0 0 9 * * *")
                .enabled(true)
                .build();
    }

    private static ScheduledRunMessage goalRunMessage() {
        return new ScheduledRunMessage(
                "Work on task", AutoRunKind.GOAL_RUN, null, GOAL_ID, TASK_ID,
                "Goal Title", "Task Title", false, null, false, false);
    }

    private Message stubSyntheticMessage(
            ScheduledRunMessage runMessage,
            ScheduleEntry schedule,
            ScheduleDeliveryContext ctx) {
        Map<String, Object> metadata = new HashMap<>();
        Message syntheticMessage = Message.builder()
                .role("user").content(runMessage.content())
                .channelType(ctx.channelType()).chatId(ctx.sessionChatId()).senderId("auto")
                .metadata(metadata)
                .build();
        when(scheduledRunMessageFactory.buildSyntheticMessage(eq(runMessage), eq(schedule), eq(ctx), anyString()))
                .thenReturn(syntheticMessage);
        return syntheticMessage;
    }

    private void stubSuccessfulRun(Message syntheticMessage, String assistantText) {
        when(sessionRunCoordinator.submit(syntheticMessage))
                .thenAnswer(invocation -> {
                    syntheticMessage.getMetadata().put(ContextAttributes.AUTO_RUN_STATUS, "SUCCESS");
                    syntheticMessage.getMetadata().put(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT, assistantText);
                    return CompletableFuture.completedFuture(null);
                });
    }

    private CompletableFuture<Void> stubNeverCompletingFuture(Message syntheticMessage) {
        CompletableFuture<Void> future = new CompletableFuture<>() {
            @Override
            public Void get(long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException {
                throw new TimeoutException("timed out");
            }
        };
        when(sessionRunCoordinator.submit(syntheticMessage)).thenReturn(future);
        return future;
    }

    private void stubFailedRun(Message syntheticMessage, String failureSummary, String fingerprint) {
        when(sessionRunCoordinator.submit(syntheticMessage))
                .thenAnswer(invocation -> {
                    syntheticMessage.getMetadata().put(ContextAttributes.AUTO_RUN_STATUS, "FAILED");
                    syntheticMessage.getMetadata().put(ContextAttributes.AUTO_RUN_FAILURE_SUMMARY, failureSummary);
                    syntheticMessage.getMetadata().put(ContextAttributes.AUTO_RUN_FAILURE_FINGERPRINT, fingerprint);
                    return CompletableFuture.completedFuture(null);
                });
    }
}
