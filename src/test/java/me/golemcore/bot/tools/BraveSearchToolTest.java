package me.golemcore.bot.tools;

import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.http.FeignClientFactory;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BraveSearchToolTest {

    private static final String TEST_KEY = "test-key";
    private static final String QUERY = "query";
    private static final String TEST = "test";
    private static final String RATE_LIMIT_MSG = "Brave Search rate limit exceeded. Please try again later.";
    private static final String ERROR_MSG = "Web search is temporarily unavailable. Please try again later.";

    private BraveSearchTool braveSearchTool;
    private FeignClientFactory feignClientFactory;
    private BotProperties properties;
    private UserPreferencesService userPreferencesService;

    @BeforeEach
    void setUp() {
        feignClientFactory = mock(FeignClientFactory.class);
        properties = new BotProperties();
        userPreferencesService = mock(UserPreferencesService.class);
        when(userPreferencesService.getMessage("tool.brave.rate_limit")).thenReturn(RATE_LIMIT_MSG);
        when(userPreferencesService.getMessage("tool.brave.error")).thenReturn(ERROR_MSG);
    }

    @Test
    void getDefinition_returnsCorrectDefinition() {
        properties.getTools().getBraveSearch().setEnabled(false);
        braveSearchTool = new BraveSearchTool(feignClientFactory, properties, userPreferencesService);
        braveSearchTool.init();

        ToolDefinition definition = braveSearchTool.getDefinition();

        assertEquals("brave_search", definition.getName());
        assertNotNull(definition.getDescription());
        assertNotNull(definition.getInputSchema());
    }

    @Test
    void isEnabled_falseByDefault() {
        braveSearchTool = new BraveSearchTool(feignClientFactory, properties, userPreferencesService);
        braveSearchTool.init();

        assertFalse(braveSearchTool.isEnabled());
    }

    @Test
    void isEnabled_falseWhenEnabledButNoApiKey() {
        properties.getTools().getBraveSearch().setEnabled(true);
        properties.getTools().getBraveSearch().setApiKey("");

        braveSearchTool = new BraveSearchTool(feignClientFactory, properties, userPreferencesService);
        braveSearchTool.init();

        assertFalse(braveSearchTool.isEnabled());
    }

    @Test
    void isEnabled_trueWhenConfigured() {
        properties.getTools().getBraveSearch().setEnabled(true);
        properties.getTools().getBraveSearch().setApiKey(TEST_KEY);

        when(feignClientFactory.create(eq(BraveSearchTool.BraveSearchApi.class), anyString()))
                .thenReturn(mock(BraveSearchTool.BraveSearchApi.class));

        braveSearchTool = new BraveSearchTool(feignClientFactory, properties, userPreferencesService);
        braveSearchTool.init();

        assertTrue(braveSearchTool.isEnabled());
    }

    @Test
    void execute_failsWithNoQuery() throws ExecutionException, InterruptedException {
        properties.getTools().getBraveSearch().setEnabled(false);
        braveSearchTool = new BraveSearchTool(feignClientFactory, properties, userPreferencesService);
        braveSearchTool.init();

        ToolResult result = braveSearchTool.execute(Map.of()).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("required"));
    }

    @Test
    void execute_failsWithBlankQuery() throws ExecutionException, InterruptedException {
        properties.getTools().getBraveSearch().setEnabled(false);
        braveSearchTool = new BraveSearchTool(feignClientFactory, properties, userPreferencesService);
        braveSearchTool.init();

        ToolResult result = braveSearchTool.execute(Map.of(QUERY, "   ")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("required"));
    }

    @Test
    void execute_returnsResults() throws ExecutionException, InterruptedException {
        BraveSearchTool.BraveSearchApi mockApi = createEnabledToolWithMockApi();

        BraveSearchTool.WebResult result1 = new BraveSearchTool.WebResult();
        result1.setTitle("Test Result");
        result1.setUrl("https://example.com");
        result1.setDescription("A test description");

        BraveSearchTool.WebResults webResults = new BraveSearchTool.WebResults();
        webResults.setResults(java.util.List.of(result1));

        BraveSearchTool.BraveSearchResponse response = new BraveSearchTool.BraveSearchResponse();
        response.setWeb(webResults);

        when(mockApi.search(anyString(), anyString(), anyInt())).thenReturn(response);

        ToolResult toolResult = braveSearchTool.execute(Map.of(QUERY, TEST)).get();

        assertTrue(toolResult.isSuccess());
        assertTrue(toolResult.getOutput().contains("Test Result"));
        assertTrue(toolResult.getOutput().contains("https://example.com"));
        assertTrue(toolResult.getOutput().contains("A test description"));
    }

    @Test
    void execute_handlesEmptyResults() throws ExecutionException, InterruptedException {
        BraveSearchTool.BraveSearchApi mockApi = createEnabledToolWithMockApi();

        BraveSearchTool.WebResults webResults = new BraveSearchTool.WebResults();
        webResults.setResults(java.util.List.of());

        BraveSearchTool.BraveSearchResponse response = new BraveSearchTool.BraveSearchResponse();
        response.setWeb(webResults);

        when(mockApi.search(anyString(), anyString(), anyInt())).thenReturn(response);

        ToolResult toolResult = braveSearchTool.execute(Map.of(QUERY, "nothing")).get();

        assertTrue(toolResult.isSuccess());
        assertTrue(toolResult.getOutput().contains("No results"));
    }

    @Test
    void execute_respectsCountParameter() throws ExecutionException, InterruptedException {
        BraveSearchTool.BraveSearchApi mockApi = createEnabledToolWithMockApi();

        BraveSearchTool.BraveSearchResponse response = new BraveSearchTool.BraveSearchResponse();
        response.setWeb(new BraveSearchTool.WebResults());
        response.getWeb().setResults(java.util.List.of());

        when(mockApi.search(anyString(), anyString(), anyInt())).thenReturn(response);

        braveSearchTool.execute(Map.of(QUERY, TEST, "count", 3)).get();

        verify(mockApi).search(TEST_KEY, TEST, 3);
    }

    @Test
    void execute_clampsCountToValidRange() throws ExecutionException, InterruptedException {
        BraveSearchTool.BraveSearchApi mockApi = createEnabledToolWithMockApi();

        BraveSearchTool.BraveSearchResponse response = new BraveSearchTool.BraveSearchResponse();
        response.setWeb(new BraveSearchTool.WebResults());
        response.getWeb().setResults(java.util.List.of());

        when(mockApi.search(anyString(), anyString(), anyInt())).thenReturn(response);

        braveSearchTool.execute(Map.of(QUERY, TEST, "count", 50)).get();
        verify(mockApi).search(TEST_KEY, TEST, 20);
    }

    @Test
    void execute_handlesNonRateLimitApiException() throws ExecutionException, InterruptedException {
        BraveSearchTool.BraveSearchApi mockApi = createEnabledToolWithMockApi();
        when(mockApi.search(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("API error"));

        ToolResult toolResult = braveSearchTool.execute(Map.of(QUERY, TEST)).get();

        assertFalse(toolResult.isSuccess());
        assertEquals(ERROR_MSG, toolResult.getError());
        verify(mockApi, times(1)).search(anyString(), anyString(), anyInt());
    }

    @Test
    void execute_retriesOnRateLimitAndSucceeds() throws ExecutionException, InterruptedException {
        BraveSearchTool.BraveSearchApi mockApi = createEnabledToolWithMockApi();

        BraveSearchTool.WebResult result1 = new BraveSearchTool.WebResult();
        result1.setTitle("Success");
        result1.setUrl("https://example.com");
        result1.setDescription("Found after retry");

        BraveSearchTool.WebResults webResults = new BraveSearchTool.WebResults();
        webResults.setResults(java.util.List.of(result1));

        BraveSearchTool.BraveSearchResponse response = new BraveSearchTool.BraveSearchResponse();
        response.setWeb(webResults);

        when(mockApi.search(anyString(), anyString(), anyInt()))
                .thenThrow(feignException429())
                .thenReturn(response);

        ToolResult toolResult = braveSearchTool.execute(Map.of(QUERY, TEST)).get();

        assertTrue(toolResult.isSuccess());
        assertTrue(toolResult.getOutput().contains("Success"));
        verify(mockApi, times(2)).search(anyString(), anyString(), anyInt());
    }

    @Test
    void execute_returnsRateLimitMessageAfterRetriesExhausted() throws ExecutionException, InterruptedException {
        BraveSearchTool.BraveSearchApi mockApi = createEnabledToolWithMockApi();
        when(mockApi.search(anyString(), anyString(), anyInt()))
                .thenThrow(feignException429());

        ToolResult toolResult = braveSearchTool.execute(Map.of(QUERY, TEST)).get();

        assertFalse(toolResult.isSuccess());
        assertEquals(RATE_LIMIT_MSG, toolResult.getError());
        // 1 initial + 3 retries = 4 total calls
        verify(mockApi, times(4)).search(anyString(), anyString(), anyInt());
    }

    @Test
    void execute_doesNotRetryOnNon429FeignException() throws ExecutionException, InterruptedException {
        BraveSearchTool.BraveSearchApi mockApi = createEnabledToolWithMockApi();
        when(mockApi.search(anyString(), anyString(), anyInt()))
                .thenThrow(feignException(500));

        ToolResult toolResult = braveSearchTool.execute(Map.of(QUERY, TEST)).get();

        assertFalse(toolResult.isSuccess());
        assertEquals(ERROR_MSG, toolResult.getError());
        verify(mockApi, times(1)).search(anyString(), anyString(), anyInt());
    }

    private BraveSearchTool.BraveSearchApi createEnabledToolWithMockApi() {
        properties.getTools().getBraveSearch().setEnabled(true);
        properties.getTools().getBraveSearch().setApiKey(TEST_KEY);

        BraveSearchTool.BraveSearchApi mockApi = mock(BraveSearchTool.BraveSearchApi.class);
        when(feignClientFactory.create(eq(BraveSearchTool.BraveSearchApi.class), anyString()))
                .thenReturn(mockApi);

        braveSearchTool = new BraveSearchTool(feignClientFactory, properties, userPreferencesService);
        braveSearchTool.init();
        return mockApi;
    }

    private FeignException feignException429() {
        return feignException(429);
    }

    private FeignException feignException(int status) {
        Request request = Request.create(
                Request.HttpMethod.GET, "https://api.search.brave.com/res/v1/web/search",
                Collections.emptyMap(), null, null, null);
        return FeignException.errorStatus("search", feign.Response.builder()
                .status(status)
                .reason("Error")
                .request(request)
                .headers(Collections.emptyMap())
                .build());
    }
}
