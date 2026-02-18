package me.golemcore.bot.adapter.outbound.rag;

import me.golemcore.bot.domain.service.RuntimeConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LightRagAdapterTest {

    private static final String QUERY_MODE_HYBRID = "hybrid";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String TEST_QUERY = "test";

    private MockWebServer mockServer;
    private LightRagAdapter adapter;
    private RuntimeConfigService runtimeConfigService;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        String baseUrl = mockServer.url("").toString().replaceAll("/$", "");

        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isRagEnabled()).thenReturn(true);
        when(runtimeConfigService.getRagUrl()).thenReturn(baseUrl);
        when(runtimeConfigService.getRagTimeoutSeconds()).thenReturn(5);
        when(runtimeConfigService.getRagApiKey()).thenReturn(null);

        OkHttpClient client = new OkHttpClient();
        adapter = new LightRagAdapter(runtimeConfigService, client, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.close();
    }

    @Test
    void queryReturnsResponse() throws Exception {
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"response\": \"LightRAG found relevant info\"}")
                .setHeader(CONTENT_TYPE, APPLICATION_JSON).build());

        String result = adapter.query("test query", QUERY_MODE_HYBRID).get();
        assertEquals("LightRAG found relevant info", result);

        RecordedRequest request = mockServer.takeRequest();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getTarget().endsWith("/query"));
        String body = request.getBody().utf8();
        assertTrue(body.contains("\"query\":\"test query\""));
        assertTrue(body.contains("\"mode\":\"hybrid\""));
    }

    @Test
    void queryReturnsEmptyOnError() throws Exception {
        mockServer.enqueue(new MockResponse.Builder().code(500).build());

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
        mockServer.enqueue(new MockResponse.Builder()
                .body("plain text response")
                .setHeader(CONTENT_TYPE, "text/plain").build());

        String result = adapter.query(TEST_QUERY, QUERY_MODE_HYBRID).get();
        assertEquals("plain text response", result);
    }

    @Test
    void indexSendsCorrectRequest() throws Exception {
        mockServer.enqueue(new MockResponse.Builder().code(200).build());

        adapter.index("some document text").get();

        RecordedRequest request = mockServer.takeRequest();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getTarget().endsWith("/documents/text"));
        String body = request.getBody().utf8();
        assertTrue(body.contains("\"text\":\"some document text\""));
        assertTrue(body.contains("\"file_source\":\"conv_"), "should include file_source");
    }

    @Test
    void indexDoesNothingWhenDisabled() throws Exception {
        when(runtimeConfigService.isRagEnabled()).thenReturn(false);
        // Should complete without making any request
        adapter.index(TEST_QUERY).get();
        assertEquals(0, mockServer.getRequestCount());
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
        mockServer.enqueue(new MockResponse.Builder().code(200).build());
        assertTrue(adapter.isHealthy());
    }

    @Test
    void isHealthyReturnsFalseOnError() {
        mockServer.enqueue(new MockResponse.Builder().code(503).build());
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
        OkHttpClient client = new OkHttpClient();
        adapter = new LightRagAdapter(runtimeConfigService, client, new ObjectMapper());

        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"response\": \"ok\"}")
                .setHeader(CONTENT_TYPE, APPLICATION_JSON).build());

        adapter.query(TEST_QUERY, QUERY_MODE_HYBRID).get();

        RecordedRequest request = mockServer.takeRequest();
        assertEquals("Bearer test-api-key", request.getHeaders().get("Authorization"));
    }

    @Test
    void noAuthHeaderWhenApiKeyEmpty() throws Exception {
        when(runtimeConfigService.getRagApiKey()).thenReturn("");

        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"response\": \"ok\"}")
                .setHeader(CONTENT_TYPE, APPLICATION_JSON).build());

        adapter.query(TEST_QUERY, QUERY_MODE_HYBRID).get();

        RecordedRequest request = mockServer.takeRequest();
        assertNull(request.getHeaders().get("Authorization"));
    }
}
