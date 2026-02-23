package me.golemcore.bot.plugin.builtin.rag.adapter;

import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.testsupport.http.OkHttpMockEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LightRagAdapterTest {

    private static final String QUERY_MODE_HYBRID = "hybrid";
    private static final String TEST_QUERY = "test";
    private static final String RAG_BASE_URL = "http://mock.rag.local";

    private OkHttpMockEngine httpEngine;
    private LightRagAdapter adapter;
    private RuntimeConfigService runtimeConfigService;

    @BeforeEach
    void setUp() {
        httpEngine = new OkHttpMockEngine();
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isRagEnabled()).thenReturn(true);
        when(runtimeConfigService.getRagUrl()).thenReturn(RAG_BASE_URL);
        when(runtimeConfigService.getRagTimeoutSeconds()).thenReturn(5);
        when(runtimeConfigService.getRagApiKey()).thenReturn(null);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(httpEngine)
                .build();
        adapter = new LightRagAdapter(runtimeConfigService, client, new ObjectMapper());
    }

    @Test
    void queryReturnsResponse() throws Exception {
        httpEngine.enqueueJson(200, "{\"response\": \"LightRAG found relevant info\"}");

        String result = adapter.query("test query", QUERY_MODE_HYBRID).get();
        assertEquals("LightRAG found relevant info", result);

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertTrue(request.target().endsWith("/query"));
        String body = request.body();
        assertTrue(body.contains("\"query\":\"test query\""));
        assertTrue(body.contains("\"mode\":\"hybrid\""));
    }

    @Test
    void queryReturnsEmptyOnError() throws Exception {
        httpEngine.enqueueJson(500, "");

        String result = adapter.query(TEST_QUERY, QUERY_MODE_HYBRID).get();
        assertEquals("", result);
    }

    @Test
    void queryReturnsEmptyWhenDisabled() throws Exception {
        when(runtimeConfigService.isRagEnabled()).thenReturn(false);
        String result = adapter.query(TEST_QUERY, QUERY_MODE_HYBRID).get();
        assertEquals("", result);
    }

    @Test
    void queryHandlesPlainTextResponse() throws Exception {
        httpEngine.enqueueText(200, "plain text response", "text/plain");

        String result = adapter.query(TEST_QUERY, QUERY_MODE_HYBRID).get();
        assertEquals("plain text response", result);
    }

    @Test
    void indexSendsCorrectRequest() throws Exception {
        httpEngine.enqueueJson(200, "");

        adapter.index("some document text").get();

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertTrue(request.target().endsWith("/documents/text"));
        String body = request.body();
        assertTrue(body.contains("\"text\":\"some document text\""));
        assertTrue(body.contains("\"file_source\":\"conv_"), "should include file_source");
    }

    @Test
    void indexDoesNothingWhenDisabled() throws Exception {
        when(runtimeConfigService.isRagEnabled()).thenReturn(false);
        // Should complete without making any request
        adapter.index(TEST_QUERY).get();
        assertEquals(0, httpEngine.getRequestCount());
    }

    @Test
    void isAvailableReflectsEnabledFlag() {
        when(runtimeConfigService.isRagEnabled()).thenReturn(true);
        assertTrue(adapter.isAvailable());

        when(runtimeConfigService.isRagEnabled()).thenReturn(false);
        assertFalse(adapter.isAvailable());
    }

    @Test
    void isHealthyReturnsTrueOnSuccess() {
        httpEngine.enqueueJson(200, "");
        assertTrue(adapter.isHealthy());
    }

    @Test
    void isHealthyReturnsFalseOnError() {
        httpEngine.enqueueJson(503, "");
        assertFalse(adapter.isHealthy());
    }

    @Test
    void isHealthyReturnsFalseWhenDisabled() {
        when(runtimeConfigService.isRagEnabled()).thenReturn(false);
        assertFalse(adapter.isHealthy());
    }

    @Test
    void apiKeyHeaderSentWhenConfigured() throws Exception {
        when(runtimeConfigService.getRagApiKey()).thenReturn("test-api-key");
        // Recreate adapter to pick up API key
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(httpEngine)
                .build();
        adapter = new LightRagAdapter(runtimeConfigService, client, new ObjectMapper());

        httpEngine.enqueueJson(200, "{\"response\": \"ok\"}");

        adapter.query(TEST_QUERY, QUERY_MODE_HYBRID).get();

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        assertEquals("Bearer test-api-key", request.headers().get("Authorization"));
    }

    @Test
    void noAuthHeaderWhenApiKeyEmpty() throws Exception {
        when(runtimeConfigService.getRagApiKey()).thenReturn("");

        httpEngine.enqueueJson(200, "{\"response\": \"ok\"}");

        adapter.query(TEST_QUERY, QUERY_MODE_HYBRID).get();

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        assertNull(request.headers().get("Authorization"));
    }
}
