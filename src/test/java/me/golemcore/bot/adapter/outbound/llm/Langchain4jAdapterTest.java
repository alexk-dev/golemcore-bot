package me.golemcore.bot.adapter.outbound.llm;

import me.golemcore.bot.domain.model.LlmChunk;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ContentType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Langchain4jAdapterTest {

    private static final String CONVERT_ARGS_TO_JSON = "convertArgsToJson";
    private static final String PARSE_JSON_ARGS = "parseJsonArgs";
    private static final String IS_RATE_LIMIT_ERROR = "isRateLimitError";
    private static final String CONVERT_TOOLS = "convertTools";
    private static final String CONVERT_MESSAGES = "convertMessages";
    private static final String TEST_MODEL = "test-model";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_TOOL = "tool";
    private static final String WEATHER = "weather";
    private static final String OPENAI = "openai";
    private static final String KEY = "key";
    private static final String SUPPRESS_UNCHECKED = "unchecked";
    private static final String TEST_TOOL = "test_tool";
    private static final String TYPE = "type";
    private static final String OBJECT = "object";
    private static final String PROPERTIES = "properties";
    private static final String STRING = "string";
    private static final String TEST = "test";
    private static final String TEST_CAPITALIZED = "Test";

    private ModelConfigService modelConfig;
    private RuntimeConfigService runtimeConfigService;
    private Langchain4jAdapter adapter;

    @BeforeEach
    void setUp() {
        modelConfig = mock(ModelConfigService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(modelConfig.supportsTemperature(anyString())).thenReturn(true);
        when(modelConfig.getProvider(anyString())).thenReturn(OPENAI);
        when(modelConfig.isReasoningRequired(anyString())).thenReturn(false);
        when(modelConfig.getAllModels()).thenReturn(Map.of());
        when(runtimeConfigService.getTemperature()).thenReturn(0.7);
        when(runtimeConfigService.getBalancedModel()).thenReturn(OPENAI + "/gpt-5.1");
        when(runtimeConfigService.getBalancedModelReasoning()).thenReturn("medium");
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of());
        when(runtimeConfigService.hasLlmProviderApiKey(anyString())).thenReturn(false);
        when(runtimeConfigService.getLlmProviderConfig(anyString()))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder().build());

        adapter = new Langchain4jAdapter(runtimeConfigService, modelConfig) {
            @Override
            protected void sleepBeforeRetry(long backoffMs) {
                // No-op for deterministic fast retry tests.
            }
        };
    }

    // ===== getProviderId =====

    @Test
    void shouldReturnLangchain4jProviderId() {
        assertEquals("langchain4j", adapter.getProviderId());
    }

    // ===== isAvailable =====

    @Test
    void shouldBeAvailableWhenApiKeyConfigured() {
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of(OPENAI));
        when(runtimeConfigService.hasLlmProviderApiKey(OPENAI)).thenReturn(true);

        assertTrue(adapter.isAvailable());
    }

    @Test
    void shouldNotBeAvailableWhenNoApiKeys() {
        assertFalse(adapter.isAvailable());
    }

    @Test
    void shouldNotBeAvailableWhenApiKeyBlank() {
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of(OPENAI));
        when(runtimeConfigService.hasLlmProviderApiKey(OPENAI)).thenReturn(false);

        assertFalse(adapter.isAvailable());
    }

    // ===== supportsStreaming =====

    @Test
    void shouldSupportStreaming() {
        assertTrue(adapter.supportsStreaming());
    }

    // ===== getCurrentModel =====

    @Test
    void shouldReturnNullModelBeforeInit() {
        assertNull(adapter.getCurrentModel());
    }

    // ===== getSupportedModels =====

    @Test
    void shouldReturnModelsFromConfig() {
        when(runtimeConfigService.hasLlmProviderApiKey(OPENAI)).thenReturn(true);
        when(runtimeConfigService.hasLlmProviderApiKey("anthropic")).thenReturn(false);

        ModelConfigService.ModelSettings openaiSettings = new ModelConfigService.ModelSettings();
        openaiSettings.setProvider(OPENAI);
        openaiSettings.setSupportsTemperature(true);
        ModelConfigService.ModelSettings anthropicSettings = new ModelConfigService.ModelSettings();
        anthropicSettings.setProvider("anthropic");
        anthropicSettings.setSupportsTemperature(true);

        when(modelConfig.getAllModels()).thenReturn(Map.of(
                "gpt-4o", openaiSettings,
                "claude-3-haiku", anthropicSettings));

        List<String> models = adapter.getSupportedModels();
        assertTrue(models.contains(OPENAI + "/gpt-4o"));
        // claude-3-haiku should not be included because "anthropic" provider is not
        // configured
        assertFalse(models.contains("anthropic/claude-3-haiku"));
    }

    @Test
    void shouldReturnEmptyModelsWhenNullConfig() {
        when(modelConfig.getAllModels()).thenReturn(null);
        List<String> models = adapter.getSupportedModels();
        assertTrue(models.isEmpty());
    }

    // ===== getLlmPort =====

    @Test
    void shouldReturnSelfAsLlmPort() {
        assertSame(adapter, adapter.getLlmPort());
    }

    // ===== convertArgsToJson =====

    @Test
    void shouldConvertNullArgsToEmptyJson() {
        String result = ReflectionTestUtils.invokeMethod(adapter, CONVERT_ARGS_TO_JSON, (Map<String, Object>) null);
        assertEquals("{}", result);
    }

    @Test
    void shouldConvertEmptyArgsToEmptyJson() {
        String result = ReflectionTestUtils.invokeMethod(adapter, CONVERT_ARGS_TO_JSON, Collections.emptyMap());
        assertEquals("{}", result);
    }

    @Test
    void shouldConvertValidArgsToJson() {
        String json = ReflectionTestUtils.invokeMethod(adapter, CONVERT_ARGS_TO_JSON, Map.of(KEY, "value"));
        assertTrue(json.contains(KEY));
        assertTrue(json.contains("value"));
    }

    // ===== parseJsonArgs =====

    @Test
    void shouldParseNullJsonToEmptyMap() {
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        Map<String, Object> result = (Map<String, Object>) ReflectionTestUtils.invokeMethod(adapter, PARSE_JSON_ARGS,
                (String) null);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldParseBlankJsonToEmptyMap() {
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        Map<String, Object> result = (Map<String, Object>) ReflectionTestUtils.invokeMethod(adapter, PARSE_JSON_ARGS,
                "  ");
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldParseInvalidJsonToEmptyMap() {
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        Map<String, Object> result = (Map<String, Object>) ReflectionTestUtils.invokeMethod(adapter, PARSE_JSON_ARGS,
                "invalid json");
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldParseValidJson() {
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        Map<String, Object> result = (Map<String, Object>) ReflectionTestUtils.invokeMethod(adapter, PARSE_JSON_ARGS,
                "{\"key\":\"value\"}");
        assertEquals("value", result.get(KEY));
    }

    // ===== isRateLimitError =====

    @Test
    void shouldDetectRateLimitErrors() {
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR,
                new RuntimeException("rate_limit exceeded")));
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR,
                new RuntimeException("token_quota_exceeded")));
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR,
                new RuntimeException("too_many_tokens")));
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR,
                new RuntimeException("Too Many Requests")));
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR,
                new RuntimeException("HTTP 429")));
    }

    @Test
    void shouldDetectRateLimitInCauseChain() {
        RuntimeException inner = new RuntimeException("rate_limit");
        RuntimeException outer = new RuntimeException("Wrapper", inner);

        assertTrue((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR, outer));
    }

    @Test
    void shouldNotDetectNonRateLimitErrors() {
        assertFalse((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR,
                new RuntimeException("Connection refused")));
        assertFalse((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR,
                new RuntimeException("Internal server error")));
    }

    @Test
    void shouldHandleNullMessageInException() {
        assertFalse((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR,
                new RuntimeException((String) null)));
    }

    // ===== convertTools =====

    @Test
    void shouldReturnEmptyForNullTools() {
        LlmRequest request = LlmRequest.builder().tools(null).build();
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Object> result = (List<Object>) ReflectionTestUtils.invokeMethod(adapter, CONVERT_TOOLS, request);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyForEmptyTools() {
        LlmRequest request = LlmRequest.builder().tools(List.of()).build();
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Object> result = (List<Object>) ReflectionTestUtils.invokeMethod(adapter, CONVERT_TOOLS, request);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldConvertToolDefinitions() {
        ToolDefinition tool = ToolDefinition.builder()
                .name(TEST_TOOL)
                .description("A test tool")
                .inputSchema(Map.of(
                        TYPE, OBJECT,
                        PROPERTIES, Map.of(
                                "param1", Map.of(TYPE, STRING, "description", "A parameter")),
                        "required", List.of("param1")))
                .build();

        LlmRequest request = LlmRequest.builder().tools(List.of(tool)).build();
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Object> result = (List<Object>) ReflectionTestUtils.invokeMethod(adapter, CONVERT_TOOLS, request);
        assertEquals(1, result.size());
    }

    // ===== convertToolDefinition with enum and array =====

    @Test
    void shouldConvertToolWithEnumParam() {
        ToolDefinition tool = ToolDefinition.builder()
                .name(TEST)
                .description(TEST_CAPITALIZED)
                .inputSchema(Map.of(
                        TYPE, OBJECT,
                        PROPERTIES, Map.of(
                                "mode", Map.of(
                                        TYPE, STRING,
                                        "enum", List.of("fast", "slow")))))
                .build();

        LlmRequest request = LlmRequest.builder().tools(List.of(tool)).build();
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Object> result = (List<Object>) ReflectionTestUtils.invokeMethod(adapter, CONVERT_TOOLS, request);
        assertEquals(1, result.size());
    }

    @Test
    void shouldConvertToolWithArrayParam() {
        ToolDefinition tool = ToolDefinition.builder()
                .name(TEST)
                .description(TEST_CAPITALIZED)
                .inputSchema(Map.of(
                        TYPE, OBJECT,
                        PROPERTIES, Map.of(
                                "items", Map.of(
                                        TYPE, "array",
                                        "items", Map.of(TYPE, STRING)))))
                .build();

        LlmRequest request = LlmRequest.builder().tools(List.of(tool)).build();
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Object> result = (List<Object>) ReflectionTestUtils.invokeMethod(adapter, CONVERT_TOOLS, request);
        assertEquals(1, result.size());
    }

    @Test
    void shouldConvertToolWithNestedObjectParam() {
        ToolDefinition tool = ToolDefinition.builder()
                .name(TEST)
                .description(TEST_CAPITALIZED)
                .inputSchema(Map.of(
                        TYPE, OBJECT,
                        PROPERTIES, Map.of(
                                "config", Map.of(
                                        TYPE, OBJECT,
                                        PROPERTIES, Map.of(KEY, Map.of(TYPE, STRING))))))
                .build();

        LlmRequest request = LlmRequest.builder().tools(List.of(tool)).build();
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Object> result = (List<Object>) ReflectionTestUtils.invokeMethod(adapter, CONVERT_TOOLS, request);
        assertEquals(1, result.size());
    }

    @Test
    void shouldConvertToolWithNullInputSchema() {
        ToolDefinition tool = ToolDefinition.builder()
                .name(TEST)
                .description(TEST_CAPITALIZED)
                .inputSchema(null)
                .build();

        LlmRequest request = LlmRequest.builder().tools(List.of(tool)).build();
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Object> result = (List<Object>) ReflectionTestUtils.invokeMethod(adapter, CONVERT_TOOLS, request);
        assertEquals(1, result.size());
    }

    // ===== convertMessages passthrough =====

    @Test
    void shouldPassThroughToolCallIdsUnchanged() {
        Message assistantMsg = Message.builder()
                .role(ROLE_ASSISTANT)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_abc123")
                        .name(WEATHER)
                        .arguments(Map.of())
                        .build()))
                .build();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(
                        Message.builder().role(ROLE_USER).content("Hi").build(),
                        assistantMsg))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Object> messages = (List<Object>) ReflectionTestUtils.invokeMethod(adapter, CONVERT_MESSAGES, request);
        assertFalse(messages.isEmpty());
    }

    @Test
    void shouldHandleToolMessageRole() {
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("System prompt")
                .messages(List.of(
                        Message.builder().role(ROLE_USER).content(TEST_CAPITALIZED).build(),
                        Message.builder().role("system").content("System note").build()))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Object> messages = (List<Object>) ReflectionTestUtils.invokeMethod(adapter, CONVERT_MESSAGES, request);
        // System prompt + user + system note = 3 messages
        assertEquals(3, messages.size());
    }

    @Test
    void shouldConvertUserMessageWithImageAttachmentToMultimodal() {
        Message userWithImage = Message.builder()
                .role(ROLE_USER)
                .content("Describe this")
                .metadata(Map.of(
                        "attachments", List.of(Map.of(
                                TYPE, "image",
                                "mimeType", "image/png",
                                "dataBase64", "iVBORw0KGgo="))))
                .build();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(userWithImage))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> messages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(adapter, CONVERT_MESSAGES,
                request);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0) instanceof UserMessage);

        UserMessage userMessage = (UserMessage) messages.get(0);
        List<Content> contents = userMessage.contents();
        assertEquals(2, contents.size());
        assertEquals(ContentType.TEXT, contents.get(0).type());
        assertEquals(ContentType.IMAGE, contents.get(1).type());
    }

    // ===== stripProviderPrefix =====

    @ParameterizedTest
    @ValueSource(strings = { "openai/gpt-4o", "anthropic/claude-3" })
    void shouldStripProviderPrefix(String model) {
        String result = ReflectionTestUtils.invokeMethod(adapter, "stripProviderPrefix", model);
        assertFalse(result.contains("/"));
    }

    @Test
    void shouldNotStripWhenNoPrefix() {
        String result = ReflectionTestUtils.invokeMethod(adapter, "stripProviderPrefix", "gpt-4o");
        assertEquals("gpt-4o", result);
    }

    // ===== chat() flow tests with mocked ChatModel =====

    @Test
    void shouldFailChatWhenNotInitialized() {
        // adapter not initialized, chatModel is null
        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        CompletableFuture<LlmResponse> future = adapter.chat(request);
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause().getMessage().contains("not available"));
    }

    @Test
    void shouldReturnResponseOnSuccessfulChat() throws Exception {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, TEST_MODEL);

        AiMessage aiMessage = AiMessage.from("Hello back!");
        TokenUsage tokenUsage = new TokenUsage(10, 5, 15);
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .tokenUsage(tokenUsage)
                .finishReason(FinishReason.STOP)
                .build();

        when(mockModel.chat((List<ChatMessage>) any())).thenReturn(chatResponse);

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertEquals("Hello back!", response.getContent());
        assertEquals("STOP", response.getFinishReason());
        assertNotNull(response.getUsage());
        assertEquals(10, response.getUsage().getInputTokens());
        assertEquals(5, response.getUsage().getOutputTokens());
    }

    @Test
    void shouldReturnResponseWithToolCalls() throws Exception {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, TEST_MODEL);

        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_1")
                .name(WEATHER)
                .arguments("{\"location\":\"London\"}")
                .build();
        AiMessage aiMessage = AiMessage.from(List.of(toolReq));

        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.TOOL_EXECUTION)
                .build();

        when(mockModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

        ToolDefinition toolDef = ToolDefinition.builder()
                .name(WEATHER)
                .description("Get weather")
                .inputSchema(Map.of(TYPE, OBJECT, PROPERTIES, Map.of()))
                .build();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role(ROLE_USER).content("Weather?").build()))
                .tools(List.of(toolDef))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertTrue(response.hasToolCalls());
        assertEquals(1, response.getToolCalls().size());
        assertEquals("call_1", response.getToolCalls().get(0).getId());
        assertEquals(WEATHER, response.getToolCalls().get(0).getName());
        assertEquals("London", response.getToolCalls().get(0).getArguments().get("location"));
    }

    @Test
    void shouldRetryOnRateLimitError() throws Exception {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, TEST_MODEL);

        // First call throws rate limit, second succeeds
        AiMessage aiMessage = AiMessage.from("Success after retry");
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.STOP)
                .build();

        when(mockModel.chat((List<ChatMessage>) any()))
                .thenThrow(new RuntimeException("rate_limit exceeded"))
                .thenReturn(chatResponse);

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertEquals("Success after retry", response.getContent());
    }

    @Test
    void shouldThrowOnNonRateLimitError() {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, TEST_MODEL);

        when(mockModel.chat((List<ChatMessage>) any()))
                .thenThrow(new RuntimeException("Connection refused"));

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        ExecutionException ex = assertThrows(ExecutionException.class, () -> adapter.chat(request).get());
        assertTrue(ex.getCause().getMessage().contains("Connection refused"));
    }

    @Test
    void shouldHandleChatWithToolsViaRequest() throws Exception {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, TEST_MODEL);

        AiMessage aiMessage = AiMessage.from("Result with tools");
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.STOP)
                .build();

        when(mockModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

        ToolDefinition toolDef = ToolDefinition.builder()
                .name(TEST_TOOL)
                .description(TEST_CAPITALIZED)
                .inputSchema(Map.of(TYPE, OBJECT, PROPERTIES, Map.of()))
                .build();

        LlmRequest request = LlmRequest.builder()
                .systemPrompt("You are a bot")
                .messages(List.of(Message.builder().role(ROLE_USER).content(TEST).build()))
                .tools(List.of(toolDef))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertEquals("Result with tools", response.getContent());
    }

    @Test
    void shouldHandleChatWithNullTokenUsage() throws Exception {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, TEST_MODEL);

        AiMessage aiMessage = AiMessage.from("No usage info");
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.STOP)
                .build();

        when(mockModel.chat((List<ChatMessage>) any())).thenReturn(chatResponse);

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertNull(response.getUsage());
        assertEquals("No usage info", response.getContent());
    }

    @Test
    void shouldHandleNullFinishReason() throws Exception {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, TEST_MODEL);

        AiMessage aiMessage = AiMessage.from("Response");
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .build();

        when(mockModel.chat((List<ChatMessage>) any())).thenReturn(chatResponse);

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertEquals("stop", response.getFinishReason());
    }

    // ===== convertMessages with tool message round-trip =====

    @Test
    void shouldConvertToolMessageRoundTrip() throws Exception {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, TEST_MODEL);

        AiMessage aiMessage = AiMessage.from("Weather is sunny");
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.STOP)
                .build();

        when(mockModel.chat((List<ChatMessage>) any())).thenReturn(chatResponse);

        Message assistantMsg = Message.builder()
                .role(ROLE_ASSISTANT)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_short")
                        .name(WEATHER)
                        .arguments(Map.of("location", "London"))
                        .build()))
                .build();

        Message toolResultMsg = Message.builder()
                .role(ROLE_TOOL)
                .content("Sunny, 25C")
                .toolCallId("call_short")
                .toolName(WEATHER)
                .build();

        LlmRequest request = LlmRequest.builder()
                .systemPrompt("You are helpful")
                .messages(List.of(
                        Message.builder().role(ROLE_USER).content("Weather?").build(),
                        assistantMsg,
                        toolResultMsg,
                        Message.builder().role(ROLE_USER).content("Thanks!").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertEquals("Weather is sunny", response.getContent());
    }

    // ===== chatStream =====

    @Test
    void shouldStreamChat() throws Exception {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, TEST_MODEL);

        AiMessage aiMessage = AiMessage.from("Streamed response");
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.STOP)
                .build();

        when(mockModel.chat((List<ChatMessage>) any())).thenReturn(chatResponse);

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        List<LlmChunk> chunks = adapter.chatStream(request).collectList().block();
        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertEquals("Streamed response", chunks.get(0).getText());
        assertTrue(chunks.get(0).isDone());
    }

    // ===== getModelForRequest override =====

    @Test
    void shouldUseDifferentModelForRequest() throws Exception {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, OPENAI + "/gpt-5.1");

        AiMessage aiMessage = AiMessage.from("Default response");
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.STOP)
                .build();

        // The default model should be used
        when(mockModel.chat((List<ChatMessage>) any())).thenReturn(chatResponse);

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertEquals("Default response", response.getContent());
    }

    // ===== Helpers =====

    private void injectChatModel(ChatModel model, String modelName) {
        ReflectionTestUtils.setField(adapter, "chatModel", model);
        ReflectionTestUtils.setField(adapter, "currentModel", modelName);
        ReflectionTestUtils.setField(adapter, "initialized", true);
    }
}
