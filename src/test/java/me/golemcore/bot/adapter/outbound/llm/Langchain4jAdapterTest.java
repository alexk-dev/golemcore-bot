package me.golemcore.bot.adapter.outbound.llm;

import me.golemcore.bot.domain.model.LlmChunk;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ToolArtifactService;
import me.golemcore.bot.domain.model.ToolArtifactDownload;
import me.golemcore.bot.port.outbound.ModelConfigPort;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ContentType;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    private ModelConfigPort modelConfig;
    private RuntimeConfigService runtimeConfigService;
    private ToolArtifactService toolArtifactService;
    private Langchain4jAdapter adapter;

    @BeforeEach
    void setUp() {
        modelConfig = mock(ModelConfigPort.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        toolArtifactService = mock(ToolArtifactService.class);
        when(modelConfig.supportsTemperature(anyString())).thenReturn(true);
        when(modelConfig.supportsVision(anyString())).thenReturn(false);
        when(modelConfig.getProvider(anyString())).thenReturn(OPENAI);
        when(modelConfig.isReasoningRequired(anyString())).thenReturn(false);
        when(modelConfig.getAllModels()).thenReturn(Map.of());
        when(runtimeConfigService.getTemperature()).thenReturn(0.7);
        when(runtimeConfigService.getBalancedModel()).thenReturn(OPENAI + "/gpt-5.1");
        when(runtimeConfigService.getBalancedModelReasoning()).thenReturn("medium");
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of());
        when(runtimeConfigService.hasLlmProviderApiKey(anyString())).thenReturn(false);
        when(runtimeConfigService.getLlmProviderConfig(anyString()))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder().legacyApi(true).build());

        adapter = new Langchain4jAdapter(runtimeConfigService, modelConfig, toolArtifactService) {
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

    @Test
    void shouldInitializeWithoutDefaultModelConfigured() {
        when(runtimeConfigService.getBalancedModel()).thenReturn(null);
        when(runtimeConfigService.getBalancedModelReasoning()).thenReturn("none");

        adapter.initialize();

        assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(adapter, "initialized"));
        assertNull(adapter.getCurrentModel());
        assertNull(ReflectionTestUtils.getField(adapter, "chatModel"));
    }

    @Test
    void shouldInitializeWithoutDefaultModelWhenBalancedModelIsBlank() {
        when(runtimeConfigService.getBalancedModel()).thenReturn("   ");
        when(runtimeConfigService.getBalancedModelReasoning()).thenReturn("none");

        adapter.initialize();

        assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(adapter, "initialized"));
        assertEquals("   ", ReflectionTestUtils.getField(adapter, "currentModel"));
        assertNull(ReflectionTestUtils.getField(adapter, "chatModel"));
    }

    @Test
    void shouldNotReinitializeAfterSuccessfulInitialization() {
        when(runtimeConfigService.getBalancedModel()).thenReturn(OPENAI + "/gpt-5.1");
        when(runtimeConfigService.getBalancedModelReasoning()).thenReturn("medium");
        when(runtimeConfigService.getLlmProviderConfig(OPENAI))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of(KEY))
                        .apiType(OPENAI)
                        .legacyApi(true)
                        .build());

        adapter.initialize();
        ChatModel firstModel = (ChatModel) ReflectionTestUtils.getField(adapter, "chatModel");

        adapter.initialize();
        ChatModel secondModel = (ChatModel) ReflectionTestUtils.getField(adapter, "chatModel");

        assertNotNull(firstModel);
        assertSame(firstModel, secondModel);
    }

    // ===== getSupportedModels =====

    @Test
    void shouldReturnModelsFromConfig() {
        when(runtimeConfigService.hasLlmProviderApiKey(OPENAI)).thenReturn(true);
        when(runtimeConfigService.hasLlmProviderApiKey("anthropic")).thenReturn(false);

        ModelCatalogEntry openaiSettings = new ModelCatalogEntry();
        openaiSettings.setProvider(OPENAI);
        openaiSettings.setSupportsTemperature(true);
        ModelCatalogEntry anthropicSettings = new ModelCatalogEntry();
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

    @Test
    void shouldDropDuplicateToolDefinitionsByName() {
        ToolDefinition first = ToolDefinition.builder()
                .name(TEST_TOOL)
                .description("first")
                .inputSchema(Map.of(TYPE, OBJECT, PROPERTIES, Map.of()))
                .build();
        ToolDefinition duplicate = ToolDefinition.builder()
                .name(TEST_TOOL)
                .description("duplicate")
                .inputSchema(Map.of(TYPE, OBJECT, PROPERTIES, Map.of()))
                .build();

        LlmRequest request = LlmRequest.builder().tools(List.of(first, duplicate)).build();
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Object> result = (List<Object>) ReflectionTestUtils.invokeMethod(adapter, CONVERT_TOOLS, request);

        assertEquals(1, result.size());
    }

    @Test
    void shouldIgnoreInvalidToolSchemaShapesWithoutThrowing() {
        ToolDefinition tool = ToolDefinition.builder()
                .name(TEST_TOOL)
                .description("A test tool")
                .inputSchema(Map.of(
                        TYPE, OBJECT,
                        PROPERTIES, Map.of(
                                "param1", "not-a-schema-map"),
                        "required", List.of("param1")))
                .build();

        LlmRequest request = LlmRequest.builder().tools(List.of(tool)).build();
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Object> result = (List<Object>) ReflectionTestUtils.invokeMethod(adapter, CONVERT_TOOLS, request);

        assertEquals(1, result.size());
    }

    @Test
    void shouldIgnoreNonStringSchemaTypeAndDescriptionWithoutThrowing() {
        ToolDefinition tool = ToolDefinition.builder()
                .name(TEST_TOOL)
                .description("A test tool")
                .inputSchema(Map.of(
                        TYPE, OBJECT,
                        PROPERTIES, Map.of(
                                "param1", Map.of(
                                        TYPE, List.of("not-a-string"),
                                        "description", List.of("also-not-a-string")))))
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

    @Test
    void shouldReturnNullWhenModelIsNullWhileStrippingProviderPrefix() {
        String result = ReflectionTestUtils.invokeMethod(adapter, "stripProviderPrefix", (String) null);
        assertNull(result);
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
    void shouldRetryWithoutToolImagesWhenProviderRejectsOversizedJsonBody() throws Exception {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, "openai/gpt-4.1");
        when(modelConfig.supportsVision("openai/gpt-4.1")).thenReturn(true);
        when(toolArtifactService.getDownload(".golemcore/tool-artifacts/session/pinchtab/capture.png"))
                .thenReturn(ToolArtifactDownload.builder()
                        .path(".golemcore/tool-artifacts/session/pinchtab/capture.png")
                        .filename("capture.png")
                        .mimeType("image/png")
                        .size(4L)
                        .data(new byte[] { 1, 2, 3, 4 })
                        .build());

        AiMessage aiMessage = AiMessage.from("Recovered without images");
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.STOP)
                .build();

        when(mockModel.chat((List<ChatMessage>) any()))
                .thenThrow(new RuntimeException("{\"detail\":\"request body must be valid JSON\"}"))
                .thenReturn(chatResponse);

        Message assistantMsg = Message.builder()
                .role(ROLE_ASSISTANT)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_img")
                        .name("pinchtab_screenshot")
                        .arguments(Map.of("tabId", "tab-1"))
                        .build()))
                .build();

        Message toolResultMsg = Message.builder()
                .role(ROLE_TOOL)
                .content("Captured screenshot")
                .toolCallId("call_img")
                .toolName("pinchtab_screenshot")
                .metadata(Map.of(
                        "toolAttachments", List.of(Map.of(
                                TYPE, "image",
                                "name", "capture.png",
                                "mimeType", "image/png",
                                "internalFilePath", ".golemcore/tool-artifacts/session/pinchtab/capture.png"))))
                .build();

        LlmRequest request = LlmRequest.builder()
                .model("openai/gpt-4.1")
                .messages(List.of(
                        Message.builder().role(ROLE_USER).content("Inspect this capture").build(),
                        assistantMsg,
                        toolResultMsg))
                .build();

        LlmResponse response = adapter.chat(request).get();

        assertEquals("Recovered without images", response.getContent());
        assertEquals(Boolean.TRUE, response.getProviderMetadata().get("toolAttachmentFallbackApplied"));
        assertEquals("oversize_invalid_json", response.getProviderMetadata().get("toolAttachmentFallbackReason"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockModel, times(2)).chat(messagesCaptor.capture());
        List<List<ChatMessage>> calls = messagesCaptor.getAllValues();
        assertTrue(hasImageContent(calls.get(0)));
        assertFalse(hasImageContent(calls.get(1)));
        assertTrue(hasToolAttachmentPlaceholder(calls.get(1),
                ".golemcore/tool-artifacts/session/pinchtab/capture.png"));
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
    void shouldHandleChatWithPartiallyNullTokenUsage() throws Exception {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, TEST_MODEL);

        AiMessage aiMessage = AiMessage.from("Partial usage info");
        TokenUsage tokenUsage = new TokenUsage(10, null, null);
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
        assertNotNull(response.getUsage());
        assertEquals(10, response.getUsage().getInputTokens());
        assertEquals(0, response.getUsage().getOutputTokens());
        assertEquals(10, response.getUsage().getTotalTokens());
    }

    @Test
    void shouldHandleChatWithOnlyTotalTokenUsage() throws Exception {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, TEST_MODEL);

        AiMessage aiMessage = AiMessage.from("Total-only usage info");
        TokenUsage tokenUsage = new TokenUsage(null, null, 17);
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
        assertNotNull(response.getUsage());
        assertEquals(0, response.getUsage().getInputTokens());
        assertEquals(0, response.getUsage().getOutputTokens());
        assertEquals(17, response.getUsage().getTotalTokens());
    }

    @Test
    void shouldReturnNullUsageWhenAllTokenUsageFieldsAreNull() throws Exception {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, TEST_MODEL);

        AiMessage aiMessage = AiMessage.from("All usage fields null");
        TokenUsage tokenUsage = new TokenUsage(null, null, null);
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
        assertNull(response.getUsage());
        assertEquals("All usage fields null", response.getContent());
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

    @Test
    void shouldInjectToolImageAsMultimodalContextForVisionModels() {
        when(modelConfig.supportsVision("openai/gpt-4.1")).thenReturn(true);
        when(toolArtifactService.getDownload(".golemcore/tool-artifacts/session/pinchtab/capture.png"))
                .thenReturn(ToolArtifactDownload.builder()
                        .path(".golemcore/tool-artifacts/session/pinchtab/capture.png")
                        .filename("capture.png")
                        .mimeType("image/png")
                        .size(4L)
                        .data(new byte[] { 1, 2, 3, 4 })
                        .build());

        Message assistantMsg = Message.builder()
                .role(ROLE_ASSISTANT)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_img")
                        .name("pinchtab_screenshot")
                        .arguments(Map.of("tabId", "tab-1"))
                        .build()))
                .build();

        Message toolResultMsg = Message.builder()
                .role(ROLE_TOOL)
                .content("Captured screenshot")
                .toolCallId("call_img")
                .toolName("pinchtab_screenshot")
                .metadata(Map.of(
                        "toolAttachments", List.of(Map.of(
                                TYPE, "image",
                                "mimeType", "image/png",
                                "internalFilePath", ".golemcore/tool-artifacts/session/pinchtab/capture.png"))))
                .build();

        LlmRequest request = LlmRequest.builder()
                .model("openai/gpt-4.1")
                .messages(List.of(
                        Message.builder().role(ROLE_USER).content("Check the page").build(),
                        assistantMsg,
                        toolResultMsg))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> messages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, request);

        assertEquals(4, messages.size());
        assertTrue(messages.get(2) instanceof ToolExecutionResultMessage);
        assertTrue(messages.get(3) instanceof UserMessage);
        UserMessage visualContext = (UserMessage) messages.get(3);
        assertEquals(2, visualContext.contents().size());
        assertEquals(ContentType.TEXT, visualContext.contents().get(0).type());
        assertEquals(ContentType.IMAGE, visualContext.contents().get(1).type());
        assertTrue(textOf(visualContext.contents().get(0))
                .contains(".golemcore/tool-artifacts/session/pinchtab/capture.png"));
    }

    @Test
    void shouldSkipToolImageInjectionForNonVisionModels() {
        Message assistantMsg = Message.builder()
                .role(ROLE_ASSISTANT)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_img")
                        .name("pinchtab_screenshot")
                        .arguments(Map.of("tabId", "tab-1"))
                        .build()))
                .build();

        Message toolResultMsg = Message.builder()
                .role(ROLE_TOOL)
                .content("Captured screenshot")
                .toolCallId("call_img")
                .toolName("pinchtab_screenshot")
                .metadata(Map.of(
                        "toolAttachments", List.of(Map.of(
                                TYPE, "image",
                                "mimeType", "image/png",
                                "internalFilePath", ".golemcore/tool-artifacts/session/pinchtab/capture.png"))))
                .build();

        LlmRequest request = LlmRequest.builder()
                .model("openai/gpt-4.1-mini")
                .messages(List.of(assistantMsg, toolResultMsg))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> messages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, request);

        assertEquals(3, messages.size());
        assertTrue(messages.get(1) instanceof ToolExecutionResultMessage);
        assertTrue(messages.get(2) instanceof UserMessage);
        UserMessage placeholder = (UserMessage) messages.get(2);
        assertEquals(1, placeholder.contents().size());
        assertEquals(ContentType.TEXT, placeholder.contents().get(0).type());
        assertTrue(textOf(placeholder.contents().get(0))
                .contains(".golemcore/tool-artifacts/session/pinchtab/capture.png"));
    }

    @Test
    void shouldIgnoreBrokenToolImageAttachmentDownload() {
        when(modelConfig.supportsVision("openai/gpt-4.1")).thenReturn(true);
        when(toolArtifactService.getDownload(".golemcore/tool-artifacts/session/pinchtab/missing.png"))
                .thenThrow(new IllegalArgumentException("File not found"));

        Message assistantMsg = Message.builder()
                .role(ROLE_ASSISTANT)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_img")
                        .name("pinchtab_screenshot")
                        .arguments(Map.of("tabId", "tab-1"))
                        .build()))
                .build();

        Message toolResultMsg = Message.builder()
                .role(ROLE_TOOL)
                .content("Captured screenshot")
                .toolCallId("call_img")
                .toolName("pinchtab_screenshot")
                .metadata(Map.of(
                        "toolAttachments", List.of(Map.of(
                                TYPE, "image",
                                "mimeType", "image/png",
                                "internalFilePath", ".golemcore/tool-artifacts/session/pinchtab/missing.png"))))
                .build();

        LlmRequest request = LlmRequest.builder()
                .model("openai/gpt-4.1")
                .messages(List.of(assistantMsg, toolResultMsg))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> messages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, request);

        assertEquals(3, messages.size());
        assertTrue(messages.get(1) instanceof ToolExecutionResultMessage);
        assertTrue(messages.get(2) instanceof UserMessage);
        UserMessage placeholder = (UserMessage) messages.get(2);
        assertEquals(1, placeholder.contents().size());
        assertEquals(ContentType.TEXT, placeholder.contents().get(0).type());
        assertTrue(textOf(placeholder.contents().get(0))
                .contains(".golemcore/tool-artifacts/session/pinchtab/missing.png"));
    }

    @Test
    void shouldCollapseHistoricalToolImageAttachmentsToTextPlaceholder() {
        when(modelConfig.supportsVision("openai/gpt-4.1")).thenReturn(true);

        Message assistantMsg = Message.builder()
                .role(ROLE_ASSISTANT)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_img")
                        .name("pinchtab_screenshot")
                        .arguments(Map.of("tabId", "tab-1"))
                        .build()))
                .build();

        Message toolResultMsg = Message.builder()
                .role(ROLE_TOOL)
                .content("Captured screenshot")
                .toolCallId("call_img")
                .toolName("pinchtab_screenshot")
                .metadata(Map.of(
                        "toolAttachments", List.of(Map.of(
                                TYPE, "image",
                                "name", "capture.png",
                                "mimeType", "image/png",
                                "internalFilePath", ".golemcore/tool-artifacts/session/pinchtab/capture.png"))))
                .build();

        LlmRequest request = LlmRequest.builder()
                .model("openai/gpt-4.1")
                .messages(List.of(
                        assistantMsg,
                        toolResultMsg,
                        Message.builder().role(ROLE_USER).content("Summarize it").build()))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> messages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, request);

        assertEquals(4, messages.size());
        assertTrue(messages.get(1) instanceof ToolExecutionResultMessage);
        assertTrue(messages.get(2) instanceof UserMessage);
        UserMessage placeholder = (UserMessage) messages.get(2);
        assertEquals(1, placeholder.contents().size());
        assertEquals(ContentType.TEXT, placeholder.contents().get(0).type());
        assertTrue(textOf(placeholder.contents().get(0)).contains("capture.png"));
        assertTrue(textOf(placeholder.contents().get(0))
                .contains(".golemcore/tool-artifacts/session/pinchtab/capture.png"));
    }

    @Test
    void shouldHydrateGeminiThinkingSignatureIntoAssistantToolCalls() {
        when(modelConfig.getProvider("google/gemini-3.1-preview")).thenReturn("google");
        when(runtimeConfigService.getLlmProviderConfig("google"))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiType("gemini")
                        .build());

        Message assistantMsg = Message.builder()
                .role(ROLE_ASSISTANT)
                .content("Let me check that")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_1")
                        .name(WEATHER)
                        .arguments(Map.of("location", "London"))
                        .build()))
                .metadata(Map.of("thinking_signature", "sig-123"))
                .build();

        Message toolResultMsg = Message.builder()
                .role(ROLE_TOOL)
                .content("Sunny, 25C")
                .toolCallId("call_1")
                .toolName(WEATHER)
                .build();

        LlmRequest request = LlmRequest.builder()
                .model("google/gemini-3.1-preview")
                .messages(List.of(assistantMsg, toolResultMsg))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> messages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, request);

        AiMessage aiMessage = (AiMessage) messages.get(0);
        assertEquals("Let me check that", aiMessage.text());
        assertEquals("sig-123", aiMessage.attribute("thinking_signature", String.class));
    }

    @Test
    void shouldStoreGeminiThinkingSignatureInResponseMetadata() throws Exception {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, "google/gemini-3.1-preview");
        when(modelConfig.getProvider("google/gemini-3.1-preview")).thenReturn("google");
        when(runtimeConfigService.getLlmProviderConfig("google"))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiType("gemini")
                        .build());

        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_1")
                .name(WEATHER)
                .arguments("{\"location\":\"London\"}")
                .build();
        AiMessage aiMessage = AiMessage.builder()
                .toolExecutionRequests(List.of(toolReq))
                .attributes(Map.of("thinking_signature", "sig-123"))
                .build();

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
                .model("google/gemini-3.1-preview")
                .messages(List.of(Message.builder().role(ROLE_USER).content("Weather?").build()))
                .tools(List.of(toolDef))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertEquals("sig-123", response.getProviderMetadata().get("thinking_signature"));
    }

    @Test
    void shouldIgnoreGeminiThinkingSignatureForNonGeminiTargetModel() {
        when(modelConfig.getProvider("openai/gpt-5.1")).thenReturn(OPENAI);
        when(runtimeConfigService.getLlmProviderConfig(OPENAI))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiType(OPENAI)
                        .legacyApi(true)
                        .build());

        Message assistantMsg = Message.builder()
                .role(ROLE_ASSISTANT)
                .content("Let me check that")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_1")
                        .name(WEATHER)
                        .arguments(Map.of("location", "London"))
                        .build()))
                .metadata(Map.of("thinking_signature", "sig-123"))
                .build();

        LlmRequest request = LlmRequest.builder()
                .model("openai/gpt-5.1")
                .messages(List.of(assistantMsg))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> messages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, request);

        AiMessage aiMessage = (AiMessage) messages.get(0);
        assertNull(aiMessage.attribute("thinking_signature", String.class));
    }

    @Test
    void shouldUseCurrentGeminiModelWhenRequestModelMissingToHydrateSignature() {
        injectChatModel(mock(ChatModel.class), "google/gemini-3.1-preview");
        when(modelConfig.getProvider("google/gemini-3.1-preview")).thenReturn("google");
        when(runtimeConfigService.getLlmProviderConfig("google"))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiType("gemini")
                        .build());

        Message assistantMsg = Message.builder()
                .role(ROLE_ASSISTANT)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_1")
                        .name(WEATHER)
                        .arguments(Map.of("location", "London"))
                        .build()))
                .metadata(Map.of("thinking_signature", "sig-123"))
                .build();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(assistantMsg))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> messages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, request);

        AiMessage aiMessage = (AiMessage) messages.get(0);
        assertEquals("sig-123", aiMessage.attribute("thinking_signature", String.class));
    }

    @Test
    void shouldNotStoreGeminiThinkingSignatureForFinalAnswerWithoutToolCalls() throws Exception {
        ChatModel mockModel = mock(ChatModel.class);
        injectChatModel(mockModel, "google/gemini-3.1-preview");
        when(modelConfig.getProvider("google/gemini-3.1-preview")).thenReturn("google");
        when(runtimeConfigService.getLlmProviderConfig("google"))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiType("gemini")
                        .build());

        AiMessage aiMessage = AiMessage.builder()
                .text("Final answer")
                .attributes(Map.of("thinking_signature", "sig-final"))
                .build();

        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.STOP)
                .build();

        when(mockModel.chat((List<ChatMessage>) any())).thenReturn(chatResponse);

        LlmRequest request = LlmRequest.builder()
                .model("google/gemini-3.1-preview")
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertNull(response.getProviderMetadata());
    }

    @Test
    void shouldToggleGeminiThoughtSignatureInjectionAcrossRepeatedModelSwitches() {
        when(modelConfig.getProvider("google/gemini-3.1-preview")).thenReturn("google");
        when(modelConfig.getProvider("openai/gpt-5.1")).thenReturn(OPENAI);
        when(runtimeConfigService.getLlmProviderConfig("google"))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiType("gemini")
                        .build());
        when(runtimeConfigService.getLlmProviderConfig(OPENAI))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiType(OPENAI)
                        .legacyApi(true)
                        .build());

        Message assistantMsg = Message.builder()
                .role(ROLE_ASSISTANT)
                .content("Let me check that")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_1")
                        .name(WEATHER)
                        .arguments(Map.of("location", "London"))
                        .build()))
                .metadata(Map.of("thinking_signature", "sig-123"))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> openAiMessages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, LlmRequest.builder()
                        .model("openai/gpt-5.1")
                        .messages(List.of(assistantMsg))
                        .build());
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> geminiMessages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, LlmRequest.builder()
                        .model("google/gemini-3.1-preview")
                        .messages(List.of(assistantMsg))
                        .build());
        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> openAiAgainMessages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, LlmRequest.builder()
                        .model("openai/gpt-5.1")
                        .messages(List.of(assistantMsg))
                        .build());

        assertNull(((AiMessage) openAiMessages.get(0)).attribute("thinking_signature", String.class));
        assertEquals("sig-123", ((AiMessage) geminiMessages.get(0)).attribute("thinking_signature", String.class));
        assertNull(((AiMessage) openAiAgainMessages.get(0)).attribute("thinking_signature", String.class));
    }

    @Test
    void shouldFlattenGeminiToolHistoryWhenAssistantToolCallMissingThinkingSignature() {
        when(modelConfig.getProvider("google/gemini-3.1-preview")).thenReturn("google");
        when(runtimeConfigService.getLlmProviderConfig("google"))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiType("gemini")
                        .build());

        Message assistantMsg = Message.builder()
                .role(ROLE_ASSISTANT)
                .content("Transitioning to reviewer")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_1")
                        .name("default_api:skill_transition")
                        .arguments(Map.of("target_skill", "golemcore/code-reviewer"))
                        .build()))
                .build();

        Message toolResult = Message.builder()
                .role(ROLE_TOOL)
                .toolCallId("call_1")
                .toolName("default_api:skill_transition")
                .content("Transitioning to skill: golemcore/code-reviewer")
                .build();

        LlmRequest request = LlmRequest.builder()
                .model("google/gemini-3.1-preview")
                .messages(List.of(assistantMsg, toolResult))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> messages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, request);

        assertEquals(1, messages.size());
        assertTrue(messages.get(0) instanceof AiMessage);
        AiMessage aiMessage = (AiMessage) messages.get(0);
        assertFalse(aiMessage.hasToolExecutionRequests());
        assertTrue(aiMessage.text().contains("default_api:skill_transition"));
        assertTrue(aiMessage.text().contains("Transitioning to skill: golemcore/code-reviewer"));
        assertNull(aiMessage.attribute("thinking_signature", String.class));
    }

    // ===== convertMessages synthetic ID assignment =====

    @Test
    void shouldAssignSyntheticIdsWhenToolCallIdIsNull() {
        Message assistantMsg = Message.builder()
                .role(ROLE_ASSISTANT)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id(null)
                        .name(WEATHER)
                        .arguments(Map.of("location", "London"))
                        .build()))
                .build();

        Message toolResultMsg = Message.builder()
                .role(ROLE_TOOL)
                .content("Sunny, 25C")
                .toolCallId(null)
                .toolName(WEATHER)
                .build();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(
                        Message.builder().role(ROLE_USER).content("Weather?").build(),
                        assistantMsg,
                        toolResultMsg))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> messages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, request);

        // user + assistant(tool_calls) + tool_result = 3 messages
        assertEquals(3, messages.size());

        // Assistant message should have AiMessage with tool execution requests carrying
        // synthetic IDs
        AiMessage aiMsg = (AiMessage) messages.get(1);
        assertNotNull(aiMsg.toolExecutionRequests());
        assertEquals(1, aiMsg.toolExecutionRequests().size());
        String synthId = aiMsg.toolExecutionRequests().get(0).id();
        assertNotNull(synthId);
        assertTrue(synthId.startsWith("synth_call_"));

        // Tool result should be ToolExecutionResultMessage (not FunctionMessage) with
        // matching synthetic ID
        assertTrue(messages.get(2) instanceof ToolExecutionResultMessage);
        ToolExecutionResultMessage toolResult = (ToolExecutionResultMessage) messages.get(2);
        assertEquals(synthId, toolResult.id());
    }

    @Test
    void shouldAssignSyntheticIdsForBlankToolCallId() {
        Message assistantMsg = Message.builder()
                .role(ROLE_ASSISTANT)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("   ")
                        .name(WEATHER)
                        .arguments(Map.of())
                        .build()))
                .build();

        Message toolResultMsg = Message.builder()
                .role(ROLE_TOOL)
                .content("Result")
                .toolCallId("   ")
                .toolName(WEATHER)
                .build();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(assistantMsg, toolResultMsg))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> messages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, request);

        AiMessage aiMsg = (AiMessage) messages.get(0);
        String synthId = aiMsg.toolExecutionRequests().get(0).id();
        assertTrue(synthId.startsWith("synth_call_"));

        ToolExecutionResultMessage toolResult = (ToolExecutionResultMessage) messages.get(1);
        assertEquals(synthId, toolResult.id());
    }

    @Test
    void shouldPreserveRealToolCallIds() {
        Message assistantMsg = Message.builder()
                .role(ROLE_ASSISTANT)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_real_123")
                        .name(WEATHER)
                        .arguments(Map.of())
                        .build()))
                .build();

        Message toolResultMsg = Message.builder()
                .role(ROLE_TOOL)
                .content("Result")
                .toolCallId("call_real_123")
                .toolName(WEATHER)
                .build();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(assistantMsg, toolResultMsg))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> messages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, request);

        AiMessage aiMsg = (AiMessage) messages.get(0);
        assertEquals("call_real_123", aiMsg.toolExecutionRequests().get(0).id());

        ToolExecutionResultMessage toolResult = (ToolExecutionResultMessage) messages.get(1);
        assertEquals("call_real_123", toolResult.id());
    }

    // ===== convertMessages orphaned tool message handling =====

    @Test
    void shouldConvertOrphanedToolMessageToUserText() {
        Message toolResultMsg = Message.builder()
                .role(ROLE_TOOL)
                .content("orphaned result")
                .toolCallId("call_missing")
                .toolName(WEATHER)
                .build();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(
                        Message.builder().role(ROLE_USER).content("Hi").build(),
                        toolResultMsg))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> messages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, request);

        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof UserMessage);
        // Orphaned tool message should be converted to UserMessage, not
        // ToolExecutionResultMessage
        assertTrue(messages.get(1) instanceof UserMessage);
        UserMessage converted = (UserMessage) messages.get(1);
        assertTrue(converted.singleText().contains("[Tool: weather]"));
        assertTrue(converted.singleText().contains("orphaned result"));
    }

    @Test
    void shouldConvertOrphanedToolMessageWithNullIdToUserText() {
        Message toolResultMsg = Message.builder()
                .role(ROLE_TOOL)
                .content("orphaned null result")
                .toolCallId(null)
                .toolName(WEATHER)
                .build();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(
                        Message.builder().role(ROLE_USER).content("Hi").build(),
                        toolResultMsg))
                .build();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<ChatMessage> messages = (List<ChatMessage>) ReflectionTestUtils.invokeMethod(
                adapter, CONVERT_MESSAGES, request);

        assertEquals(2, messages.size());
        assertTrue(messages.get(1) instanceof UserMessage);
    }

    // ===== isUnsupportedFunctionRoleError detection =====

    @Test
    void shouldDetectOrphanedToolRoleError() {
        String errorMsg = "{\"error\":{\"message\":\"Invalid parameter: messages with role 'tool' "
                + "must be a response to a preceeding message with 'tool_calls'.\"}}";
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(adapter,
                "isUnsupportedFunctionRoleError", new RuntimeException(errorMsg)));
    }

    @Test
    void shouldDetectLegacyFunctionRoleError() {
        String errorMsg = "unsupported_value: model does not support 'function' role";
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(adapter,
                "isUnsupportedFunctionRoleError", new RuntimeException(errorMsg)));
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

    // ===== getApiType =====

    @Test
    void shouldDefaultToOpenAiApiTypeWhenNull() {
        RuntimeConfig.LlmProviderConfig config = RuntimeConfig.LlmProviderConfig.builder().build();
        String result = ReflectionTestUtils.invokeMethod(adapter, "getApiType", config);
        assertEquals("openai", result);
    }

    @Test
    void shouldDefaultToOpenAiApiTypeWhenBlank() {
        RuntimeConfig.LlmProviderConfig config = RuntimeConfig.LlmProviderConfig.builder().apiType("  ").build();
        String result = ReflectionTestUtils.invokeMethod(adapter, "getApiType", config);
        assertEquals("openai", result);
    }

    @Test
    void shouldReturnExplicitApiType() {
        RuntimeConfig.LlmProviderConfig config = RuntimeConfig.LlmProviderConfig.builder().apiType("gemini").build();
        String result = ReflectionTestUtils.invokeMethod(adapter, "getApiType", config);
        assertEquals("gemini", result);
    }

    @Test
    void shouldNormalizeApiTypeToLowercase() {
        RuntimeConfig.LlmProviderConfig config = RuntimeConfig.LlmProviderConfig.builder().apiType("ANTHROPIC").build();
        String result = ReflectionTestUtils.invokeMethod(adapter, "getApiType", config);
        assertEquals("anthropic", result);
    }

    // ===== isResponsesApiRequest routing =====

    @Test
    void shouldRouteToResponsesApiWhenLegacyApiIsNull() {
        injectChatModel(mock(ChatModel.class), OPENAI + "/gpt-5.4");
        when(modelConfig.getProvider(OPENAI + "/gpt-5.4")).thenReturn(OPENAI);
        when(runtimeConfigService.getLlmProviderConfig(OPENAI))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of("key"))
                        .apiType(OPENAI)
                        .build());

        LlmRequest request = LlmRequest.builder()
                .model(OPENAI + "/gpt-5.4")
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        Boolean result = ReflectionTestUtils.invokeMethod(adapter, "isResponsesApiRequest", request);
        assertTrue(result);
    }

    @Test
    void shouldRouteToResponsesApiWhenLegacyApiIsFalse() {
        injectChatModel(mock(ChatModel.class), OPENAI + "/gpt-5.4");
        when(modelConfig.getProvider(OPENAI + "/gpt-5.4")).thenReturn(OPENAI);
        when(runtimeConfigService.getLlmProviderConfig(OPENAI))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of("key"))
                        .apiType(OPENAI)
                        .legacyApi(false)
                        .build());

        LlmRequest request = LlmRequest.builder()
                .model(OPENAI + "/gpt-5.4")
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        Boolean result = ReflectionTestUtils.invokeMethod(adapter, "isResponsesApiRequest", request);
        assertTrue(result);
    }

    @Test
    void shouldNotRouteToResponsesApiWhenLegacyApiIsTrue() {
        injectChatModel(mock(ChatModel.class), OPENAI + "/gpt-5.1");
        when(modelConfig.getProvider(OPENAI + "/gpt-5.1")).thenReturn(OPENAI);
        when(runtimeConfigService.getLlmProviderConfig(OPENAI))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of("key"))
                        .apiType(OPENAI)
                        .legacyApi(true)
                        .build());

        LlmRequest request = LlmRequest.builder()
                .model(OPENAI + "/gpt-5.1")
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        Boolean result = ReflectionTestUtils.invokeMethod(adapter, "isResponsesApiRequest", request);
        assertFalse(result);
    }

    @Test
    void shouldNotRouteToResponsesApiForAnthropicProvider() {
        injectChatModel(mock(ChatModel.class), "anthropic/claude-opus-4-1");
        when(modelConfig.getProvider("anthropic/claude-opus-4-1")).thenReturn("anthropic");
        when(runtimeConfigService.getLlmProviderConfig("anthropic"))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of("key"))
                        .apiType("anthropic")
                        .build());

        LlmRequest request = LlmRequest.builder()
                .model("anthropic/claude-opus-4-1")
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        Boolean result = ReflectionTestUtils.invokeMethod(adapter, "isResponsesApiRequest", request);
        assertFalse(result);
    }

    @Test
    void shouldNotRouteToResponsesApiForGeminiProvider() {
        injectChatModel(mock(ChatModel.class), "google/gemini-3.1-preview");
        when(modelConfig.getProvider("google/gemini-3.1-preview")).thenReturn("google");
        when(runtimeConfigService.getLlmProviderConfig("google"))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of("key"))
                        .apiType("gemini")
                        .build());

        LlmRequest request = LlmRequest.builder()
                .model("google/gemini-3.1-preview")
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        Boolean result = ReflectionTestUtils.invokeMethod(adapter, "isResponsesApiRequest", request);
        assertFalse(result);
    }

    @Test
    void shouldFallbackToCurrentModelWhenRequestModelIsNull() {
        injectChatModel(mock(ChatModel.class), OPENAI + "/gpt-5.1");
        // Default mock returns legacyApi=true from setUp

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        Boolean result = ReflectionTestUtils.invokeMethod(adapter, "isResponsesApiRequest", request);
        assertFalse(result);
    }

    // ===== createModel dispatch by apiType =====

    @Test
    void shouldDispatchToAnthropicModelUsingApiTypeNotProviderName() {
        String requestModel = "custom-provider/claude-opus-4-1";
        when(modelConfig.getProvider(requestModel)).thenReturn("custom-provider");
        when(runtimeConfigService.getLlmProviderConfig("custom-provider"))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of("ant-key"))
                        .apiType("anthropic")
                        .build());

        ChatModel result = ReflectionTestUtils.invokeMethod(adapter, "createModel", requestModel, null);
        assertTrue(result instanceof AnthropicChatModel);
    }

    @Test
    void shouldDispatchToGeminiModelUsingApiTypeNotProviderName() {
        String requestModel = "custom-provider/gemini-2.5-pro";
        when(modelConfig.getProvider(requestModel)).thenReturn("custom-provider");
        when(runtimeConfigService.getLlmProviderConfig("custom-provider"))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of("gemini-key"))
                        .apiType("gemini")
                        .build());

        ChatModel result = ReflectionTestUtils.invokeMethod(adapter, "createModel", requestModel, null);
        assertTrue(result instanceof GoogleAiGeminiChatModel);
        assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(result, "returnThinking"));
        assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(result, "sendThinking"));
    }

    @Test
    void shouldUseScopedModelIdForGeminiCapabilityLookup() {
        String requestModel = "google/gemini-2.5-flash";
        when(modelConfig.getProvider(requestModel)).thenReturn("google");
        when(runtimeConfigService.getLlmProviderConfig("google"))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of("gemini-key"))
                        .apiType("gemini")
                        .build());
        when(modelConfig.supportsTemperature(requestModel)).thenReturn(true);
        when(modelConfig.supportsTemperature("gemini-2.5-flash"))
                .thenThrow(new IllegalArgumentException("raw model id lookup should not be used"));

        ChatModel result = ReflectionTestUtils.invokeMethod(adapter, "createModel", requestModel, null);

        assertTrue(result instanceof GoogleAiGeminiChatModel);
    }

    @Test
    void shouldCreateOneOffModelForRequestWhenDefaultChatModelIsMissing() {
        String requestModel = "google/gemini-2.5-flash";
        when(modelConfig.getProvider(requestModel)).thenReturn("google");
        when(runtimeConfigService.getLlmProviderConfig("google"))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of("gemini-key"))
                        .apiType("gemini")
                        .build());
        when(modelConfig.supportsTemperature(requestModel)).thenReturn(true);

        LlmRequest request = LlmRequest.builder()
                .model(requestModel)
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        ChatModel result = ReflectionTestUtils.invokeMethod(adapter, "getModelForRequest", request);

        assertTrue(result instanceof GoogleAiGeminiChatModel);
    }

    @Test
    void shouldCreateReasoningSpecificModelForCurrentModel() {
        ChatModel defaultModel = mock(ChatModel.class);
        String currentModel = OPENAI + "/gpt-5.4";
        injectChatModel(defaultModel, currentModel);
        when(modelConfig.isReasoningRequired(currentModel)).thenReturn(true);
        when(modelConfig.getProvider(currentModel)).thenReturn(OPENAI);
        when(runtimeConfigService.getLlmProviderConfig(OPENAI))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of(KEY))
                        .apiType(OPENAI)
                        .legacyApi(true)
                        .build());

        LlmRequest request = LlmRequest.builder()
                .reasoningEffort("high")
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        ChatModel result = ReflectionTestUtils.invokeMethod(adapter, "getModelForRequest", request);

        assertTrue(result instanceof OpenAiChatModel);
        assertNotSame(defaultModel, result);
    }

    @Test
    void shouldReuseCurrentModelWhenRequestMatchesConfiguredModel() {
        ChatModel defaultModel = mock(ChatModel.class);
        String currentModel = OPENAI + "/gpt-5.4";
        injectChatModel(defaultModel, currentModel);

        LlmRequest request = LlmRequest.builder()
                .model(currentModel)
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        ChatModel result = ReflectionTestUtils.invokeMethod(adapter, "getModelForRequest", request);

        assertSame(defaultModel, result);
    }

    @Test
    void shouldFallbackToOpenAiWhenApiTypeIsUnknown() {
        String requestModel = "custom-provider/gpt-5.1";
        when(modelConfig.getProvider(requestModel)).thenReturn("custom-provider");
        when(runtimeConfigService.getLlmProviderConfig("custom-provider"))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of("openai-key"))
                        .apiType("unknown")
                        .legacyApi(true)
                        .build());

        ChatModel result = ReflectionTestUtils.invokeMethod(adapter, "createModel", requestModel, "medium");
        assertTrue(result instanceof OpenAiChatModel);
    }

    @Test
    void shouldFailGeminiModelCreationWhenApiKeyMissing() {
        String requestModel = "google/gemini-2.5-pro";
        when(modelConfig.getProvider(requestModel)).thenReturn("google");
        when(runtimeConfigService.getLlmProviderConfig("google"))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiType("gemini")
                        .build());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(adapter, "createModel", requestModel, null));
        assertTrue(error.getMessage().contains("Missing apiKey for Gemini provider"));
    }

    // ===== Responses API streaming integration =====

    @Test
    void shouldRouteSyncChatThroughResponsesStreamingModel() throws Exception {
        String model = OPENAI + "/gpt-5.4";
        StreamingChatModel streamingModel = mockStreamingModel("Hello from Responses API");
        injectResponsesStreamingModel(model, null, streamingModel);
        setupResponsesApiProvider(model);

        LlmRequest request = LlmRequest.builder()
                .model(model)
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();

        assertNotNull(response);
        assertEquals("Hello from Responses API", response.getContent());
    }

    @Test
    void shouldRouteSyncChatWithToolsThroughResponsesStreamingModel() throws Exception {
        String model = OPENAI + "/gpt-5.4";
        StreamingChatModel streamingModel = mockStreamingModelWithToolCall("get_weather", "{\"city\":\"NYC\"}");
        injectResponsesStreamingModel(model, null, streamingModel);
        setupResponsesApiProvider(model);

        LlmRequest request = LlmRequest.builder()
                .model(model)
                .messages(List.of(Message.builder().role(ROLE_USER).content("Weather?").build()))
                .tools(List.of(ToolDefinition.builder()
                        .name("get_weather")
                        .description("Get weather")
                        .inputSchema(Map.of(TYPE, OBJECT, PROPERTIES, Map.of("city", Map.of(TYPE, STRING))))
                        .build()))
                .build();

        LlmResponse response = adapter.chat(request).get();

        assertNotNull(response);
        assertTrue(response.hasToolCalls());
        assertEquals("get_weather", response.getToolCalls().get(0).getName());
    }

    @Test
    void shouldRouteSyncChatWithReasoningEffortThroughResponsesModel() throws Exception {
        String model = OPENAI + "/gpt-5.4";
        String reasoning = "high";
        StreamingChatModel streamingModel = mockStreamingModel("Reasoned response");
        injectResponsesStreamingModel(model, reasoning, streamingModel);
        setupResponsesApiProvider(model);
        when(modelConfig.isReasoningRequired(model)).thenReturn(true);

        LlmRequest request = LlmRequest.builder()
                .model(model)
                .reasoningEffort(reasoning)
                .messages(List.of(Message.builder().role(ROLE_USER).content("Think hard").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();

        assertEquals("Reasoned response", response.getContent());
    }

    @Test
    void shouldCreateResponsesStreamingModelWithCompatibilityBuilder() {
        RuntimeConfig.LlmProviderConfig config = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of(KEY))
                .baseUrl("https://example.com/v1")
                .requestTimeoutSeconds(42)
                .build();

        StreamingChatModel model = ReflectionTestUtils.invokeMethod(adapter,
                "createResponsesStreamingModel", "openai/gpt-5.4", "gpt-5.4", "high", config);
        HttpClientBuilder httpClientBuilder = ReflectionTestUtils.invokeMethod(adapter,
                "createResponsesCompatibilityHttpClientBuilder", Duration.ofSeconds(42));

        assertNotNull(model);
        assertTrue(httpClientBuilder instanceof ResponsesCompatibilityHttpClientBuilder);
        assertEquals(Duration.ofSeconds(42), httpClientBuilder.connectTimeout());
        assertEquals(Duration.ofSeconds(42), httpClientBuilder.readTimeout());
    }

    @Test
    void shouldCreateResponsesStreamingModelUsingScopedModelIdForCapabilityLookup() {
        String model = OPENAI + "/gpt-5.4";
        setupResponsesApiProvider(model);
        when(modelConfig.supportsTemperature(model)).thenReturn(true);
        when(modelConfig.supportsTemperature("gpt-5.4"))
                .thenThrow(new IllegalArgumentException("raw model id lookup should not be used"));

        StreamingChatModel result = ReflectionTestUtils.invokeMethod(adapter,
                "getResponsesStreamingModel", model, "high");

        assertNotNull(result);
    }

    @Test
    void shouldCreateResponsesStreamingModelWhenTemperatureUnsupported() {
        RuntimeConfig.LlmProviderConfig config = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of(KEY))
                .requestTimeoutSeconds(42)
                .build();
        when(modelConfig.supportsTemperature(OPENAI + "/gpt-5.4")).thenReturn(false);

        StreamingChatModel result = ReflectionTestUtils.invokeMethod(adapter,
                "createResponsesStreamingModel", OPENAI + "/gpt-5.4", "gpt-5.4", null, config);

        assertNotNull(result);
    }

    @Test
    void shouldCreateAnthropicModelWhenTemperatureUnsupported() {
        String model = "anthropic/claude-opus-4-1";
        RuntimeConfig.LlmProviderConfig config = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("ant-key"))
                .apiType("anthropic")
                .build();
        when(modelConfig.supportsTemperature(model)).thenReturn(false);

        ChatModel result = ReflectionTestUtils.invokeMethod(adapter,
                "createAnthropicModel", model, "claude-opus-4-1", config);

        assertTrue(result instanceof AnthropicChatModel);
    }

    @Test
    void shouldCreateGeminiModelWhenTemperatureUnsupported() {
        String model = "google/gemini-2.5-flash";
        RuntimeConfig.LlmProviderConfig config = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("gemini-key"))
                .apiType("gemini")
                .build();
        when(modelConfig.supportsTemperature(model)).thenReturn(false);

        ChatModel result = ReflectionTestUtils.invokeMethod(adapter,
                "createGeminiModel", model, "gemini-2.5-flash", config);

        assertTrue(result instanceof GoogleAiGeminiChatModel);
    }

    @Test
    void shouldInstantiateJdkHttpClientBuilderForResponsesCompatibilityFallback() {
        HttpClientBuilder httpClientBuilder = ReflectionTestUtils.invokeMethod(adapter,
                "instantiateJdkHttpClientBuilder");

        assertNotNull(httpClientBuilder);
    }

    @Test
    void shouldStreamThroughResponsesApiModel() {
        String model = OPENAI + "/gpt-5.4";
        StreamingChatModel streamingModel = mockStreamingModelWithPartials("Hello ", "world!");
        injectResponsesStreamingModel(model, null, streamingModel);
        setupResponsesApiProvider(model);

        LlmRequest request = LlmRequest.builder()
                .model(model)
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        List<LlmChunk> chunks = adapter.chatStream(request).collectList().block();

        assertNotNull(chunks);
        assertTrue(chunks.size() >= 2);
        assertEquals("Hello ", chunks.get(0).getText());
        assertFalse(chunks.get(0).isDone());
        // Last chunk should be done
        assertTrue(chunks.get(chunks.size() - 1).isDone());
    }

    @Test
    void shouldStreamWithToolCallsThroughResponsesApi() {
        String model = OPENAI + "/gpt-5.4";
        StreamingChatModel streamingModel = mockStreamingModelWithToolCall("search", "{\"q\":\"test\"}");
        injectResponsesStreamingModel(model, null, streamingModel);
        setupResponsesApiProvider(model);

        LlmRequest request = LlmRequest.builder()
                .model(model)
                .messages(List.of(Message.builder().role(ROLE_USER).content("Search").build()))
                .tools(List.of(ToolDefinition.builder()
                        .name("search")
                        .description("Search")
                        .inputSchema(Map.of(TYPE, OBJECT))
                        .build()))
                .build();

        List<LlmChunk> chunks = adapter.chatStream(request).collectList().block();

        assertNotNull(chunks);
        // Should have final done chunk with tool calls in the response
        assertTrue(chunks.get(chunks.size() - 1).isDone());
    }

    @Test
    void shouldPropagateStreamingErrorInChatStream() {
        String model = OPENAI + "/gpt-5.4";
        StreamingChatModel streamingModel = mockStreamingModelWithError(new RuntimeException("API error"));
        injectResponsesStreamingModel(model, null, streamingModel);
        setupResponsesApiProvider(model);

        LlmRequest request = LlmRequest.builder()
                .model(model)
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        assertThrows(RuntimeException.class,
                () -> adapter.chatStream(request).collectList().block());
    }

    @Test
    void shouldPropagateStreamingErrorInSyncChat() {
        String model = OPENAI + "/gpt-5.4";
        StreamingChatModel streamingModel = mockStreamingModelWithError(new RuntimeException("API timeout"));
        injectResponsesStreamingModel(model, null, streamingModel);
        setupResponsesApiProvider(model);

        LlmRequest request = LlmRequest.builder()
                .model(model)
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> adapter.chat(request).get());
        assertTrue(ex.getCause().getMessage().contains("API timeout"));
    }

    @Test
    void shouldFallToLegacyPathWhenLegacyApiIsTrue() throws Exception {
        String model = OPENAI + "/gpt-5.1";
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(List.class))).thenReturn(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("Legacy response"))
                        .tokenUsage(new TokenUsage(10, 5))
                        .finishReason(FinishReason.STOP)
                        .build());
        injectChatModel(chatModel, model);

        when(modelConfig.getProvider(model)).thenReturn(OPENAI);
        when(runtimeConfigService.getLlmProviderConfig(OPENAI))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of(KEY))
                        .apiType(OPENAI)
                        .legacyApi(true)
                        .build());

        LlmRequest request = LlmRequest.builder()
                .model(model)
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();

        assertEquals("Legacy response", response.getContent());
        verify(chatModel).chat(any(List.class));
    }

    @Test
    void shouldCacheResponsesStreamingModelsPerModelAndReasoning() {
        String model = OPENAI + "/gpt-5.4";
        setupResponsesApiProvider(model);

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        Map<String, StreamingChatModel> cache = (Map<String, StreamingChatModel>) ReflectionTestUtils.getField(adapter,
                "responsesStreamingModels");
        assertNotNull(cache);
        assertTrue(cache.isEmpty());

        // Inject two different models
        StreamingChatModel model1 = mockStreamingModel("r1");
        StreamingChatModel model2 = mockStreamingModel("r2");
        cache.put(model + ":", model1);
        cache.put(model + ":high", model2);

        assertEquals(2, cache.size());
        assertSame(model1, cache.get(model + ":"));
        assertSame(model2, cache.get(model + ":high"));
    }

    @Test
    void shouldUseCurrentModelForResponsesApiWhenRequestModelIsNull() throws Exception {
        String model = OPENAI + "/gpt-5.4";
        StreamingChatModel streamingModel = mockStreamingModel("default model response");
        injectResponsesStreamingModel(model, null, streamingModel);

        when(modelConfig.getProvider(model)).thenReturn(OPENAI);
        when(runtimeConfigService.getLlmProviderConfig(OPENAI))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of(KEY))
                        .apiType(OPENAI)
                        .build());

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role(ROLE_USER).content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();

        assertEquals("default model response", response.getContent());
    }

    // ===== Responses streaming model helpers =====

    private void setupResponsesApiProvider(String model) {
        when(modelConfig.getProvider(model)).thenReturn(OPENAI);
        when(runtimeConfigService.getLlmProviderConfig(OPENAI))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of(KEY))
                        .apiType(OPENAI)
                        .build());
    }

    @SuppressWarnings(SUPPRESS_UNCHECKED)
    private void injectResponsesStreamingModel(String model, String reasoningEffort,
            StreamingChatModel streamingModel) {
        ReflectionTestUtils.setField(adapter, "currentModel", model);
        ReflectionTestUtils.setField(adapter, "initialized", true);
        Map<String, StreamingChatModel> cache = (Map<String, StreamingChatModel>) ReflectionTestUtils.getField(adapter,
                "responsesStreamingModels");
        String cacheKey = model + ":" + (reasoningEffort != null ? reasoningEffort : "");
        cache.put(cacheKey, streamingModel);
    }

    private StreamingChatModel mockStreamingModel(String responseText) {
        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            ChatResponse response = ChatResponse.builder()
                    .aiMessage(AiMessage.from(responseText))
                    .tokenUsage(new TokenUsage(10, 5))
                    .finishReason(FinishReason.STOP)
                    .build();
            handler.onCompleteResponse(response);
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        return model;
    }

    private StreamingChatModel mockStreamingModelWithPartials(String... partials) {
        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            for (String partial : partials) {
                handler.onPartialResponse(partial);
            }
            StringBuilder full = new StringBuilder();
            for (String p : partials) {
                full.append(p);
            }
            ChatResponse response = ChatResponse.builder()
                    .aiMessage(AiMessage.from(full.toString()))
                    .tokenUsage(new TokenUsage(10, 5))
                    .finishReason(FinishReason.STOP)
                    .build();
            handler.onCompleteResponse(response);
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        return model;
    }

    private StreamingChatModel mockStreamingModelWithToolCall(String toolName, String argsJson) {
        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                    .id("call_test_123")
                    .name(toolName)
                    .arguments(argsJson)
                    .build();
            ChatResponse response = ChatResponse.builder()
                    .aiMessage(AiMessage.from(List.of(toolRequest)))
                    .tokenUsage(new TokenUsage(20, 10))
                    .finishReason(FinishReason.TOOL_EXECUTION)
                    .build();
            handler.onCompleteResponse(response);
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        return model;
    }

    private StreamingChatModel mockStreamingModelWithError(Throwable error) {
        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onError(error);
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        return model;
    }

    private StreamingChatModel mockStreamingModelWithoutTerminalEvent() {
        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(invocation -> null)
                .when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        return model;
    }

    // ===== Helpers =====

    private void injectChatModel(ChatModel model, String modelName) {
        ReflectionTestUtils.setField(adapter, "chatModel", model);
        ReflectionTestUtils.setField(adapter, "currentModel", modelName);
        ReflectionTestUtils.setField(adapter, "initialized", true);
    }

    private boolean hasImageContent(List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            if (!(message instanceof UserMessage userMessage)) {
                continue;
            }
            for (Content content : userMessage.contents()) {
                if (content.type() == ContentType.IMAGE) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasToolAttachmentPlaceholder(List<ChatMessage> messages, String pathFragment) {
        for (ChatMessage message : messages) {
            if (!(message instanceof UserMessage userMessage)) {
                continue;
            }
            for (Content content : userMessage.contents()) {
                if (content.type() == ContentType.TEXT && textOf(content).contains(pathFragment)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String textOf(Content content) {
        if (content instanceof TextContent textContent) {
            return textContent.text();
        }
        return String.valueOf(content);
    }
}
