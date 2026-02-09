package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.*;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmExecutionSystemTest {

    private LlmPort llmPort;
    private UsageTrackingPort usageTracker;
    private BotProperties properties;
    private ModelConfigService modelConfigService;
    private Clock clock;
    private LlmExecutionSystem system;

    @BeforeEach
    void setUp() {
        llmPort = mock(LlmPort.class);
        usageTracker = mock(UsageTrackingPort.class);
        properties = new BotProperties();
        modelConfigService = mock(ModelConfigService.class);
        when(modelConfigService.getMaxInputTokens(anyString())).thenReturn(131072);
        clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);

        system = new LlmExecutionSystem(llmPort, usageTracker, properties, modelConfigService, clock);
    }

    // ===== Context Overflow Detection =====

    @Test
    void detectsExceedsMaximumInputLength() {
        Exception e = new ExecutionException(
                new RuntimeException(
                        "LLM chat failed: {\"error\":{\"message\":\"Requested input length 1162492 exceeds maximum input length 131071\"}}"));
        assertTrue(system.isContextOverflowError(e));
    }

    @Test
    void detectsContextLengthExceeded() {
        Exception e = new RuntimeException("context_length_exceeded: max 128000 tokens");
        assertTrue(system.isContextOverflowError(e));
    }

    @Test
    void detectsMaximumContextLength() {
        Exception e = new RuntimeException("This model's maximum context length is 128000 tokens");
        assertTrue(system.isContextOverflowError(e));
    }

    @Test
    void detectsTooManyTokens() {
        Exception e = new RuntimeException("Too many tokens in request");
        assertTrue(system.isContextOverflowError(e));
    }

    @Test
    void detectsRequestTooLarge() {
        Exception e = new RuntimeException("Request too large for model");
        assertTrue(system.isContextOverflowError(e));
    }

    @Test
    void doesNotFalsePositiveOnOtherErrors() {
        assertFalse(system.isContextOverflowError(new RuntimeException("Connection timeout")));
        assertFalse(system.isContextOverflowError(new RuntimeException("Rate limit exceeded")));
        assertFalse(system.isContextOverflowError(new RuntimeException("Internal server error")));
    }

    @Test
    void handlesNullMessage() {
        assertFalse(system.isContextOverflowError(new RuntimeException((String) null)));
    }

    @Test
    void detectsNestedCause() {
        Exception inner = new RuntimeException("exceeds maximum input length");
        Exception outer = new ExecutionException("LLM call failed", inner);
        assertTrue(system.isContextOverflowError(outer));
    }

    // ===== Emergency Truncation =====

    @Test
    void truncatesLargeMessages() {
        // model maxInputTokens=131072, so per-message limit = 131072 * 3.5 * 0.25 =
        // ~114688 chars
        // Floor is 10000 chars
        String hugeContent = "x".repeat(200000);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().id("m1").role("user").content("Hello").timestamp(Instant.now()).build());
        messages.add(Message.builder().id("m2").role("tool").content(hugeContent).toolCallId("tc1").toolName("shell")
                .timestamp(Instant.now()).build());
        messages.add(Message.builder().id("m3").role("assistant").content("OK").timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id("test-session")
                .chatId("chat1")
                .channelType("telegram")
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier("balanced")
                .build();

        int truncated = system.truncateLargeMessages(context);

        assertEquals(1, truncated);
        // The huge message should now be smaller
        assertTrue(context.getMessages().get(1).getContent().length() < 200000);
        assertTrue(context.getMessages().get(1).getContent().contains("[EMERGENCY TRUNCATED:"));
        // Other messages unchanged
        assertEquals("Hello", context.getMessages().get(0).getContent());
        assertEquals("OK", context.getMessages().get(2).getContent());
        // Metadata preserved
        assertEquals("tc1", context.getMessages().get(1).getToolCallId());
        assertEquals("shell", context.getMessages().get(1).getToolName());
    }

    @Test
    void noTruncationWhenMessagesSmall() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().id("m1").role("user").content("Hello").timestamp(Instant.now()).build());
        messages.add(Message.builder().id("m2").role("assistant").content("Hi there").timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id("test-session")
                .chatId("chat1")
                .channelType("telegram")
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier("balanced")
                .build();

        int truncated = system.truncateLargeMessages(context);
        assertEquals(0, truncated);
    }

    @Test
    void truncationAlsoAppliedToSessionMessages() {
        String hugeContent = "x".repeat(200000);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().id("m1").role("tool").content(hugeContent).toolCallId("tc1")
                .timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id("test-session")
                .chatId("chat1")
                .channelType("telegram")
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier("balanced")
                .build();

        system.truncateLargeMessages(context);

        // Session messages also truncated
        assertTrue(session.getMessages().get(0).getContent().contains("[EMERGENCY TRUNCATED:"));
        assertTrue(session.getMessages().get(0).getContent().length() < 200000);
    }

    @Test
    void truncationUsesModelSpecificLimits() {
        // Model with 32K context
        when(modelConfigService.getMaxInputTokens(anyString())).thenReturn(32000);
        // per-message limit = 32000 * 3.5 * 0.25 = 28000 chars
        String content = "x".repeat(50000);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().id("m1").role("tool").content(content).timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id("test-session")
                .chatId("chat1")
                .channelType("telegram")
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier("balanced")
                .build();

        int truncated = system.truncateLargeMessages(context);
        assertEquals(1, truncated);
        // Should be around 28000 chars max (not the default 114K for 131K models)
        assertTrue(context.getMessages().get(0).getContent().length() < 30000);
    }

    @Test
    void truncationFloorOf10KChars() {
        // Tiny model where 25% would be less than 10K
        when(modelConfigService.getMaxInputTokens(anyString())).thenReturn(1000);
        // 1000 * 3.5 * 0.25 = 875, but floor is 10000
        String content = "x".repeat(20000);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().id("m1").role("tool").content(content).timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id("test-session")
                .chatId("chat1")
                .channelType("telegram")
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier("balanced")
                .build();

        system.truncateLargeMessages(context);
        // Floor of 10000 chars
        assertTrue(context.getMessages().get(0).getContent().length() >= 10000);
    }

    // ===== Edge Cases =====

    @Test
    void orderIsThirty() {
        assertEquals(30, system.getOrder());
    }

    @Test
    void nameIsLlmExecutionSystem() {
        assertEquals("LlmExecutionSystem", system.getName());
    }

    @Test
    void handlesNullExceptionInOverflowCheck() {
        assertFalse(system.isContextOverflowError(null));
    }

    @Test
    void truncationSkipsNullContentMessages() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().id("m1").role("user").content(null).timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id("test-session")
                .chatId("chat1")
                .channelType("telegram")
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier("balanced")
                .build();

        int truncated = system.truncateLargeMessages(context);
        assertEquals(0, truncated);
        assertNull(context.getMessages().get(0).getContent());
    }

    @Test
    void truncationHandlesEmptyMessageList() {
        AgentSession session = AgentSession.builder()
                .id("test-session")
                .chatId("chat1")
                .channelType("telegram")
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .modelTier("balanced")
                .build();

        int truncated = system.truncateLargeMessages(context);
        assertEquals(0, truncated);
    }

    @Test
    void detectsRequestTooLargePattern() {
        Exception e = new RuntimeException("request too large for model gpt-5.1");
        assertTrue(system.isContextOverflowError(e));
    }

    @Test
    void overflowCheckSearchesCauseChain() {
        Exception root = new RuntimeException("exceeds maximum input length");
        Exception middle = new RuntimeException("processing failed", root);
        Exception outer = new ExecutionException("LLM error", middle);
        assertTrue(system.isContextOverflowError(outer));
    }

    @Test
    void truncationPreservesMessageId() {
        String huge = "x".repeat(200000);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().id("msg-42").role("tool").content(huge).timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id("test-session")
                .chatId("chat1")
                .channelType("telegram")
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier("balanced")
                .build();

        system.truncateLargeMessages(context);
        assertEquals("msg-42", context.getMessages().get(0).getId());
    }

    // ===== Model Selection =====

    @Test
    void selectsFastModelForFastTier() {
        properties.getRouter().setFastModel("openai/gpt-5.1");
        properties.getRouter().setFastModelReasoning("low");

        when(llmPort.getProviderId()).thenReturn("langchain4j");
        when(llmPort.getCurrentModel()).thenReturn("gpt-5.1");

        LlmResponse response = LlmResponse.builder().content("Hello!").build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        AgentContext ctx = createContextWithTier("fast");
        system.process(ctx);

        LlmResponse result = ctx.getAttribute("llm.response");
        assertNotNull(result);
        assertNull(ctx.getAttribute("llm.error"));

        // Verify the request was built with fast model
        verify(llmPort).chat(
                argThat(req -> "openai/gpt-5.1".equals(req.getModel()) && "low".equals(req.getReasoningEffort())));
    }

    @Test
    void selectsCodingModelForCodingTier() {
        properties.getRouter().setCodingModel("openai/gpt-5.2");
        properties.getRouter().setCodingModelReasoning("medium");

        when(llmPort.getProviderId()).thenReturn("langchain4j");
        when(llmPort.getCurrentModel()).thenReturn("gpt-5.2");

        LlmResponse response = LlmResponse.builder().content("```python\nprint()```").build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        AgentContext ctx = createContextWithTier("coding");
        system.process(ctx);

        verify(llmPort).chat(
                argThat(req -> "openai/gpt-5.2".equals(req.getModel()) && "medium".equals(req.getReasoningEffort())));
    }

    @Test
    void selectsSmartModelForSmartTier() {
        properties.getRouter().setSmartModel("openai/gpt-5.1");
        properties.getRouter().setSmartModelReasoning("high");

        when(llmPort.getProviderId()).thenReturn("langchain4j");
        when(llmPort.getCurrentModel()).thenReturn("gpt-5.1");

        LlmResponse response = LlmResponse.builder().content("Analysis...").build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        AgentContext ctx = createContextWithTier("smart");
        system.process(ctx);

        verify(llmPort).chat(argThat(req -> "high".equals(req.getReasoningEffort())));
    }

    @Test
    void usesDefaultModelForNullTier() {
        properties.getRouter().setDefaultModel("openai/gpt-5.1");
        properties.getRouter().setDefaultModelReasoning("medium");

        when(llmPort.getProviderId()).thenReturn("langchain4j");
        when(llmPort.getCurrentModel()).thenReturn("gpt-5.1");

        LlmResponse response = LlmResponse.builder().content("OK").build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        AgentContext ctx = createContextWithTier(null);
        system.process(ctx);

        verify(llmPort).chat(
                argThat(req -> "openai/gpt-5.1".equals(req.getModel()) && "medium".equals(req.getReasoningEffort())));
    }

    // ===== Happy path / tool calls =====

    @Test
    void storesToolCallsInContext() {
        when(llmPort.getProviderId()).thenReturn("langchain4j");
        when(llmPort.getCurrentModel()).thenReturn("gpt-5.1");

        List<Message.ToolCall> toolCalls = List.of(
                Message.ToolCall.builder().id("tc1").name("shell").arguments(java.util.Map.of("command", "ls"))
                        .build());
        LlmResponse response = LlmResponse.builder().content(null).toolCalls(toolCalls).build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        AgentContext ctx = createContextWithTier("fast");
        system.process(ctx);

        LlmResponse result = ctx.getAttribute("llm.response");
        assertNotNull(result);
        assertTrue(result.hasToolCalls());
        assertNotNull(ctx.getAttribute("llm.toolCalls"));
    }

    @Test
    void tracksUsageOnSuccess() {
        when(llmPort.getProviderId()).thenReturn("langchain4j");
        when(llmPort.getCurrentModel()).thenReturn("gpt-5.1");

        LlmUsage usage = LlmUsage.builder().inputTokens(100).outputTokens(50).totalTokens(150).build();
        LlmResponse response = LlmResponse.builder().content("Hi").usage(usage).build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        AgentContext ctx = createContextWithTier("fast");
        system.process(ctx);

        verify(usageTracker).recordUsage(eq("langchain4j"), eq("gpt-5.1"), any(LlmUsage.class));
    }

    // ===== Empty response retry =====

    @Test
    void retriesOnceOnEmptyResponse() {
        when(llmPort.getProviderId()).thenReturn("langchain4j");
        when(llmPort.getCurrentModel()).thenReturn("gpt-5.1");

        LlmResponse empty = LlmResponse.builder().content("").build();
        LlmResponse good = LlmResponse.builder().content("Hello!").build();
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(empty))
                .thenReturn(CompletableFuture.completedFuture(good));

        AgentContext ctx = createContextWithTier("fast");
        system.process(ctx);

        LlmResponse result = ctx.getAttribute("llm.response");
        assertEquals("Hello!", result.getContent());
        assertNull(ctx.getAttribute("llm.error"));
        verify(llmPort, times(2)).chat(any());
    }

    @Test
    void setsErrorAfterMaxEmptyRetries() {
        when(llmPort.getProviderId()).thenReturn("langchain4j");
        when(llmPort.getCurrentModel()).thenReturn("gpt-5.1");

        LlmResponse empty = LlmResponse.builder().content(null).build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(empty));

        AgentContext ctx = createContextWithTier("fast");
        system.process(ctx);

        assertNotNull(ctx.getAttribute("llm.error"));
        assertEquals("LLM returned empty response", ctx.getAttribute("llm.error"));
    }

    // ===== LLM exception =====

    @Test
    void setsErrorOnLlmException() {
        when(llmPort.getProviderId()).thenReturn("langchain4j");
        when(llmPort.getCurrentModel()).thenReturn("gpt-5.1");
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Connection refused")));

        AgentContext ctx = createContextWithTier("fast");
        system.process(ctx);

        assertNotNull(ctx.getAttribute("llm.error"));
        assertNull(ctx.getAttribute("llm.response"));
    }

    // ===== Context overflow retry =====

    @Test
    void retriesAfterContextOverflowTruncation() {
        when(llmPort.getProviderId()).thenReturn("langchain4j");
        when(llmPort.getCurrentModel()).thenReturn("gpt-5.1");

        // First call: overflow error
        CompletableFuture<LlmResponse> overflowFuture = new CompletableFuture<>();
        overflowFuture.completeExceptionally(new RuntimeException("exceeds maximum input length"));
        // Second call: success
        LlmResponse good = LlmResponse.builder().content("OK").build();

        when(llmPort.chat(any()))
                .thenReturn(overflowFuture)
                .thenReturn(CompletableFuture.completedFuture(good));

        // Need large message for truncation to actually happen
        String hugeContent = "x".repeat(200000);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().id("m1").role("user").content("Hello").timestamp(Instant.now()).build());
        messages.add(Message.builder().id("m2").role("tool").content(hugeContent).timestamp(Instant.now()).build());

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().id("s1").chatId("ch1").channelType("telegram")
                        .messages(new ArrayList<>(messages)).build())
                .messages(new ArrayList<>(messages))
                .modelTier("fast")
                .build();

        system.process(ctx);

        LlmResponse result = ctx.getAttribute("llm.response");
        assertEquals("OK", result.getContent());
    }

    private AgentContext createContextWithTier(String tier) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().id("m1").role("user").content("Hello").timestamp(Instant.now()).build());

        return AgentContext.builder()
                .session(AgentSession.builder().id("test-session").chatId("ch1").channelType("telegram")
                        .messages(new ArrayList<>(messages)).build())
                .messages(new ArrayList<>(messages))
                .modelTier(tier)
                .build();
    }

    // ===== Existing truncation tests below =====

    @Test
    void truncatesMultipleLargeMessages() {
        String huge = "x".repeat(200000);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().id("m1").role("tool").content(huge).timestamp(Instant.now()).build());
        messages.add(Message.builder().id("m2").role("user").content("small").timestamp(Instant.now()).build());
        messages.add(Message.builder().id("m3").role("tool").content(huge).timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id("test-session")
                .chatId("chat1")
                .channelType("telegram")
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier("balanced")
                .build();

        int truncated = system.truncateLargeMessages(context);
        assertEquals(2, truncated);
        assertTrue(context.getMessages().get(0).getContent().contains("[EMERGENCY TRUNCATED:"));
        assertEquals("small", context.getMessages().get(1).getContent());
        assertTrue(context.getMessages().get(2).getContent().contains("[EMERGENCY TRUNCATED:"));
    }
}
