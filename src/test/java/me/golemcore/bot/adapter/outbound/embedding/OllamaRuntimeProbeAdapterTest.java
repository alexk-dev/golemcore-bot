package me.golemcore.bot.adapter.outbound.embedding;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OllamaRuntimeProbeAdapterTest {

    private MockWebServer server;
    private OllamaRuntimeProbeAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        adapter = new OllamaRuntimeProbeAdapter(new OkHttpClient(), new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void shouldReportRuntimeHealthyFromTagsEndpoint() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"models\":[]}")
                .build());

        assertTrue(adapter.isRuntimeReachable(server.url("/").toString()));
    }

    @Test
    void shouldReturnFalseWhenRuntimeEndpointIsUnavailable() {
        server.enqueue(new MockResponse.Builder().code(503).build());

        assertFalse(adapter.isRuntimeReachable(server.url("/").toString()));
    }

    @Test
    void shouldReturnFalseWhenRuntimeEndpointThrowsConnectionError() {
        assertFalse(adapter.isRuntimeReachable("http://127.0.0.1:1"));
    }

    @Test
    void shouldParseRuntimeVersion() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"version\":\"0.19.0\"}")
                .build());

        assertEquals("0.19.0", adapter.getRuntimeVersion(server.url("/").toString()));
    }

    @Test
    void shouldDetectModelAvailabilityFromTagsEndpoint() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("""
                        {
                          "models": [
                            {"name": "qwen3-embedding:0.6b"},
                            {"name": "bge-m3"}
                          ]
                        }
                        """)
                .build());

        assertTrue(adapter.hasModel(server.url("/").toString(), "qwen3-embedding:0.6b"));
    }

    @Test
    void shouldDetectModelAvailabilityWhenOllamaReturnsLatestTag() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("""
                        {
                          "models": [
                            {"name": "bge-m3:latest"}
                          ]
                        }
                        """)
                .build());

        assertTrue(adapter.hasModel(server.url("/").toString(), "bge-m3"));
    }

    @Test
    void shouldReturnFalseWhenModelIsMissing() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("""
                        {
                          "models": [
                            {"name": "nomic-embed-text"}
                          ]
                        }
                        """)
                .build());

        assertFalse(adapter.hasModel(server.url("/").toString(), "qwen3-embedding:0.6b"));
    }

    @Test
    void shouldReturnFalseWhenModelEndpointRejectsRequest() {
        server.enqueue(new MockResponse.Builder().code(503).build());

        assertFalse(adapter.hasModel(server.url("/").toString(), "qwen3-embedding:0.6b"));
    }

    @Test
    void shouldReturnFalseWhenModelEndpointThrowsConnectionError() {
        assertFalse(adapter.hasModel("http://127.0.0.1:1", "qwen3-embedding:0.6b"));
    }

    @Test
    void shouldCallOllamaPullEndpointWhenInstallingModel() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("""
                        {
                          "status": "success"
                        }
                        """)
                .build());

        String baseUrl = server.url("/").toString();

        assertTrue(adapter.pullModel(baseUrl, "qwen3-embedding:0.6b", Duration.ofSeconds(5)));

        RecordedRequest request = server.takeRequest();
        assertEquals("/api/pull", request.getTarget());
        assertTrue(request.getBody().utf8().contains("qwen3-embedding:0.6b"));
    }

    @Test
    void shouldReturnFalseWhenPullEndpointRejectsInstall() {
        server.enqueue(new MockResponse.Builder()
                .code(500)
                .body("""
                        {
                          "status": "failed"
                        }
                        """)
                .build());

        assertFalse(adapter.pullModel(server.url("/").toString(), "qwen3-embedding:0.6b", Duration.ofSeconds(5)));
    }

    @Test
    void shouldReturnFalseWhenPullEndpointThrowsConnectionError() {
        assertFalse(adapter.pullModel("http://127.0.0.1:1", "qwen3-embedding:0.6b", Duration.ofSeconds(5)));
    }

    @Test
    void shouldRejectNonLocalRuntimeEndpoints() {
        assertFalse(adapter.isRuntimeReachable("http://example.com:11434"));
        assertFalse(adapter.hasModel("http://example.com:11434", "qwen3-embedding:0.6b"));
        assertFalse(adapter.pullModel("http://example.com:11434", "qwen3-embedding:0.6b", Duration.ofSeconds(5)));
    }

    @Test
    void shouldRejectLocalRuntimeEndpointsWithPathPrefixOrQuery() {
        assertFalse(adapter.isRuntimeReachable("http://127.0.0.1:11434/proxy"));
        assertFalse(adapter.hasModel("http://127.0.0.1:11434/?via=proxy", "qwen3-embedding:0.6b"));
        assertFalse(adapter.pullModel("http://127.0.0.1:11434/#fragment", "qwen3-embedding:0.6b",
                Duration.ofSeconds(5)));
    }

    @Test
    void shouldRejectLocalRuntimeEndpointsWithUserInfoOrUnsupportedScheme() {
        assertFalse(adapter.isRuntimeReachable("http://user@127.0.0.1:11434"));
        assertFalse(adapter.hasModel("ftp://127.0.0.1:11434", "qwen3-embedding:0.6b"));
        assertFalse(adapter.pullModel("ftp://127.0.0.1:11434", "qwen3-embedding:0.6b", Duration.ofSeconds(5)));
    }

    @Test
    void shouldHandleLoopbackDefaultPortsAndIpv6Endpoints() {
        assertFalse(adapter.isRuntimeReachable("http://127.0.0.1"));
        assertFalse(adapter.hasModel("https://localhost", "qwen3-embedding:0.6b"));
        assertFalse(adapter.pullModel("http://[::1]:1", "qwen3-embedding:0.6b", Duration.ofSeconds(5)));
    }

    @Test
    void shouldAllowLongRunningPullsBeyondSharedHttpTimeout() {
        adapter = new OllamaRuntimeProbeAdapter(
                new OkHttpClient.Builder()
                        .callTimeout(Duration.ofMillis(100))
                        .connectTimeout(Duration.ofMillis(100))
                        .readTimeout(Duration.ofMillis(100))
                        .writeTimeout(Duration.ofMillis(100))
                        .build(),
                new ObjectMapper());
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("""
                        {
                          "status": "success"
                        }
                        """)
                .headersDelay(250, TimeUnit.MILLISECONDS)
                .build());

        assertTrue(adapter.pullModel(server.url("/").toString(), "bge-m3", Duration.ofSeconds(5)));
    }
}
