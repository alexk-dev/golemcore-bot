package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.LlmUsage;
import me.golemcore.bot.domain.model.Message;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmExecutionSystemTest {

    private static final String SESSION_ID = "test-session";
    private static final String CHAT_ID = "chat1";
    private static final String CHANNEL_TYPE = "telegram";
    private static final String TIER_BALANCED = "balanced";
    private static final String TIER_DEEP = "deep";
    private static final String PROVIDER_ID = "langchain4j";
    private static final String MODEL_NAME = "gpt-5.1";
    private static final String MSG_ID_1 = "m1";
    private static final String ROLE_USER = "user";
    private static final String ROLE_TOOL = "tool";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String CONTENT_HELLO = "Hello";
    private static final String CONTENT_OK = "OK";
    private static final String EMERGENCY_TRUNCATED_MARKER = "[EMERGENCY TRUNCATED:";
    private static final String ATTR_LLM_RESPONSE = "llm.response";
    private static final String ATTR_LLM_ERROR = "llm.error";
    private static final String TOOL_CALL_ID = "tc1";
    private static final String OPENAI_MODEL = "openai/gpt-5.1";
    private static final String OPENAI_MODEL_52 = "openai/gpt-5.2";
    private static final String REASONING_MEDIUM = "medium";
    private static final String REASONING_HIGH = "high";
    private static final String REASONING_XHIGH = "xhigh";
    private static final String TOOL_SHELL = "shell";
    private static final String ARG_COMMAND = "command";

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
        messages.add(
                Message.builder().id(MSG_ID_1).role(ROLE_USER).content(CONTENT_HELLO).timestamp(Instant.now()).build());
        messages.add(Message.builder().id("m2").role(ROLE_TOOL).content(hugeContent).toolCallId(TOOL_CALL_ID)
                .toolName(TOOL_SHELL).timestamp(Instant.now()).build());
        messages.add(
                Message.builder().id("m3").role(ROLE_ASSISTANT).content(CONTENT_OK).timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier(TIER_BALANCED)
                .build();

        int truncated = system.truncateLargeMessages(context);

        assertEquals(1, truncated);
        // The huge message should now be smaller
        assertTrue(context.getMessages().get(1).getContent().length() < 200000);
        assertTrue(context.getMessages().get(1).getContent().contains(EMERGENCY_TRUNCATED_MARKER));
        // Other messages unchanged
        assertEquals(CONTENT_HELLO, context.getMessages().get(0).getContent());
        assertEquals(CONTENT_OK, context.getMessages().get(2).getContent());
        // Metadata preserved
        assertEquals(TOOL_CALL_ID, context.getMessages().get(1).getToolCallId());
        assertEquals(TOOL_SHELL, context.getMessages().get(1).getToolName());
    }

    @Test
    void noTruncationWhenMessagesSmall() {
        List<Message> messages = new ArrayList<>();
        messages.add(
                Message.builder().id(MSG_ID_1).role(ROLE_USER).content(CONTENT_HELLO).timestamp(Instant.now()).build());
        messages.add(
                Message.builder().id("m2").role(ROLE_ASSISTANT).content("Hi there").timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier(TIER_BALANCED)
                .build();

        int truncated = system.truncateLargeMessages(context);
        assertEquals(0, truncated);
    }

    @Test
    void truncationAlsoAppliedToSessionMessages() {
        String hugeContent = "x".repeat(200000);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().id(MSG_ID_1).role(ROLE_TOOL).content(hugeContent).toolCallId(TOOL_CALL_ID)
                .timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier(TIER_BALANCED)
                .build();

        system.truncateLargeMessages(context);

        // Session messages also truncated
        assertTrue(session.getMessages().get(0).getContent().contains(EMERGENCY_TRUNCATED_MARKER));
        assertTrue(session.getMessages().get(0).getContent().length() < 200000);
    }

    @Test
    void truncationUsesModelSpecificLimits() {
        // Model with 32K context
        when(modelConfigService.getMaxInputTokens(anyString())).thenReturn(32000);
        // per-message limit = 32000 * 3.5 * 0.25 = 28000 chars
        String content = "x".repeat(50000);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().id(MSG_ID_1).role(ROLE_TOOL).content(content).timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier(TIER_BALANCED)
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
        messages.add(Message.builder().id(MSG_ID_1).role(ROLE_TOOL).content(content).timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier(TIER_BALANCED)
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
        messages.add(Message.builder().id(MSG_ID_1).role(ROLE_USER).content(null).timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier(TIER_BALANCED)
                .build();

        int truncated = system.truncateLargeMessages(context);
        assertEquals(0, truncated);
        assertNull(context.getMessages().get(0).getContent());
    }

    @Test
    void truncationHandlesEmptyMessageList() {
        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .modelTier(TIER_BALANCED)
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
        messages.add(Message.builder().id("msg-42").role(ROLE_TOOL).content(huge).timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier(TIER_BALANCED)
                .build();

        system.truncateLargeMessages(context);
        assertEquals("msg-42", context.getMessages().get(0).getId());
    }

    // ===== Model Selection =====

    @Test
    void selectsDeepModelForDeepTier() {
        properties.getRouter().setDeepModel(OPENAI_MODEL_52);
        properties.getRouter().setDeepModelReasoning(REASONING_XHIGH);

        when(llmPort.getProviderId()).thenReturn(PROVIDER_ID);
        when(llmPort.getCurrentModel()).thenReturn(MODEL_NAME);

        LlmResponse response = LlmResponse.builder().content("Hello!").build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        AgentContext ctx = createContextWithTier(TIER_DEEP);
        system.process(ctx);

        LlmResponse result = ctx.getAttribute(ATTR_LLM_RESPONSE);
        assertNotNull(result);
        assertNull(ctx.getAttribute(ATTR_LLM_ERROR));

        // Verify the request was built with deep model
        verify(llmPort).chat(
                argThat(req -> OPENAI_MODEL_52.equals(req.getModel())
                        && REASONING_XHIGH.equals(req.getReasoningEffort())));
    }

    @Test
    void selectsCodingModelForCodingTier() {
        properties.getRouter().setCodingModel(OPENAI_MODEL_52);
        properties.getRouter().setCodingModelReasoning(REASONING_MEDIUM);

        when(llmPort.getProviderId()).thenReturn(PROVIDER_ID);
        when(llmPort.getCurrentModel()).thenReturn("gpt-5.2");

        LlmResponse response = LlmResponse.builder().content("```python\nprint()```").build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        AgentContext ctx = createContextWithTier("coding");
        system.process(ctx);

        verify(llmPort).chat(
                argThat(req -> OPENAI_MODEL_52.equals(req.getModel())
                        && REASONING_MEDIUM.equals(req.getReasoningEffort())));
    }

    @Test
    void selectsSmartModelForSmartTier() {
        properties.getRouter().setSmartModel(OPENAI_MODEL);
        properties.getRouter().setSmartModelReasoning(REASONING_HIGH);

        when(llmPort.getProviderId()).thenReturn(PROVIDER_ID);
        when(llmPort.getCurrentModel()).thenReturn(MODEL_NAME);

        LlmResponse response = LlmResponse.builder().content("Analysis...").build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        AgentContext ctx = createContextWithTier("smart");
        system.process(ctx);

        verify(llmPort).chat(argThat(req -> REASONING_HIGH.equals(req.getReasoningEffort())));
    }

    @Test
    void usesDefaultModelForNullTier() {
        properties.getRouter().setDefaultModel(OPENAI_MODEL);
        properties.getRouter().setDefaultModelReasoning(REASONING_MEDIUM);

        when(llmPort.getProviderId()).thenReturn(PROVIDER_ID);
        when(llmPort.getCurrentModel()).thenReturn(MODEL_NAME);

        LlmResponse response = LlmResponse.builder().content(CONTENT_OK).build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        AgentContext ctx = createContextWithTier(null);
        system.process(ctx);

        verify(llmPort).chat(
                argThat(req -> OPENAI_MODEL.equals(req.getModel())
                        && REASONING_MEDIUM.equals(req.getReasoningEffort())));
    }

    // ===== Happy path / tool calls =====

    @Test
    void storesToolCallsInContext() {
        when(llmPort.getProviderId()).thenReturn(PROVIDER_ID);
        when(llmPort.getCurrentModel()).thenReturn(MODEL_NAME);

        List<Message.ToolCall> toolCalls = List.of(
                Message.ToolCall.builder().id(TOOL_CALL_ID).name(TOOL_SHELL).arguments(Map.of(ARG_COMMAND, "ls"))
                        .build());
        LlmResponse response = LlmResponse.builder().content(null).toolCalls(toolCalls).build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        AgentContext ctx = createContextWithTier(TIER_BALANCED);
        system.process(ctx);

        LlmResponse result = ctx.getAttribute(ATTR_LLM_RESPONSE);
        assertNotNull(result);
        assertTrue(result.hasToolCalls());
        assertNotNull(ctx.getAttribute("llm.toolCalls"));
    }

    @Test
    void tracksUsageOnSuccess() {
        when(llmPort.getProviderId()).thenReturn(PROVIDER_ID);
        when(llmPort.getCurrentModel()).thenReturn(MODEL_NAME);

        LlmUsage usage = LlmUsage.builder().inputTokens(100).outputTokens(50).totalTokens(150).build();
        LlmResponse response = LlmResponse.builder().content("Hi").usage(usage).build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        AgentContext ctx = createContextWithTier(TIER_BALANCED);
        system.process(ctx);

        verify(usageTracker).recordUsage(eq(PROVIDER_ID), eq(MODEL_NAME), any(LlmUsage.class));
    }

    // ===== Empty response retry =====

    @Test
    void retriesOnceOnEmptyResponse() {
        when(llmPort.getProviderId()).thenReturn(PROVIDER_ID);
        when(llmPort.getCurrentModel()).thenReturn(MODEL_NAME);

        LlmResponse empty = LlmResponse.builder().content("").build();
        LlmResponse good = LlmResponse.builder().content("Hello!").build();
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(empty))
                .thenReturn(CompletableFuture.completedFuture(good));

        AgentContext ctx = createContextWithTier(TIER_BALANCED);
        system.process(ctx);

        LlmResponse result = ctx.getAttribute(ATTR_LLM_RESPONSE);
        assertEquals("Hello!", result.getContent());
        assertNull(ctx.getAttribute(ATTR_LLM_ERROR));
        verify(llmPort, times(2)).chat(any());
    }

    @Test
    void setsErrorAfterMaxEmptyRetries() {
        when(llmPort.getProviderId()).thenReturn(PROVIDER_ID);
        when(llmPort.getCurrentModel()).thenReturn(MODEL_NAME);

        LlmResponse empty = LlmResponse.builder().content(null).build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(empty));

        AgentContext ctx = createContextWithTier(TIER_BALANCED);
        system.process(ctx);

        assertNotNull(ctx.getAttribute(ATTR_LLM_ERROR));
        assertEquals("LLM returned empty response", ctx.getAttribute(ATTR_LLM_ERROR));
    }

    // ===== LLM exception =====

    @Test
    void setsErrorOnLlmException() {
        when(llmPort.getProviderId()).thenReturn(PROVIDER_ID);
        when(llmPort.getCurrentModel()).thenReturn(MODEL_NAME);
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Connection refused")));

        AgentContext ctx = createContextWithTier(TIER_BALANCED);
        system.process(ctx);

        assertNotNull(ctx.getAttribute(ATTR_LLM_ERROR));
        assertNull(ctx.getAttribute(ATTR_LLM_RESPONSE));
    }

    // ===== Context overflow retry =====

    @Test
    void retriesAfterContextOverflowTruncation() {
        when(llmPort.getProviderId()).thenReturn(PROVIDER_ID);
        when(llmPort.getCurrentModel()).thenReturn(MODEL_NAME);

        // First call: overflow error
        CompletableFuture<LlmResponse> overflowFuture = new CompletableFuture<>();
        overflowFuture.completeExceptionally(new RuntimeException("exceeds maximum input length"));
        // Second call: success
        LlmResponse good = LlmResponse.builder().content(CONTENT_OK).build();

        when(llmPort.chat(any()))
                .thenReturn(overflowFuture)
                .thenReturn(CompletableFuture.completedFuture(good));

        // Need large message for truncation to actually happen
        String hugeContent = "x".repeat(200000);
        List<Message> messages = new ArrayList<>();
        messages.add(
                Message.builder().id(MSG_ID_1).role(ROLE_USER).content(CONTENT_HELLO).timestamp(Instant.now()).build());
        messages.add(Message.builder().id("m2").role(ROLE_USER).content(hugeContent).timestamp(Instant.now()).build());

        // Pre-set model in metadata so flattening doesn't interfere
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ContextAttributes.LLM_MODEL, OPENAI_MODEL);

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().id("s1").chatId("ch1").channelType(CHANNEL_TYPE)
                        .messages(new ArrayList<>(messages)).metadata(metadata).build())
                .messages(new ArrayList<>(messages))
                .modelTier(TIER_BALANCED)
                .build();

        system.process(ctx);

        LlmResponse result = ctx.getAttribute(ATTR_LLM_RESPONSE);
        assertEquals(CONTENT_OK, result.getContent());
    }

    private AgentContext createContextWithTier(String tier) {
        List<Message> messages = new ArrayList<>();
        messages.add(
                Message.builder().id(MSG_ID_1).role(ROLE_USER).content(CONTENT_HELLO).timestamp(Instant.now()).build());

        return AgentContext.builder()
                .session(AgentSession.builder().id(SESSION_ID).chatId("ch1").channelType(CHANNEL_TYPE)
                        .messages(new ArrayList<>(messages)).build())
                .messages(new ArrayList<>(messages))
                .modelTier(tier)
                .build();
    }

    // ===== Existing truncation tests below =====

    // ===== Model Tracking & Flattening =====

    @Test
    void shouldTrackModelInSessionMetadata() {
        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .messages(new ArrayList<>())
                .metadata(new HashMap<>())
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(List.of(
                        Message.builder().id(MSG_ID_1).role(ROLE_USER).content(CONTENT_HELLO).timestamp(Instant.now())
                                .build())))
                .modelTier(TIER_BALANCED)
                .build();

        system.flattenOnModelSwitch(context, OPENAI_MODEL);

        assertEquals(OPENAI_MODEL, session.getMetadata().get(ContextAttributes.LLM_MODEL));
    }

    @Test
    void shouldFlattenToolMessagesOnModelSwitch() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ContextAttributes.LLM_MODEL, "old-model");

        List<Message> messages = new ArrayList<>(List.of(
                Message.builder().id(MSG_ID_1).role(ROLE_USER).content(CONTENT_HELLO).timestamp(Instant.now()).build(),
                Message.builder().id("m2").role(ROLE_ASSISTANT)
                        .toolCalls(List.of(Message.ToolCall.builder()
                                .id(TOOL_CALL_ID).name(TOOL_SHELL)
                                .arguments(Map.of(ARG_COMMAND, "ls"))
                                .build()))
                        .timestamp(Instant.now()).build(),
                Message.builder().id("m3").role(ROLE_TOOL)
                        .toolCallId(TOOL_CALL_ID).toolName(TOOL_SHELL)
                        .content("file1.txt")
                        .timestamp(Instant.now()).build()));

        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .messages(new ArrayList<>(messages))
                .metadata(metadata)
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier(TIER_BALANCED)
                .build();

        system.flattenOnModelSwitch(context, "new-model");

        // Tool messages should be flattened
        assertEquals(2, context.getMessages().size());
        assertTrue(context.getMessages().get(1).getContent().contains("[Tool: shell"));
        assertFalse(context.getMessages().stream().anyMatch(Message::isToolMessage));

        // Session messages also flattened
        assertEquals(2, session.getMessages().size());

        // Model updated in metadata
        assertEquals("new-model", metadata.get(ContextAttributes.LLM_MODEL));
    }

    @Test
    void shouldNotFlattenWhenSameModel() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ContextAttributes.LLM_MODEL, OPENAI_MODEL);

        List<Message> messages = new ArrayList<>(List.of(
                Message.builder().id(MSG_ID_1).role(ROLE_ASSISTANT)
                        .toolCalls(List.of(Message.ToolCall.builder()
                                .id(TOOL_CALL_ID).name(TOOL_SHELL)
                                .arguments(Map.of(ARG_COMMAND, "ls"))
                                .build()))
                        .timestamp(Instant.now()).build(),
                Message.builder().id("m2").role(ROLE_TOOL)
                        .toolCallId(TOOL_CALL_ID).toolName(TOOL_SHELL)
                        .content("files")
                        .timestamp(Instant.now()).build()));

        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .messages(new ArrayList<>(messages))
                .metadata(metadata)
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier(TIER_BALANCED)
                .build();

        system.flattenOnModelSwitch(context, OPENAI_MODEL);

        // Tool messages should NOT be flattened — same model
        assertEquals(2, context.getMessages().size());
        assertTrue(context.getMessages().get(0).hasToolCalls());
        assertTrue(context.getMessages().get(1).isToolMessage());
    }

    @Test
    void shouldFlattenLegacySessionWithToolMessages() {
        Map<String, Object> metadata = new HashMap<>();
        // No previous model stored

        List<Message> messages = new ArrayList<>(List.of(
                Message.builder().id(MSG_ID_1).role(ROLE_ASSISTANT)
                        .toolCalls(List.of(Message.ToolCall.builder()
                                .id(TOOL_CALL_ID).name(TOOL_SHELL)
                                .arguments(Map.of(ARG_COMMAND, "ls"))
                                .build()))
                        .timestamp(Instant.now()).build(),
                Message.builder().id("m2").role(ROLE_TOOL)
                        .toolCallId(TOOL_CALL_ID).toolName(TOOL_SHELL)
                        .content("files")
                        .timestamp(Instant.now()).build()));

        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .messages(new ArrayList<>(messages))
                .metadata(metadata)
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier(TIER_BALANCED)
                .build();

        system.flattenOnModelSwitch(context, OPENAI_MODEL);

        // Legacy session with tool messages should be flattened
        assertEquals(1, context.getMessages().size());
        assertTrue(context.getMessages().get(0).getContent().contains("[Tool: shell"));
    }

    @Test
    void shouldNotFlattenFirstRunWithoutToolMessages() {
        Map<String, Object> metadata = new HashMap<>();

        List<Message> messages = new ArrayList<>(List.of(
                Message.builder().id(MSG_ID_1).role(ROLE_USER).content(CONTENT_HELLO).timestamp(Instant.now())
                        .build()));

        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .messages(new ArrayList<>(messages))
                .metadata(metadata)
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier(TIER_BALANCED)
                .build();

        system.flattenOnModelSwitch(context, OPENAI_MODEL);

        // No flattening needed — no tool messages
        assertEquals(1, context.getMessages().size());
        assertEquals(CONTENT_HELLO, context.getMessages().get(0).getContent());
        // Model should still be tracked
        assertEquals(OPENAI_MODEL, metadata.get(ContextAttributes.LLM_MODEL));
    }

    // ===== Existing truncation tests below =====

    @Test
    void truncatesMultipleLargeMessages() {
        String huge = "x".repeat(200000);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().id(MSG_ID_1).role(ROLE_TOOL).content(huge).timestamp(Instant.now()).build());
        messages.add(Message.builder().id("m2").role(ROLE_USER).content("small").timestamp(Instant.now()).build());
        messages.add(Message.builder().id("m3").role(ROLE_TOOL).content(huge).timestamp(Instant.now()).build());

        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .messages(new ArrayList<>(messages))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .modelTier(TIER_BALANCED)
                .build();

        int truncated = system.truncateLargeMessages(context);
        assertEquals(2, truncated);
        assertTrue(context.getMessages().get(0).getContent().contains(EMERGENCY_TRUNCATED_MARKER));
        assertEquals("small", context.getMessages().get(1).getContent());
        assertTrue(context.getMessages().get(2).getContent().contains(EMERGENCY_TRUNCATED_MARKER));
    }
}
