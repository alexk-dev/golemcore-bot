package me.golemcore.bot.infrastructure.telemetry;

import me.golemcore.bot.testsupport.http.OkHttpMockEngine;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GaTelemetryClientTest {

    private OkHttpMockEngine mockEngine;
    private GaTelemetryClient client;

    @BeforeEach
    void setUp() {
        mockEngine = new OkHttpMockEngine();
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .addInterceptor(mockEngine)
                .build();
        client = new GaTelemetryClient(okHttpClient);
    }

    @Test
    void shouldSendEventWithCorrectGa4Format() {
        mockEngine.enqueueText(204, "", "text/plain");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model_name", "gpt-4o");
        params.put("tier", "smart");
        params.put("request_count", 12L);
        params.put("input_tokens", 4500L);

        client.sendEvent("test-client-id", 1712500000L, "model_usage", params);

        assertEquals(1, mockEngine.getRequestCount());
        OkHttpMockEngine.CapturedRequest request = mockEngine.takeRequest();
        assertEquals("POST", request.method());

        String payload = request.body();
        assertTrue(payload.contains("v=2"));
        assertTrue(payload.contains("tid=G-"));
        assertTrue(payload.contains("cid=test-client-id"));
        assertTrue(payload.contains("sid=1712500000"));
        assertTrue(payload.contains("en=model_usage"));
        assertTrue(payload.contains("ep.model_name=gpt-4o"));
        assertTrue(payload.contains("ep.tier=smart"));
        assertTrue(payload.contains("epn.request_count=12"));
        assertTrue(payload.contains("epn.input_tokens=4500"));
        assertTrue(payload.contains("_et=1"));
    }

    @Test
    void shouldThrowWhenNetworkFails() {
        mockEngine.enqueueFailure(new IOException("network error"));

        assertThrows(IllegalStateException.class,
                () -> client.sendEvent("cid", 123L, "test_event", Map.of()));
    }
}
