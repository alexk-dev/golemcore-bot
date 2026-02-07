package me.golemcore.bot.tools;

import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.http.FeignClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BraveSearchToolTest {

    private BraveSearchTool braveSearchTool;
    private FeignClientFactory feignClientFactory;
    private BotProperties properties;

    @BeforeEach
    void setUp() {
        feignClientFactory = mock(FeignClientFactory.class);
        properties = new BotProperties();
    }

    @Test
    void getDefinition_returnsCorrectDefinition() {
        properties.getTools().getBraveSearch().setEnabled(false);
        braveSearchTool = new BraveSearchTool(feignClientFactory, properties);
        braveSearchTool.init();

        ToolDefinition definition = braveSearchTool.getDefinition();

        assertEquals("brave_search", definition.getName());
        assertNotNull(definition.getDescription());
        assertNotNull(definition.getInputSchema());
    }

    @Test
    void isEnabled_falseByDefault() {
        braveSearchTool = new BraveSearchTool(feignClientFactory, properties);
        braveSearchTool.init();

        assertFalse(braveSearchTool.isEnabled());
    }

    @Test
    void isEnabled_falseWhenEnabledButNoApiKey() {
        properties.getTools().getBraveSearch().setEnabled(true);
        properties.getTools().getBraveSearch().setApiKey("");

        braveSearchTool = new BraveSearchTool(feignClientFactory, properties);
        braveSearchTool.init();

        assertFalse(braveSearchTool.isEnabled());
    }

    @Test
    void isEnabled_trueWhenConfigured() {
        properties.getTools().getBraveSearch().setEnabled(true);
        properties.getTools().getBraveSearch().setApiKey("test-key");

        // Mock Feign client creation
        when(feignClientFactory.create(eq(BraveSearchTool.BraveSearchApi.class), anyString()))
                .thenReturn(mock(BraveSearchTool.BraveSearchApi.class));

        braveSearchTool = new BraveSearchTool(feignClientFactory, properties);
        braveSearchTool.init();

        assertTrue(braveSearchTool.isEnabled());
    }

    @Test
    void execute_failsWithNoQuery() throws ExecutionException, InterruptedException {
        properties.getTools().getBraveSearch().setEnabled(false);
        braveSearchTool = new BraveSearchTool(feignClientFactory, properties);
        braveSearchTool.init();

        ToolResult result = braveSearchTool.execute(Map.of()).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("required"));
    }

    @Test
    void execute_failsWithBlankQuery() throws ExecutionException, InterruptedException {
        properties.getTools().getBraveSearch().setEnabled(false);
        braveSearchTool = new BraveSearchTool(feignClientFactory, properties);
        braveSearchTool.init();

        ToolResult result = braveSearchTool.execute(Map.of("query", "   ")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("required"));
    }

    @Test
    void execute_returnsResults() throws ExecutionException, InterruptedException {
        properties.getTools().getBraveSearch().setEnabled(true);
        properties.getTools().getBraveSearch().setApiKey("test-key");

        // Build mock response
        var result1 = new BraveSearchTool.WebResult();
        result1.setTitle("Test Result");
        result1.setUrl("https://example.com");
        result1.setDescription("A test description");

        var webResults = new BraveSearchTool.WebResults();
        webResults.setResults(java.util.List.of(result1));

        var response = new BraveSearchTool.BraveSearchResponse();
        response.setWeb(webResults);

        var mockApi = mock(BraveSearchTool.BraveSearchApi.class);
        when(mockApi.search(anyString(), anyString(), anyInt())).thenReturn(response);
        when(feignClientFactory.create(eq(BraveSearchTool.BraveSearchApi.class), anyString()))
                .thenReturn(mockApi);

        braveSearchTool = new BraveSearchTool(feignClientFactory, properties);
        braveSearchTool.init();

        ToolResult toolResult = braveSearchTool.execute(Map.of("query", "test")).get();

        assertTrue(toolResult.isSuccess());
        assertTrue(toolResult.getOutput().contains("Test Result"));
        assertTrue(toolResult.getOutput().contains("https://example.com"));
        assertTrue(toolResult.getOutput().contains("A test description"));
    }

    @Test
    void execute_handlesEmptyResults() throws ExecutionException, InterruptedException {
        properties.getTools().getBraveSearch().setEnabled(true);
        properties.getTools().getBraveSearch().setApiKey("test-key");

        var webResults = new BraveSearchTool.WebResults();
        webResults.setResults(java.util.List.of());

        var response = new BraveSearchTool.BraveSearchResponse();
        response.setWeb(webResults);

        var mockApi = mock(BraveSearchTool.BraveSearchApi.class);
        when(mockApi.search(anyString(), anyString(), anyInt())).thenReturn(response);
        when(feignClientFactory.create(eq(BraveSearchTool.BraveSearchApi.class), anyString()))
                .thenReturn(mockApi);

        braveSearchTool = new BraveSearchTool(feignClientFactory, properties);
        braveSearchTool.init();

        ToolResult toolResult = braveSearchTool.execute(Map.of("query", "nothing")).get();

        assertTrue(toolResult.isSuccess());
        assertTrue(toolResult.getOutput().contains("No results"));
    }

    @Test
    void execute_respectsCountParameter() throws ExecutionException, InterruptedException {
        properties.getTools().getBraveSearch().setEnabled(true);
        properties.getTools().getBraveSearch().setApiKey("test-key");

        var response = new BraveSearchTool.BraveSearchResponse();
        response.setWeb(new BraveSearchTool.WebResults());
        response.getWeb().setResults(java.util.List.of());

        var mockApi = mock(BraveSearchTool.BraveSearchApi.class);
        when(mockApi.search(anyString(), anyString(), anyInt())).thenReturn(response);
        when(feignClientFactory.create(eq(BraveSearchTool.BraveSearchApi.class), anyString()))
                .thenReturn(mockApi);

        braveSearchTool = new BraveSearchTool(feignClientFactory, properties);
        braveSearchTool.init();

        braveSearchTool.execute(Map.of("query", "test", "count", 3)).get();

        verify(mockApi).search("test-key", "test", 3);
    }

    @Test
    void execute_clampsCountToValidRange() throws ExecutionException, InterruptedException {
        properties.getTools().getBraveSearch().setEnabled(true);
        properties.getTools().getBraveSearch().setApiKey("test-key");

        var response = new BraveSearchTool.BraveSearchResponse();
        response.setWeb(new BraveSearchTool.WebResults());
        response.getWeb().setResults(java.util.List.of());

        var mockApi = mock(BraveSearchTool.BraveSearchApi.class);
        when(mockApi.search(anyString(), anyString(), anyInt())).thenReturn(response);
        when(feignClientFactory.create(eq(BraveSearchTool.BraveSearchApi.class), anyString()))
                .thenReturn(mockApi);

        braveSearchTool = new BraveSearchTool(feignClientFactory, properties);
        braveSearchTool.init();

        // count > 20 should be clamped to 20
        braveSearchTool.execute(Map.of("query", "test", "count", 50)).get();
        verify(mockApi).search("test-key", "test", 20);
    }

    @Test
    void execute_handlesApiException() throws ExecutionException, InterruptedException {
        properties.getTools().getBraveSearch().setEnabled(true);
        properties.getTools().getBraveSearch().setApiKey("test-key");

        var mockApi = mock(BraveSearchTool.BraveSearchApi.class);
        when(mockApi.search(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("API error"));
        when(feignClientFactory.create(eq(BraveSearchTool.BraveSearchApi.class), anyString()))
                .thenReturn(mockApi);

        braveSearchTool = new BraveSearchTool(feignClientFactory, properties);
        braveSearchTool.init();

        ToolResult toolResult = braveSearchTool.execute(Map.of("query", "test")).get();

        assertFalse(toolResult.isSuccess());
        assertTrue(toolResult.getError().contains("Search failed"));
    }
}
