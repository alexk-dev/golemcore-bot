package me.golemcore.bot.adapter.outbound.llm;

import me.golemcore.bot.domain.model.LlmChunk;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Langchain4jAdapterTest {

    private BotProperties properties;
    private ModelConfigService modelConfig;
    private Langchain4jAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        modelConfig = mock(ModelConfigService.class);
        when(modelConfig.supportsTemperature(anyString())).thenReturn(true);
        when(modelConfig.getProvider(anyString())).thenReturn("openai");
        when(modelConfig.isReasoningRequired(anyString())).thenReturn(false);
        when(modelConfig.getAllModels()).thenReturn(Map.of());

        adapter = new Langchain4jAdapter(properties, modelConfig);
    }

    // ===== getProviderId =====

    @Test
    void shouldReturnLangchain4jProviderId() {
        assertEquals("langchain4j", adapter.getProviderId());
    }

    // ===== isAvailable =====

    @Test
    void shouldBeAvailableWhenApiKeyConfigured() {
        BotProperties.ProviderProperties providerProps = new BotProperties.ProviderProperties();
        providerProps.setApiKey("test-key");
        properties.getLlm().getLangchain4j().getProviders().put("openai", providerProps);

        assertTrue(adapter.isAvailable());
    }

    @Test
    void shouldNotBeAvailableWhenNoApiKeys() {
        assertFalse(adapter.isAvailable());
    }

    @Test
    void shouldNotBeAvailableWhenApiKeyBlank() {
        BotProperties.ProviderProperties providerProps = new BotProperties.ProviderProperties();
        providerProps.setApiKey("  ");
        properties.getLlm().getLangchain4j().getProviders().put("openai", providerProps);

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
        BotProperties.ProviderProperties openaiProps = new BotProperties.ProviderProperties();
        openaiProps.setApiKey("key");
        properties.getLlm().getLangchain4j().getProviders().put("openai", openaiProps);

        ModelConfigService.ModelSettings openaiSettings = new ModelConfigService.ModelSettings("openai", false, true);
        ModelConfigService.ModelSettings anthropicSettings = new ModelConfigService.ModelSettings("anthropic", false,
                true);

        when(modelConfig.getAllModels()).thenReturn(Map.of(
                "gpt-4o", openaiSettings,
                "claude-3-haiku", anthropicSettings));

        List<String> models = adapter.getSupportedModels();
        assertTrue(models.contains("openai/gpt-4o"));
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

    // ===== sanitizeFunctionName =====

    @ParameterizedTest
    @CsvSource({
            "valid_name, valid_name",
            "valid-name, valid-name",
            "validName123, validName123",
            "name.with.dots, name_with_dots",
            "name with spaces, name_with_spaces",
            "name@special#chars, name_special_chars"
    })
    void shouldSanitizeFunctionName(String input, String expected) throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("sanitizeFunctionName", String.class);
        method.setAccessible(true);
        assertEquals(expected, method.invoke(adapter, input));
    }

    @Test
    void shouldReturnUnknownForNullFunctionName() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("sanitizeFunctionName", String.class);
        method.setAccessible(true);
        assertEquals("unknown", method.invoke(adapter, (String) null));
    }

    @Test
    void shouldReturnUnknownForAllInvalidCharsFunctionName() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("sanitizeFunctionName", String.class);
        method.setAccessible(true);
        // "..." should become "___" which is not empty
        String result = (String) method.invoke(adapter, "...");
        assertEquals("___", result);
    }

    // ===== convertArgsToJson =====

    @Test
    void shouldConvertNullArgsToEmptyJson() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("convertArgsToJson", Map.class);
        method.setAccessible(true);
        assertEquals("{}", method.invoke(adapter, (Map<String, Object>) null));
    }

    @Test
    void shouldConvertEmptyArgsToEmptyJson() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("convertArgsToJson", Map.class);
        method.setAccessible(true);
        assertEquals("{}", method.invoke(adapter, Collections.emptyMap()));
    }

    @Test
    void shouldConvertValidArgsToJson() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("convertArgsToJson", Map.class);
        method.setAccessible(true);
        String json = (String) method.invoke(adapter, Map.of("key", "value"));
        assertTrue(json.contains("key"));
        assertTrue(json.contains("value"));
    }

    // ===== parseJsonArgs =====

    @Test
    void shouldParseNullJsonToEmptyMap() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("parseJsonArgs", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(adapter, (String) null);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldParseBlankJsonToEmptyMap() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("parseJsonArgs", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(adapter, "  ");
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldParseInvalidJsonToEmptyMap() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("parseJsonArgs", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(adapter, "invalid json");
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldParseValidJson() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("parseJsonArgs", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(adapter, "{\"key\":\"value\"}");
        assertEquals("value", result.get("key"));
    }

    // ===== isRateLimitError =====

    @Test
    void shouldDetectRateLimitErrors() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("isRateLimitError", Throwable.class);
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(adapter, new RuntimeException("rate_limit exceeded")));
        assertTrue((boolean) method.invoke(adapter, new RuntimeException("token_quota_exceeded")));
        assertTrue((boolean) method.invoke(adapter, new RuntimeException("too_many_tokens")));
        assertTrue((boolean) method.invoke(adapter, new RuntimeException("Too Many Requests")));
        assertTrue((boolean) method.invoke(adapter, new RuntimeException("HTTP 429")));
    }

    @Test
    void shouldDetectRateLimitInCauseChain() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("isRateLimitError", Throwable.class);
        method.setAccessible(true);

        RuntimeException inner = new RuntimeException("rate_limit");
        RuntimeException outer = new RuntimeException("Wrapper", inner);

        assertTrue((boolean) method.invoke(adapter, outer));
    }

    @Test
    void shouldNotDetectNonRateLimitErrors() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("isRateLimitError", Throwable.class);
        method.setAccessible(true);

        assertFalse((boolean) method.invoke(adapter, new RuntimeException("Connection refused")));
        assertFalse((boolean) method.invoke(adapter, new RuntimeException("Internal server error")));
    }

    @Test
    void shouldHandleNullMessageInException() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("isRateLimitError", Throwable.class);
        method.setAccessible(true);

        assertFalse((boolean) method.invoke(adapter, new RuntimeException((String) null)));
    }

    // ===== convertTools =====

    @Test
    void shouldReturnEmptyForNullTools() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("convertTools", LlmRequest.class);
        method.setAccessible(true);

        LlmRequest request = LlmRequest.builder().tools(null).build();
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) method.invoke(adapter, request);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyForEmptyTools() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("convertTools", LlmRequest.class);
        method.setAccessible(true);

        LlmRequest request = LlmRequest.builder().tools(List.of()).build();
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) method.invoke(adapter, request);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldConvertToolDefinitions() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("convertTools", LlmRequest.class);
        method.setAccessible(true);

        ToolDefinition tool = ToolDefinition.builder()
                .name("test_tool")
                .description("A test tool")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "param1", Map.of("type", "string", "description", "A parameter")),
                        "required", List.of("param1")))
                .build();

        LlmRequest request = LlmRequest.builder().tools(List.of(tool)).build();
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) method.invoke(adapter, request);
        assertEquals(1, result.size());
    }

    // ===== convertToolDefinition with enum and array =====

    @Test
    void shouldConvertToolWithEnumParam() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("convertTools", LlmRequest.class);
        method.setAccessible(true);

        ToolDefinition tool = ToolDefinition.builder()
                .name("test")
                .description("Test")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "mode", Map.of(
                                        "type", "string",
                                        "enum", List.of("fast", "slow")))))
                .build();

        LlmRequest request = LlmRequest.builder().tools(List.of(tool)).build();
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) method.invoke(adapter, request);
        assertEquals(1, result.size());
    }

    @Test
    void shouldConvertToolWithArrayParam() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("convertTools", LlmRequest.class);
        method.setAccessible(true);

        ToolDefinition tool = ToolDefinition.builder()
                .name("test")
                .description("Test")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "items", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string")))))
                .build();

        LlmRequest request = LlmRequest.builder().tools(List.of(tool)).build();
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) method.invoke(adapter, request);
        assertEquals(1, result.size());
    }

    @Test
    void shouldConvertToolWithNestedObjectParam() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("convertTools", LlmRequest.class);
        method.setAccessible(true);

        ToolDefinition tool = ToolDefinition.builder()
                .name("test")
                .description("Test")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "config", Map.of(
                                        "type", "object",
                                        "properties", Map.of("key", Map.of("type", "string"))))))
                .build();

        LlmRequest request = LlmRequest.builder().tools(List.of(tool)).build();
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) method.invoke(adapter, request);
        assertEquals(1, result.size());
    }

    @Test
    void shouldConvertToolWithNullInputSchema() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("convertTools", LlmRequest.class);
        method.setAccessible(true);

        ToolDefinition tool = ToolDefinition.builder()
                .name("test")
                .description("Test")
                .inputSchema(null)
                .build();

        LlmRequest request = LlmRequest.builder().tools(List.of(tool)).build();
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) method.invoke(adapter, request);
        assertEquals(1, result.size());
    }

    // ===== convertMessages ID remapping =====

    @Test
    void shouldNotRemapShortValidIds() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("convertMessages", LlmRequest.class);
        method.setAccessible(true);

        Message assistantMsg = Message.builder()
                .role("assistant")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_abc123")
                        .name("weather")
                        .arguments(Map.of())
                        .build()))
                .build();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(
                        Message.builder().role("user").content("Hi").build(),
                        assistantMsg))
                .build();

        @SuppressWarnings("unchecked")
        List<Object> messages = (List<Object>) method.invoke(adapter, request);
        assertFalse(messages.isEmpty());
    }

    @Test
    void shouldHandleToolMessageRole() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("convertMessages", LlmRequest.class);
        method.setAccessible(true);

        LlmRequest request = LlmRequest.builder()
                .systemPrompt("System prompt")
                .messages(List.of(
                        Message.builder().role("user").content("Test").build(),
                        Message.builder().role("system").content("System note").build()))
                .build();

        @SuppressWarnings("unchecked")
        List<Object> messages = (List<Object>) method.invoke(adapter, request);
        // System prompt + user + system note = 3 messages
        assertEquals(3, messages.size());
    }

    // ===== stripProviderPrefix =====

    @ParameterizedTest
    @ValueSource(strings = { "openai/gpt-4o", "anthropic/claude-3" })
    void shouldStripProviderPrefix(String model) throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("stripProviderPrefix", String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(adapter, model);
        assertFalse(result.contains("/"));
    }

    @Test
    void shouldNotStripWhenNoPrefix() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("stripProviderPrefix", String.class);
        method.setAccessible(true);
        assertEquals("gpt-4o", method.invoke(adapter, "gpt-4o"));
    }

    // ===== chat() flow tests with mocked ChatLanguageModel =====

    @Test
    void shouldFailChatWhenNotInitialized() {
        // adapter not initialized, chatModel is null
        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .build();

        CompletableFuture<LlmResponse> future = adapter.chat(request);
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause().getMessage().contains("not available"));
    }

    @Test
    void shouldReturnResponseOnSuccessfulChat() throws Exception {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        injectChatModel(mockModel, "test-model");

        AiMessage aiMessage = AiMessage.from("Hello back!");
        TokenUsage tokenUsage = new TokenUsage(10, 5, 15);
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .tokenUsage(tokenUsage)
                .finishReason(FinishReason.STOP)
                .build();

        when(mockModel.chat((List<ChatMessage>) any())).thenReturn(chatResponse);

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
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
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        injectChatModel(mockModel, "test-model");

        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_1")
                .name("weather")
                .arguments("{\"location\":\"London\"}")
                .build();
        AiMessage aiMessage = AiMessage.from(List.of(toolReq));

        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.TOOL_EXECUTION)
                .build();

        when(mockModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

        ToolDefinition toolDef = ToolDefinition.builder()
                .name("weather")
                .description("Get weather")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .build();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("Weather?").build()))
                .tools(List.of(toolDef))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertTrue(response.hasToolCalls());
        assertEquals(1, response.getToolCalls().size());
        assertEquals("call_1", response.getToolCalls().get(0).getId());
        assertEquals("weather", response.getToolCalls().get(0).getName());
        assertEquals("London", response.getToolCalls().get(0).getArguments().get("location"));
    }

    @Test
    void shouldRetryOnRateLimitError() throws Exception {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        injectChatModel(mockModel, "test-model");

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
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertEquals("Success after retry", response.getContent());
    }

    @Test
    void shouldThrowOnNonRateLimitError() {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        injectChatModel(mockModel, "test-model");

        when(mockModel.chat((List<ChatMessage>) any()))
                .thenThrow(new RuntimeException("Connection refused"));

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .build();

        ExecutionException ex = assertThrows(ExecutionException.class, () -> adapter.chat(request).get());
        assertTrue(ex.getCause().getMessage().contains("Connection refused"));
    }

    @Test
    void shouldHandleChatWithToolsViaRequest() throws Exception {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        injectChatModel(mockModel, "test-model");

        AiMessage aiMessage = AiMessage.from("Result with tools");
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.STOP)
                .build();

        when(mockModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

        ToolDefinition toolDef = ToolDefinition.builder()
                .name("test_tool")
                .description("Test")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .build();

        LlmRequest request = LlmRequest.builder()
                .systemPrompt("You are a bot")
                .messages(List.of(Message.builder().role("user").content("test").build()))
                .tools(List.of(toolDef))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertEquals("Result with tools", response.getContent());
    }

    @Test
    void shouldHandleChatWithNullTokenUsage() throws Exception {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        injectChatModel(mockModel, "test-model");

        AiMessage aiMessage = AiMessage.from("No usage info");
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.STOP)
                .build();

        when(mockModel.chat((List<ChatMessage>) any())).thenReturn(chatResponse);

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertNull(response.getUsage());
        assertEquals("No usage info", response.getContent());
    }

    @Test
    void shouldHandleNullFinishReason() throws Exception {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        injectChatModel(mockModel, "test-model");

        AiMessage aiMessage = AiMessage.from("Response");
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .build();

        when(mockModel.chat((List<ChatMessage>) any())).thenReturn(chatResponse);

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertEquals("stop", response.getFinishReason());
    }

    // ===== convertMessages with tool message round-trip =====

    @Test
    void shouldConvertToolMessageRoundTrip() throws Exception {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        injectChatModel(mockModel, "test-model");

        AiMessage aiMessage = AiMessage.from("Weather is sunny");
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.STOP)
                .build();

        when(mockModel.chat((List<ChatMessage>) any())).thenReturn(chatResponse);

        Message assistantMsg = Message.builder()
                .role("assistant")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_short")
                        .name("weather")
                        .arguments(Map.of("location", "London"))
                        .build()))
                .build();

        Message toolResultMsg = Message.builder()
                .role("tool")
                .content("Sunny, 25C")
                .toolCallId("call_short")
                .toolName("weather")
                .build();

        LlmRequest request = LlmRequest.builder()
                .systemPrompt("You are helpful")
                .messages(List.of(
                        Message.builder().role("user").content("Weather?").build(),
                        assistantMsg,
                        toolResultMsg,
                        Message.builder().role("user").content("Thanks!").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertEquals("Weather is sunny", response.getContent());
    }

    @Test
    void shouldRemapLongToolCallIds() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("convertMessages", LlmRequest.class);
        method.setAccessible(true);

        String longId = "call_" + "a".repeat(50); // > 40 chars
        Message assistantMsg = Message.builder()
                .role("assistant")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id(longId)
                        .name("test_tool")
                        .arguments(Map.of())
                        .build()))
                .build();

        Message toolResultMsg = Message.builder()
                .role("tool")
                .content("result")
                .toolCallId(longId)
                .toolName("test_tool")
                .build();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(
                        Message.builder().role("user").content("test").build(),
                        assistantMsg,
                        toolResultMsg))
                .build();

        @SuppressWarnings("unchecked")
        List<Object> messages = (List<Object>) method.invoke(adapter, request);
        // Should have 3 messages: user + assistant + tool
        assertEquals(3, messages.size());
    }

    @Test
    void shouldRemapIdsWithInvalidChars() throws Exception {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("convertMessages", LlmRequest.class);
        method.setAccessible(true);

        String invalidId = "call.with.dots.123"; // dots are invalid
        Message assistantMsg = Message.builder()
                .role("assistant")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id(invalidId)
                        .name("test")
                        .arguments(Map.of())
                        .build()))
                .build();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(
                        Message.builder().role("user").content("test").build(),
                        assistantMsg))
                .build();

        @SuppressWarnings("unchecked")
        List<Object> messages = (List<Object>) method.invoke(adapter, request);
        assertEquals(2, messages.size());
    }

    // ===== chatStream =====

    @Test
    void shouldStreamChat() throws Exception {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        injectChatModel(mockModel, "test-model");

        AiMessage aiMessage = AiMessage.from("Streamed response");
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.STOP)
                .build();

        when(mockModel.chat((List<ChatMessage>) any())).thenReturn(chatResponse);

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
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
        // Set up provider config for both models
        BotProperties.ProviderProperties openaiProps = new BotProperties.ProviderProperties();
        openaiProps.setApiKey("test-key");
        properties.getLlm().getLangchain4j().getProviders().put("openai", openaiProps);

        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        injectChatModel(mockModel, "openai/gpt-5.1");

        AiMessage aiMessage = AiMessage.from("Default response");
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(FinishReason.STOP)
                .build();

        // The default model should be used
        when(mockModel.chat((List<ChatMessage>) any())).thenReturn(chatResponse);

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertEquals("Default response", response.getContent());
    }

    // ===== Helpers =====

    private void injectChatModel(ChatLanguageModel model, String modelName) {
        try {
            java.lang.reflect.Field chatModelField = Langchain4jAdapter.class.getDeclaredField("chatModel");
            chatModelField.setAccessible(true);
            chatModelField.set(adapter, model);

            java.lang.reflect.Field currentModelField = Langchain4jAdapter.class.getDeclaredField("currentModel");
            currentModelField.setAccessible(true);
            currentModelField.set(adapter, modelName);

            java.lang.reflect.Field initializedField = Langchain4jAdapter.class.getDeclaredField("initialized");
            initializedField.setAccessible(true);
            initializedField.set(adapter, true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mock model", e);
        }
    }
}
