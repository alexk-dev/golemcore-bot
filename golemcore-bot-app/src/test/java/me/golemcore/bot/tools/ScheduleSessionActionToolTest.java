package me.golemcore.bot.tools;

import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.DelayedActionDeliveryMode;
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.scheduling.DelayedActionPolicyService;
import me.golemcore.bot.domain.scheduling.DelayedSessionActionService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScheduleSessionActionToolTest {

    private DelayedSessionActionService delayedActionService;
    private DelayedActionPolicyService delayedActionPolicyService;
    private RuntimeConfigService runtimeConfigService;
    private ScheduleSessionActionTool tool;

    @BeforeEach
    void setUp() {
        delayedActionService = mock(DelayedSessionActionService.class);
        delayedActionPolicyService = mock(DelayedActionPolicyService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(delayedActionPolicyService.canScheduleActions("telegram")).thenReturn(true);
        when(delayedActionPolicyService.canScheduleRunLater("telegram", "transport-1")).thenReturn(true);
        when(delayedActionPolicyService.supportsProactiveMessage("telegram", "transport-1")).thenReturn(true);
        when(delayedActionPolicyService.supportsProactiveDocument("telegram", "transport-1")).thenReturn(true);
        when(delayedActionPolicyService.supportsDelayedExecution("telegram", "transport-1")).thenReturn(true);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(true);
        when(runtimeConfigService.getDelayedActionsDefaultMaxAttempts()).thenReturn(4);
        tool = new ScheduleSessionActionTool(
                delayedActionService,
                delayedActionPolicyService,
                runtimeConfigService,
                Clock.fixed(Instant.parse("2026-03-19T18:30:00Z"), ZoneOffset.UTC));

        AgentSession session = AgentSession.builder()
                .id("telegram:conv-1")
                .channelType("telegram")
                .chatId("conv-1")
                .messages(new ArrayList<>())
                .metadata(Map.of("session.transport.chat.id", "transport-1"))
                .build();
        AgentContextHolder.set(AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build());
    }

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    @Test
    void shouldBeEnabledWithoutSessionContextWhenRuntimeFeatureIsOn() {
        AgentContextHolder.clear();

        assertTrue(tool.isEnabled());
    }

    @Test
    void shouldBeDisabledWhenRuntimeFeatureIsOff() {
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(false);

        assertFalse(tool.isEnabled());
    }

    @Test
    void shouldRespectChannelPolicyInEnabledCheck() {
        when(delayedActionPolicyService.canScheduleActions("telegram")).thenReturn(false);

        assertFalse(tool.isEnabled());
    }

    @Test
    void shouldExposeDefinitionForAllDelayedActionOperations() {
        ToolDefinition definition = tool.getDefinition();

        assertEquals(ScheduleSessionActionTool.TOOL_NAME, definition.getName());
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = definition.getInputSchema();
        assertEquals("object", schema.get("type"));
        assertEquals(java.util.List.of("operation"), schema.get("required"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> operation = (Map<String, Object>) properties.get("operation");
        assertEquals(java.util.List.of("create", "list", "cancel", "run_now"), operation.get("enum"));
        assertTrue(properties.containsKey("action_kind"));
        assertTrue(properties.containsKey("delay_seconds"));
        assertTrue(properties.containsKey("run_at"));
        assertTrue(properties.containsKey("cancel_on_user_activity"));
        assertTrue(properties.containsKey("max_attempts"));
    }

    @Test
    void shouldRejectMissingSessionContext() throws Exception {
        AgentContextHolder.clear();

        ToolResult result = tool.execute(Map.of("operation", "list")).get();

        assertFalse(result.isSuccess());
        assertEquals("No active session context", result.getError());
    }

    @Test
    void shouldRejectMissingOperation() throws Exception {
        ToolResult result = tool.execute(Map.of()).get();

        assertFalse(result.isSuccess());
        assertEquals("Missing required parameter: operation", result.getError());
    }

    @Test
    void shouldRejectUnknownOperation() throws Exception {
        ToolResult result = tool.execute(Map.of("operation", "explode")).get();

        assertFalse(result.isSuccess());
        assertEquals("Unknown operation: explode", result.getError());
    }

    @Test
    void shouldRejectCreateWithoutActionKind() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "delay_seconds", 30)).get();

        assertFalse(result.isSuccess());
        assertEquals("Missing required parameter: action_kind", result.getError());
    }

    @Test
    void shouldRejectUnsupportedActionKind() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "action_kind", "later",
                "delay_seconds", 30)).get();

        assertFalse(result.isSuccess());
        assertEquals("Unsupported action_kind: later", result.getError());
    }

    @Test
    void shouldRejectCreateWithoutResolvableRunAt() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "action_kind", "remind_later",
                "message", "Ping me")).get();

        assertFalse(result.isSuccess());
        assertEquals("Provide either delay_seconds or run_at", result.getError());
    }

    @Test
    void shouldRejectCreateWithInvalidAbsoluteTimestamp() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "action_kind", "remind_later",
                "run_at", "tomorrow",
                "message", "Ping me")).get();

        assertFalse(result.isSuccess());
        assertEquals("Provide either delay_seconds or run_at", result.getError());
    }

    @Test
    void shouldRejectReminderWithoutMessage() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "action_kind", "remind_later",
                "delay_seconds", 60)).get();

        assertFalse(result.isSuccess());
        assertEquals("message is required for remind_later", result.getError());
    }

    @Test
    void shouldRejectRunLaterWithoutInstruction() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "action_kind", "run_later",
                "delay_seconds", 60)).get();

        assertFalse(result.isSuccess());
        assertEquals("instruction is required for run_later", result.getError());
    }

    @Test
    void shouldRejectNotifyJobReadyWithoutMessageOrArtifact() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "action_kind", "notify_job_ready",
                "delay_seconds", 60)).get();

        assertFalse(result.isSuccess());
        assertEquals("message or artifact_path is required for notify_job_ready", result.getError());
    }

    @Test
    void shouldReportSchedulingFailureFromService() throws Exception {
        when(delayedActionService.schedule(any())).thenThrow(new IllegalStateException("storage down"));

        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "action_kind", "remind_later",
                "delay_seconds", 60,
                "message", "Ping me")).get();

        assertFalse(result.isSuccess());
        assertEquals("Failed to schedule delayed action: storage down", result.getError());
    }

    @Test
    void shouldCreateDirectFileNotificationWithAbsoluteRunAt() throws Exception {
        org.mockito.ArgumentCaptor<DelayedSessionAction> captor = forClass(DelayedSessionAction.class);
        when(delayedActionService.schedule(any())).thenAnswer(invocation -> {
            DelayedSessionAction action = invocation.getArgument(0);
            action.setId("delay-file-1");
            return action;
        });

        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "action_kind", "notify_job_ready",
                "run_at", "2026-03-19T18:45:00Z",
                "artifact_path", "artifacts/report.pdf",
                "artifact_name", "monthly-report.pdf",
                "message", "Report ready",
                "cancel_on_user_activity", true,
                "max_attempts", 7)).get();

        assertTrue(result.isSuccess());
        org.mockito.Mockito.verify(delayedActionService).schedule(captor.capture());
        DelayedSessionAction scheduled = captor.getValue();
        assertEquals(DelayedActionDeliveryMode.DIRECT_FILE, scheduled.getDeliveryMode());
        assertEquals(Instant.parse("2026-03-19T18:45:00Z"), scheduled.getRunAt());
        assertEquals("artifacts/report.pdf", scheduled.getPayload().get("artifactPath"));
        assertEquals("monthly-report.pdf", scheduled.getPayload().get("artifactName"));
        assertEquals("Report ready", scheduled.getPayload().get("message"));
        assertEquals(7, scheduled.getMaxAttempts());
        assertTrue(scheduled.isCancelOnUserActivity());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("DIRECT_FILE", data.get("deliveryMode"));
        assertEquals(Boolean.TRUE, data.get("proactiveDeliverySupportedNow"));
        assertEquals("job_result", data.get("userVisibleKind"));
        assertEquals("Report ready", data.get("humanSummary"));
        assertEquals("2026-03-19T18:45:00Z", data.get("nextCheckLabel"));
    }

    @Test
    void shouldCreateRunLaterAction() throws Exception {
        org.mockito.ArgumentCaptor<DelayedSessionAction> captor = forClass(DelayedSessionAction.class);
        when(delayedActionService.schedule(any())).thenAnswer(invocation -> {
            DelayedSessionAction action = invocation.getArgument(0);
            action.setId("delay-1");
            return action;
        });

        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "action_kind", "run_later",
                "delay_seconds", 300,
                "instruction", "Start the report",
                "original_summary", "weekly report export")).get();

        assertTrue(result.isSuccess());
        org.mockito.Mockito.verify(delayedActionService).schedule(captor.capture());
        DelayedSessionAction scheduled = captor.getValue();
        assertEquals(DelayedActionDeliveryMode.INTERNAL_TURN, scheduled.getDeliveryMode());
        assertTrue(scheduled.isCancelOnUserActivity());
        assertEquals("Waiting for result: weekly report export", scheduled.getPayload().get("humanSummary"));
        assertEquals("check_back", scheduled.getPayload().get("userVisibleKind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("delay-1", data.get("actionId"));
        assertEquals(Boolean.TRUE, data.get("cancelOnUserActivity"));
        assertEquals("Waiting for result: weekly report export", data.get("humanSummary"));
        assertEquals("check_back", data.get("userVisibleKind"));
        assertEquals("2026-03-19T18:35:00Z", data.get("nextCheckLabel"));
    }

    @Test
    void shouldDefaultReminderCancellationToFalse() throws Exception {
        org.mockito.ArgumentCaptor<DelayedSessionAction> captor = forClass(DelayedSessionAction.class);
        when(delayedActionService.schedule(any())).thenAnswer(invocation -> {
            DelayedSessionAction action = invocation.getArgument(0);
            action.setId("delay-2");
            return action;
        });

        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "action_kind", "remind_later",
                "delay_seconds", 60,
                "message", "Ping me")).get();

        assertTrue(result.isSuccess());
        org.mockito.Mockito.verify(delayedActionService).schedule(captor.capture());
        assertFalse(captor.getValue().isCancelOnUserActivity());
        assertEquals("Reminder: Ping me", captor.getValue().getPayload().get("humanSummary"));
        assertEquals("reminder", captor.getValue().getPayload().get("userVisibleKind"));
    }

    @Test
    void shouldAllowRunLaterWhenOnlyDelayedExecutionIsAvailable() throws Exception {
        when(delayedActionService.schedule(any())).thenAnswer(invocation -> {
            DelayedSessionAction action = invocation.getArgument(0);
            action.setId("delay-3");
            return action;
        });
        when(delayedActionPolicyService.supportsProactiveMessage("telegram", "transport-1")).thenReturn(false);

        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "action_kind", "run_later",
                "delay_seconds", 300,
                "instruction", "Start the report")).get();

        assertTrue(result.isSuccess());
    }

    @Test
    void shouldRejectReminderWhenProactiveMessageDeliveryIsUnavailable() throws Exception {
        when(delayedActionPolicyService.supportsProactiveMessage("telegram", "transport-1")).thenReturn(false);

        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "action_kind", "remind_later",
                "delay_seconds", 300,
                "message", "Ping me")).get();

        assertFalse(result.isSuccess());
        assertEquals("Proactive message delivery is unavailable for this channel or session", result.getError());
    }

    @Test
    void shouldRejectFileNotificationWhenProactiveFileDeliveryIsUnavailable() throws Exception {
        when(delayedActionPolicyService.supportsProactiveDocument("telegram", "transport-1")).thenReturn(false);

        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "action_kind", "notify_job_ready",
                "delay_seconds", 300,
                "artifact_path", "artifacts/report.txt")).get();

        assertFalse(result.isSuccess());
        assertEquals("Proactive file delivery is unavailable for this channel or session", result.getError());
    }

    @Test
    void shouldRejectRunLaterWhenDelayedExecutionIsUnavailable() throws Exception {
        when(delayedActionPolicyService.canScheduleRunLater("telegram", "transport-1")).thenReturn(false);

        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "action_kind", "run_later",
                "delay_seconds", 300,
                "instruction", "Start the report")).get();

        assertFalse(result.isSuccess());
        assertEquals("run_later is unavailable for this channel or session", result.getError());
    }

    @Test
    void shouldListActionsForCurrentSession() throws Exception {
        when(delayedActionService.listActions("telegram", "conv-1")).thenReturn(java.util.List.of(
                DelayedSessionAction.builder()
                        .id("delay-1")
                        .kind(DelayedActionKind.REMIND_LATER)
                        .deliveryMode(DelayedActionDeliveryMode.DIRECT_MESSAGE)
                        .status(me.golemcore.bot.domain.model.DelayedActionStatus.SCHEDULED)
                        .attempts(2)
                        .cancelOnUserActivity(true)
                        .payload(Map.of(
                                "humanSummary", "Reminder: ping me",
                                "userVisibleKind", "reminder"))
                        .build()));

        ToolResult result = tool.execute(Map.of("operation", "list")).get();

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(1, ((java.util.List<?>) data.get("items")).size());
        @SuppressWarnings("unchecked")
        Map<String, Object> firstItem = (Map<String, Object>) ((java.util.List<?>) data.get("items")).get(0);
        assertEquals(Boolean.TRUE, firstItem.get("cancelOnUserActivity"));
        assertEquals(2, firstItem.get("attempts"));
        assertEquals("Reminder: ping me", firstItem.get("humanSummary"));
        assertEquals("reminder", firstItem.get("userVisibleKind"));
    }

    @Test
    void shouldIncludeRunAtInListedActions() throws Exception {
        when(delayedActionService.listActions("telegram", "conv-1")).thenReturn(java.util.List.of(
                DelayedSessionAction.builder()
                        .id("delay-2")
                        .kind(DelayedActionKind.NOTIFY_JOB_READY)
                        .deliveryMode(DelayedActionDeliveryMode.DIRECT_FILE)
                        .status(me.golemcore.bot.domain.model.DelayedActionStatus.SCHEDULED)
                        .runAt(Instant.parse("2026-03-19T19:00:00Z"))
                        .build()));

        ToolResult result = tool.execute(Map.of("operation", "list")).get();

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> firstItem = (Map<String, Object>) ((java.util.List<?>) data.get("items")).get(0);
        assertEquals("2026-03-19T19:00:00Z", firstItem.get("runAt"));
        assertEquals("DIRECT_FILE", firstItem.get("deliveryMode"));
    }

    @Test
    void shouldRejectWebhookChannel() throws Exception {
        when(delayedActionPolicyService.canScheduleActions("webhook")).thenReturn(false);
        AgentSession session = AgentSession.builder()
                .id("webhook:conv-1")
                .channelType("webhook")
                .chatId("conv-1")
                .messages(new ArrayList<>())
                .metadata(Map.of("session.transport.chat.id", "conv-1"))
                .build();
        AgentContextHolder.set(AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build());

        ToolResult result = tool.execute(Map.of("operation", "list")).get();

        assertFalse(result.isSuccess());
        assertEquals("Delayed actions are unavailable for this channel", result.getError());
    }

    @Test
    void shouldCancelExistingDelayedAction() throws Exception {
        when(delayedActionService.cancelAction("delay-1", "telegram", "conv-1")).thenReturn(true);

        ToolResult result = tool.execute(Map.of(
                "operation", "cancel",
                "action_id", "delay-1")).get();

        assertTrue(result.isSuccess());
        assertEquals("Delayed action cancelled", result.getOutput());
    }

    @Test
    void shouldRejectCancelWithoutActionId() throws Exception {
        ToolResult result = tool.execute(Map.of("operation", "cancel")).get();

        assertFalse(result.isSuccess());
        assertEquals("Missing required parameter: action_id", result.getError());
    }

    @Test
    void shouldRejectCancelWhenActionIsNotCancellable() throws Exception {
        when(delayedActionService.cancelAction("delay-1", "telegram", "conv-1")).thenReturn(false);

        ToolResult result = tool.execute(Map.of(
                "operation", "cancel",
                "action_id", "delay-1")).get();

        assertFalse(result.isSuccess());
        assertEquals("Delayed action not found or not cancellable", result.getError());
    }

    @Test
    void shouldMakeActionDueImmediately() throws Exception {
        when(delayedActionService.runNow("delay-1", "telegram", "conv-1")).thenReturn(true);

        ToolResult result = tool.execute(Map.of(
                "operation", "run_now",
                "action_id", "delay-1")).get();

        assertTrue(result.isSuccess());
        assertEquals("Delayed action made due immediately", result.getOutput());
    }

    @Test
    void shouldRejectRunNowWithoutActionId() throws Exception {
        ToolResult result = tool.execute(Map.of("operation", "run_now")).get();

        assertFalse(result.isSuccess());
        assertEquals("Missing required parameter: action_id", result.getError());
    }

    @Test
    void shouldRejectRunNowWhenActionIsNotRunnable() throws Exception {
        when(delayedActionService.runNow("delay-1", "telegram", "conv-1")).thenReturn(false);

        ToolResult result = tool.execute(Map.of(
                "operation", "run_now",
                "action_id", "delay-1")).get();

        assertFalse(result.isSuccess());
        assertEquals("Delayed action not found or not runnable", result.getError());
    }
}
