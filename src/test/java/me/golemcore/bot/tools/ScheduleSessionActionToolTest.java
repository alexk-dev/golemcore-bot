package me.golemcore.bot.tools;

import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.DelayedActionDeliveryMode;
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.DelayedSessionActionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
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
    void shouldCreateRunLaterAction() throws Exception {
        when(delayedActionService.schedule(any())).thenAnswer(invocation -> {
            DelayedSessionAction action = invocation.getArgument(0);
            action.setId("delay-1");
            return action;
        });

        ToolResult result = tool.execute(Map.of(
                "operation", "create",
                "action_kind", "run_later",
                "delay_seconds", 300,
                "instruction", "Start the report")).get();

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("delay-1", data.get("actionId"));
    }

    @Test
    void shouldRejectRunLaterWhenProactiveDeliveryIsUnavailable() throws Exception {
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
                        .build()));

        ToolResult result = tool.execute(Map.of("operation", "list")).get();

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(1, ((java.util.List<?>) data.get("items")).size());
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
}
