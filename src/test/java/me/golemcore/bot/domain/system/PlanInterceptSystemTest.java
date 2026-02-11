package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.PlanStep;
import me.golemcore.bot.domain.service.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanInterceptSystemTest {

    private static final String CHAT_ID = "123";
    private static final String PLAN_ID = "plan-001";
    private static final String STEP_ID = "step-1";
    private static final String TOOL_FILESYSTEM = "filesystem";
    private static final String TOOL_SHELL = "shell";
    private static final String TC_1 = "tc1";
    private static final String OP_WRITE = "write";

    private PlanInterceptSystem system;
    private PlanService planService;
    private AgentSession session;

    @BeforeEach
    void setUp() {
        planService = mock(PlanService.class);
        session = mock(AgentSession.class);
        when(session.getChatId()).thenReturn(CHAT_ID);
        doNothing().when(session).addMessage(any(Message.class));

        system = new PlanInterceptSystem(planService);
    }

    @Test
    void shouldReturnCorrectNameAndOrder() {
        assertEquals("PlanInterceptSystem", system.getName());
        assertEquals(35, system.getOrder());
    }

    @Test
    void shouldNotProcessWhenPlanModeInactive() {
        when(planService.isPlanModeActive()).thenReturn(false);

        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TC_1)
                .name(TOOL_FILESYSTEM)
                .arguments(Map.of("op", OP_WRITE))
                .build();

        AgentContext context = createContext(List.of(toolCall));

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenNoToolCalls() {
        when(planService.isPlanModeActive()).thenReturn(true);

        AgentContext contextNoAttr = createContext(null);
        assertFalse(system.shouldProcess(contextNoAttr));

        AgentContext contextEmptyList = createContext(List.of());
        assertFalse(system.shouldProcess(contextEmptyList));
    }

    @Test
    void shouldInterceptToolCallsAndAddPlanSteps() {
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.getActivePlanId()).thenReturn(PLAN_ID);
        when(planService.addStep(anyString(), anyString(), anyMap(), anyString()))
                .thenReturn(PlanStep.builder().id(STEP_ID).build());

        Message.ToolCall tc1 = Message.ToolCall.builder()
                .id(TC_1)
                .name(TOOL_FILESYSTEM)
                .arguments(Map.of("operation", OP_WRITE, "path", "/tmp/file.txt"))
                .build();
        Message.ToolCall tc2 = Message.ToolCall.builder()
                .id("tc2")
                .name(TOOL_SHELL)
                .arguments(Map.of("command", "echo hello"))
                .build();

        AgentContext context = createContext(List.of(tc1, tc2));

        assertTrue(system.shouldProcess(context));
        system.process(context);

        verify(planService).addStep(eq(PLAN_ID), eq(TOOL_FILESYSTEM), eq(tc1.getArguments()), anyString());
        verify(planService).addStep(eq(PLAN_ID), eq(TOOL_SHELL), eq(tc2.getArguments()), anyString());
    }

    @Test
    void shouldClearToolCallsAfterIntercept() {
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.getActivePlanId()).thenReturn(PLAN_ID);
        when(planService.addStep(anyString(), anyString(), anyMap(), anyString()))
                .thenReturn(PlanStep.builder().id(STEP_ID).build());

        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TC_1)
                .name(TOOL_FILESYSTEM)
                .arguments(Map.of("op", "read"))
                .build();

        AgentContext context = createContext(List.of(toolCall));
        system.process(context);

        List<Message.ToolCall> toolCalls = context.getAttribute("llm.toolCalls");
        assertNull(toolCalls);
    }

    @Test
    void shouldSetToolsExecutedAttribute() {
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.getActivePlanId()).thenReturn(PLAN_ID);
        when(planService.addStep(anyString(), anyString(), anyMap(), anyString()))
                .thenReturn(PlanStep.builder().id(STEP_ID).build());

        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TC_1)
                .name(TOOL_FILESYSTEM)
                .arguments(Map.of("op", "list"))
                .build();

        AgentContext context = createContext(List.of(toolCall));
        system.process(context);

        Boolean toolsExecuted = context.getAttribute(ContextAttributes.TOOLS_EXECUTED);
        assertTrue(toolsExecuted);
    }

    @Test
    void shouldAddSyntheticToolResultMessages() {
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.getActivePlanId()).thenReturn(PLAN_ID);
        when(planService.addStep(anyString(), anyString(), anyMap(), anyString()))
                .thenReturn(PlanStep.builder().id(STEP_ID).build());

        Message.ToolCall tc1 = Message.ToolCall.builder()
                .id(TC_1)
                .name(TOOL_FILESYSTEM)
                .arguments(Map.of("op", OP_WRITE))
                .build();
        Message.ToolCall tc2 = Message.ToolCall.builder()
                .id("tc2")
                .name(TOOL_SHELL)
                .arguments(Map.of("command", "ls"))
                .build();

        AgentContext context = createContext(List.of(tc1, tc2));
        system.process(context);

        // Messages: 1 assistant + 2 tool results = 3
        List<Message> messages = context.getMessages();
        assertEquals(3, messages.size());

        // First tool result
        Message toolResult1 = messages.get(1);
        assertEquals("tool", toolResult1.getRole());
        assertEquals(TC_1, toolResult1.getToolCallId());
        assertEquals(TOOL_FILESYSTEM, toolResult1.getToolName());
        assertTrue(toolResult1.getContent().contains("Planned"));

        // Second tool result
        Message toolResult2 = messages.get(2);
        assertEquals("tool", toolResult2.getRole());
        assertEquals("tc2", toolResult2.getToolCallId());
        assertEquals(TOOL_SHELL, toolResult2.getToolName());
        assertTrue(toolResult2.getContent().contains("Planned"));
    }

    @Test
    void shouldPreserveAssistantMessageInHistory() {
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.getActivePlanId()).thenReturn(PLAN_ID);
        when(planService.addStep(anyString(), anyString(), anyMap(), anyString()))
                .thenReturn(PlanStep.builder().id(STEP_ID).build());

        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TC_1)
                .name(TOOL_FILESYSTEM)
                .arguments(Map.of("op", OP_WRITE))
                .build();

        LlmResponse llmResponse = LlmResponse.builder()
                .content("I will write to the file")
                .build();

        AgentContext context = createContext(List.of(toolCall));
        context.setAttribute(ContextAttributes.LLM_RESPONSE, llmResponse);

        system.process(context);

        // First message should be the assistant message
        Message assistantMsg = context.getMessages().get(0);
        assertEquals("assistant", assistantMsg.getRole());
        assertEquals("I will write to the file", assistantMsg.getContent());
        assertEquals(List.of(toolCall), assistantMsg.getToolCalls());

        // Verify the assistant message and tool result were added to the session (2
        // calls total)
        verify(session, times(2)).addMessage(any(Message.class));
    }

    @Test
    void shouldBeDisabledWhenFeatureDisabled() {
        when(planService.isFeatureEnabled()).thenReturn(false);
        assertFalse(system.isEnabled());

        when(planService.isFeatureEnabled()).thenReturn(true);
        assertTrue(system.isEnabled());
    }

    // ===== Helper Methods =====

    private AgentContext createContext(List<Message.ToolCall> toolCalls) {
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();

        if (toolCalls != null) {
            context.setAttribute("llm.toolCalls", toolCalls);
        }

        LlmResponse llmResponse = LlmResponse.builder()
                .content("some text")
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, llmResponse);

        return context;
    }
}
