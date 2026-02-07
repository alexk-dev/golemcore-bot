package me.golemcore.bot.adapter.outbound.rag;

import me.golemcore.bot.infrastructure.config.BotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class LightRagAdapterTest {

    private MockWebServer mockServer;
    private LightRagAdapter adapter;
    private BotProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        properties = new BotProperties();
        properties.getRag().setEnabled(true);
        properties.getRag().setUrl(mockServer.url("").toString().replaceAll("/$", ""));
        properties.getRag().setTimeoutSeconds(5);

        OkHttpClient client = new OkHttpClient();
        adapter = new LightRagAdapter(properties, client, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    void queryReturnsResponse() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setBody("{\"response\": \"LightRAG found relevant info\"}")
                .setHeader("Content-Type", "application/json"));

        String result = adapter.query("test query", "hybrid").get();
        assertEquals("LightRAG found relevant info", result);

        RecordedRequest request = mockServer.takeRequest();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getPath().endsWith("/query"));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"query\":\"test query\""));
        assertTrue(body.contains("\"mode\":\"hybrid\""));
    }

    @Test
    void queryReturnsEmptyOnError() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        String result = adapter.query("test", "hybrid").get();
        assertEquals("", result);
    }

    @Test
    void queryReturnsEmptyWhenDisabled() throws Exception {
        properties.getRag().setEnabled(false);
        String result = adapter.query("test", "hybrid").get();
        assertEquals("", result);
    }

    @Test
    void queryHandlesPlainTextResponse() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setBody("plain text response")
                .setHeader("Content-Type", "text/plain"));

        String result = adapter.query("test", "hybrid").get();
        assertEquals("plain text response", result);
    }

    @Test
    void indexSendsCorrectRequest() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200));

        adapter.index("some document text").get();

        RecordedRequest request = mockServer.takeRequest();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getPath().endsWith("/documents/text"));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"text\":\"some document text\""));
        assertTrue(body.contains("\"file_source\":\"conv_"), "should include file_source");
    }

    @Test
    void indexDoesNothingWhenDisabled() throws Exception {
        properties.getRag().setEnabled(false);
        // Should complete without making any request
        adapter.index("test").get();
        assertEquals(0, mockServer.getRequestCount());
    }

    @Test
    void isAvailableReflectsEnabledFlag() {
        properties.getRag().setEnabled(true);
        assertTrue(adapter.isAvailable());

        properties.getRag().setEnabled(false);
        assertFalse(adapter.isAvailable());
    }

    @Test
    void isHealthyReturnsTrueOnSuccess() {
        mockServer.enqueue(new MockResponse().setResponseCode(200));
        assertTrue(adapter.isHealthy());
    }

    @Test
    void isHealthyReturnsFalseOnError() {
        mockServer.enqueue(new MockResponse().setResponseCode(503));
        assertFalse(adapter.isHealthy());
    }

    @Test
    void isHealthyReturnsFalseWhenDisabled() {
        properties.getRag().setEnabled(false);
        assertFalse(adapter.isHealthy());
    }

    @Test
    void apiKeyHeaderSentWhenConfigured() throws Exception {
        properties.getRag().setApiKey("test-api-key");
        // Recreate adapter to pick up API key
        OkHttpClient client = new OkHttpClient();
        adapter = new LightRagAdapter(properties, client, new ObjectMapper());

        mockServer.enqueue(new MockResponse()
                .setBody("{\"response\": \"ok\"}")
                .setHeader("Content-Type", "application/json"));

        adapter.query("test", "hybrid").get();

        RecordedRequest request = mockServer.takeRequest();
        assertEquals("Bearer test-api-key", request.getHeader("Authorization"));
    }

    @Test
    void noAuthHeaderWhenApiKeyEmpty() throws Exception {
        properties.getRag().setApiKey("");

        mockServer.enqueue(new MockResponse()
                .setBody("{\"response\": \"ok\"}")
                .setHeader("Content-Type", "application/json"));

        adapter.query("test", "hybrid").get();

        RecordedRequest request = mockServer.takeRequest();
        assertNull(request.getHeader("Authorization"));
    }
}
