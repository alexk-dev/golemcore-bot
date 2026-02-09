package me.golemcore.bot.adapter.outbound.llm;

import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.infrastructure.http.FeignClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomLlmAdapterTest {

    private BotProperties properties;
    private FeignClientFactory feignClientFactory;
    private ModelConfigService modelConfig;
    private CustomLlmAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        feignClientFactory = mock(FeignClientFactory.class);
        modelConfig = mock(ModelConfigService.class);
        when(modelConfig.supportsTemperature(anyString())).thenReturn(true);

        adapter = new CustomLlmAdapter(properties, feignClientFactory, modelConfig);
    }

    // ===== getProviderId =====

    @Test
    void shouldReturnCustomProviderId() {
        assertEquals("custom", adapter.getProviderId());
    }

    // ===== isAvailable =====

    @Test
    void shouldBeAvailableWhenApiUrlAndKeyConfigured() {
        properties.getLlm().getCustom().setApiUrl("https://api.example.com");
        properties.getLlm().getCustom().setApiKey("test-key");
        assertTrue(adapter.isAvailable());
    }

    @Test
    void shouldNotBeAvailableWhenApiUrlMissing() {
        properties.getLlm().getCustom().setApiUrl(null);
        properties.getLlm().getCustom().setApiKey("test-key");
        assertFalse(adapter.isAvailable());
    }

    @Test
    void shouldNotBeAvailableWhenApiUrlBlank() {
        properties.getLlm().getCustom().setApiUrl("  ");
        properties.getLlm().getCustom().setApiKey("test-key");
        assertFalse(adapter.isAvailable());
    }

    @Test
    void shouldNotBeAvailableWhenApiKeyMissing() {
        properties.getLlm().getCustom().setApiUrl("https://api.example.com");
        properties.getLlm().getCustom().setApiKey(null);
        assertFalse(adapter.isAvailable());
    }

    @Test
    void shouldNotBeAvailableWhenApiKeyBlank() {
        properties.getLlm().getCustom().setApiUrl("https://api.example.com");
        properties.getLlm().getCustom().setApiKey("  ");
        assertFalse(adapter.isAvailable());
    }

    // ===== supportsStreaming =====

    @Test
    void shouldNotSupportStreaming() {
        assertFalse(adapter.supportsStreaming());
    }

    // ===== getSupportedModels =====

    @Test
    void shouldReturnCurrentModelInSupportedModels() {
        properties.getRouter().setDefaultModel("test-model");
        properties.getLlm().getCustom().setApiUrl("https://api.example.com");
        properties.getLlm().getCustom().setApiKey("key");

        adapter.initialize();

        List<String> models = adapter.getSupportedModels();
        assertEquals(1, models.size());
        assertEquals("test-model", models.get(0));
    }

    @Test
    void shouldReturnCustomWhenNoModelConfigured() {
        // Before initialization, currentModel is null
        List<String> models = adapter.getSupportedModels();
        assertEquals(1, models.size());
        assertEquals("custom", models.get(0));
    }

    // ===== getCurrentModel =====

    @Test
    void shouldReturnNullBeforeInit() {
        assertNull(adapter.getCurrentModel());
    }

    @Test
    void shouldReturnModelAfterInit() {
        properties.getRouter().setDefaultModel("my-model");
        properties.getLlm().getCustom().setApiUrl("https://api.example.com");
        properties.getLlm().getCustom().setApiKey("key");

        CustomLlmAdapter.CustomLlmApi mockApi = mock(CustomLlmAdapter.CustomLlmApi.class);
        when(feignClientFactory.create(CustomLlmAdapter.CustomLlmApi.class, "https://api.example.com"))
                .thenReturn(mockApi);

        adapter.initialize();
        assertEquals("my-model", adapter.getCurrentModel());
    }

    // ===== initialize =====

    @Test
    void shouldInitializeOnlyOnce() {
        properties.getRouter().setDefaultModel("model");
        properties.getLlm().getCustom().setApiUrl("https://api.example.com");
        properties.getLlm().getCustom().setApiKey("key");

        when(feignClientFactory.create(CustomLlmAdapter.CustomLlmApi.class, "https://api.example.com"))
                .thenReturn(mock(CustomLlmAdapter.CustomLlmApi.class));

        adapter.initialize();
        adapter.initialize(); // Second call should be no-op

        verify(feignClientFactory, times(1)).create(any(), anyString());
    }

    @Test
    void shouldHandleInitFailureGracefully() {
        properties.getLlm().getCustom().setApiUrl("https://api.example.com");
        properties.getLlm().getCustom().setApiKey("key");

        when(feignClientFactory.create(any(), anyString())).thenThrow(new RuntimeException("Connection failed"));

        assertDoesNotThrow(() -> adapter.initialize());
    }

    @Test
    void shouldNotInitializeWhenUrlBlank() {
        properties.getLlm().getCustom().setApiUrl("");
        properties.getLlm().getCustom().setApiKey("key");

        adapter.initialize();

        verify(feignClientFactory, never()).create(any(), anyString());
    }

    // ===== chat - no client =====

    @Test
    void shouldFailChatWhenNotInitialized() {
        properties.getLlm().getCustom().setApiUrl(null);
        LlmRequest request = LlmRequest.builder().messages(List.of()).build();

        CompletableFuture<LlmResponse> future = adapter.chat(request);

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause().getMessage().contains("not available"));
    }

    // ===== chat - successful =====

    @Test
    void shouldReturnResponseOnSuccessfulChat() throws Exception {
        setupInitializedAdapter();

        CustomLlmAdapter.CustomLlmApi client = getInjectedClient();

        CustomLlmAdapter.ApiMessage apiMsg = new CustomLlmAdapter.ApiMessage();
        apiMsg.setRole("assistant");
        apiMsg.setContent("Hello!");

        CustomLlmAdapter.ChatChoice choice = new CustomLlmAdapter.ChatChoice();
        choice.setMessage(apiMsg);
        choice.setFinishReason("stop");

        CustomLlmAdapter.ApiUsage apiUsage = new CustomLlmAdapter.ApiUsage();
        apiUsage.setPromptTokens(10);
        apiUsage.setCompletionTokens(5);
        apiUsage.setTotalTokens(15);

        CustomLlmAdapter.ChatCompletionResponse apiResponse = new CustomLlmAdapter.ChatCompletionResponse();
        apiResponse.setChoices(List.of(choice));
        apiResponse.setUsage(apiUsage);
        apiResponse.setModel("test-model");

        when(client.chatCompletion(anyString(), any())).thenReturn(apiResponse);

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();

        assertEquals("Hello!", response.getContent());
        assertEquals("stop", response.getFinishReason());
        assertNotNull(response.getUsage());
        assertEquals(10, response.getUsage().getInputTokens());
        assertEquals(5, response.getUsage().getOutputTokens());
    }

    // ===== chat - empty choices =====

    @Test
    void shouldReturnEmptyContentOnNoChoices() throws Exception {
        setupInitializedAdapter();
        CustomLlmAdapter.CustomLlmApi client = getInjectedClient();

        CustomLlmAdapter.ChatCompletionResponse apiResponse = new CustomLlmAdapter.ChatCompletionResponse();
        apiResponse.setChoices(List.of());

        when(client.chatCompletion(anyString(), any())).thenReturn(apiResponse);

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertEquals("", response.getContent());
        assertEquals("error", response.getFinishReason());
    }

    @Test
    void shouldReturnEmptyContentOnNullChoices() throws Exception {
        setupInitializedAdapter();
        CustomLlmAdapter.CustomLlmApi client = getInjectedClient();

        CustomLlmAdapter.ChatCompletionResponse apiResponse = new CustomLlmAdapter.ChatCompletionResponse();
        apiResponse.setChoices(null);

        when(client.chatCompletion(anyString(), any())).thenReturn(apiResponse);

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertEquals("", response.getContent());
    }

    // ===== chat with tool calls =====

    @Test
    void shouldConvertToolCallsInResponse() throws Exception {
        setupInitializedAdapter();
        CustomLlmAdapter.CustomLlmApi client = getInjectedClient();

        CustomLlmAdapter.ApiFunction func = new CustomLlmAdapter.ApiFunction();
        func.setName("weather");
        func.setArguments("{\"location\":\"London\"}");

        CustomLlmAdapter.ApiToolCall toolCall = new CustomLlmAdapter.ApiToolCall();
        toolCall.setId("call_123");
        toolCall.setType("function");
        toolCall.setFunction(func);

        CustomLlmAdapter.ApiMessage apiMsg = new CustomLlmAdapter.ApiMessage();
        apiMsg.setRole("assistant");
        apiMsg.setContent(null);
        apiMsg.setToolCalls(List.of(toolCall));

        CustomLlmAdapter.ChatChoice choice = new CustomLlmAdapter.ChatChoice();
        choice.setMessage(apiMsg);
        choice.setFinishReason("tool_calls");

        CustomLlmAdapter.ChatCompletionResponse apiResponse = new CustomLlmAdapter.ChatCompletionResponse();
        apiResponse.setChoices(List.of(choice));

        when(client.chatCompletion(anyString(), any())).thenReturn(apiResponse);

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("Weather in London").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertNotNull(response.getToolCalls());
        assertEquals(1, response.getToolCalls().size());
        assertEquals("call_123", response.getToolCalls().get(0).getId());
        assertEquals("weather", response.getToolCalls().get(0).getName());
        assertEquals("London", response.getToolCalls().get(0).getArguments().get("location"));
    }

    // ===== buildRequest with tools =====

    @Test
    void shouldBuildRequestWithTools() throws Exception {
        setupInitializedAdapter();
        CustomLlmAdapter.CustomLlmApi client = getInjectedClient();

        // Make the actual call to verify tool definitions are included
        CustomLlmAdapter.ApiMessage apiMsg = new CustomLlmAdapter.ApiMessage();
        apiMsg.setRole("assistant");
        apiMsg.setContent("Result");

        CustomLlmAdapter.ChatChoice choice = new CustomLlmAdapter.ChatChoice();
        choice.setMessage(apiMsg);
        choice.setFinishReason("stop");

        CustomLlmAdapter.ChatCompletionResponse apiResponse = new CustomLlmAdapter.ChatCompletionResponse();
        apiResponse.setChoices(List.of(choice));

        when(client.chatCompletion(anyString(), any())).thenReturn(apiResponse);

        ToolDefinition toolDef = ToolDefinition.builder()
                .name("test_tool")
                .description("A test tool")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .build();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("test").build()))
                .tools(List.of(toolDef))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertNotNull(response.getContent());
    }

    // ===== buildRequest with system prompt and tool messages =====

    @Test
    void shouldBuildRequestWithSystemPromptAndToolMessages() throws Exception {
        setupInitializedAdapter();
        CustomLlmAdapter.CustomLlmApi client = getInjectedClient();

        CustomLlmAdapter.ApiMessage apiMsg = new CustomLlmAdapter.ApiMessage();
        apiMsg.setRole("assistant");
        apiMsg.setContent("Done");

        CustomLlmAdapter.ChatChoice choice = new CustomLlmAdapter.ChatChoice();
        choice.setMessage(apiMsg);
        choice.setFinishReason("stop");

        CustomLlmAdapter.ChatCompletionResponse apiResponse = new CustomLlmAdapter.ChatCompletionResponse();
        apiResponse.setChoices(List.of(choice));

        when(client.chatCompletion(anyString(), any())).thenReturn(apiResponse);

        Message assistantMsg = Message.builder()
                .role("assistant")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_1")
                        .name("weather")
                        .arguments(Map.of("location", "Tokyo"))
                        .build()))
                .build();

        Message toolResultMsg = Message.builder()
                .role("tool")
                .content("Sunny, 25C")
                .toolCallId("call_1")
                .toolName("weather")
                .build();

        LlmRequest request = LlmRequest.builder()
                .systemPrompt("You are a helpful bot")
                .messages(List.of(
                        Message.builder().role("user").content("What's the weather?").build(),
                        assistantMsg,
                        toolResultMsg))
                .build();

        LlmResponse response = adapter.chat(request).get();
        assertNotNull(response.getContent());
    }

    // ===== convertArgsToJson =====

    @Test
    void shouldConvertNullArgsToEmptyJson() throws Exception {
        Method method = CustomLlmAdapter.class.getDeclaredMethod("convertArgsToJson", Map.class);
        method.setAccessible(true);
        assertEquals("{}", method.invoke(adapter, (Map<String, Object>) null));
    }

    @Test
    void shouldConvertEmptyArgsToEmptyJson() throws Exception {
        Method method = CustomLlmAdapter.class.getDeclaredMethod("convertArgsToJson", Map.class);
        method.setAccessible(true);
        assertEquals("{}", method.invoke(adapter, Collections.emptyMap()));
    }

    // ===== parseJsonArgs =====

    @Test
    void shouldParseNullJsonToEmptyMap() throws Exception {
        Method method = CustomLlmAdapter.class.getDeclaredMethod("parseJsonArgs", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(adapter, (String) null);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldParseBlankJsonToEmptyMap() throws Exception {
        Method method = CustomLlmAdapter.class.getDeclaredMethod("parseJsonArgs", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(adapter, "  ");
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldParseInvalidJsonToEmptyMap() throws Exception {
        Method method = CustomLlmAdapter.class.getDeclaredMethod("parseJsonArgs", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(adapter, "not json");
        assertTrue(result.isEmpty());
    }

    // ===== getLlmPort =====

    @Test
    void shouldReturnSelfAsLlmPort() {
        assertSame(adapter, adapter.getLlmPort());
    }

    // ===== Helpers =====

    private void setupInitializedAdapter() throws Exception {
        properties.getRouter().setDefaultModel("test-model");
        properties.getLlm().getCustom().setApiUrl("https://api.example.com");
        properties.getLlm().getCustom().setApiKey("test-key");

        CustomLlmAdapter.CustomLlmApi mockApi = mock(CustomLlmAdapter.CustomLlmApi.class);
        when(feignClientFactory.create(CustomLlmAdapter.CustomLlmApi.class, "https://api.example.com"))
                .thenReturn(mockApi);

        adapter.initialize();
    }

    private CustomLlmAdapter.CustomLlmApi getInjectedClient() throws Exception {
        Field clientField = CustomLlmAdapter.class.getDeclaredField("client");
        clientField.setAccessible(true);
        return (CustomLlmAdapter.CustomLlmApi) clientField.get(adapter);
    }
}
