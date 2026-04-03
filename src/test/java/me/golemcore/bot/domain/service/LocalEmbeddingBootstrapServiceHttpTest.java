package me.golemcore.bot.domain.service;

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

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import me.golemcore.bot.port.outbound.OllamaProcessPort;

class LocalEmbeddingBootstrapServiceHttpTest {

    private MockWebServer server;
    private LocalEmbeddingBootstrapService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = new LocalEmbeddingBootstrapService(
                mock(RuntimeConfigService.class),
                new TacticSearchMetricsService(Clock.fixed(Instant.parse("2026-04-01T21:00:00Z"), ZoneOffset.UTC)),
                Clock.fixed(Instant.parse("2026-04-01T21:00:00Z"), ZoneOffset.UTC),
                new OkHttpClient(),
                new ObjectMapper(),
                null,
                stubProcessPort());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void shouldDetectHealthyOllamaRuntimeAndAvailableModel() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("""
                        {
                          "models": [
                            {
                              "name": "qwen3-embedding:0.6b"
                            }
                          ]
                        }
                        """)
                .build());
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("""
                        {
                          "models": [
                            {
                              "name": "qwen3-embedding:0.6b"
                            }
                          ]
                        }
                        """)
                .build());

        String baseUrl = server.url("/").toString();

        assertTrue(service.isRuntimeHealthy(baseUrl));
        assertTrue(service.hasModel(baseUrl, "qwen3-embedding:0.6b"));
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

        assertTrue(service.pullModel(baseUrl, "qwen3-embedding:0.6b"));

        RecordedRequest request = server.takeRequest();
        assertEquals("/api/pull", request.getTarget());
        assertTrue(request.getBody().utf8().contains("qwen3-embedding:0.6b"));
    }

    @Test
    void shouldReturnFalseWhenRuntimeEndpointIsUnavailable() {
        server.enqueue(new MockResponse.Builder().code(503).build());

        String baseUrl = server.url("/").toString();

        assertFalse(service.isRuntimeHealthy(baseUrl));
    }

    @Test
    void shouldReturnFalseWhenRuntimeEndpointThrowsConnectionError() {
        assertFalse(service.isRuntimeHealthy("http://127.0.0.1:1"));
    }

    @Test
    void shouldReturnFalseWhenRequestedModelIsMissing() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("""
                        {
                          "models": [
                            {
                              "name": "different-model"
                            }
                          ]
                        }
                        """)
                .build());

        String baseUrl = server.url("/").toString();

        assertFalse(service.hasModel(baseUrl, "qwen3-embedding:0.6b"));
    }

    @Test
    void shouldReturnFalseWhenModelEndpointRejectsRequest() {
        server.enqueue(new MockResponse.Builder().code(503).build());

        String baseUrl = server.url("/").toString();

        assertFalse(service.hasModel(baseUrl, "qwen3-embedding:0.6b"));
    }

    @Test
    void shouldReturnFalseWhenModelEndpointThrowsConnectionError() {
        assertFalse(service.hasModel("http://127.0.0.1:1", "qwen3-embedding:0.6b"));
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

        String baseUrl = server.url("/").toString();

        assertFalse(service.pullModel(baseUrl, "qwen3-embedding:0.6b"));
    }

    @Test
    void shouldReturnFalseWhenPullEndpointThrowsConnectionError() {
        assertFalse(service.pullModel("http://127.0.0.1:1", "qwen3-embedding:0.6b"));
    }

    private OllamaProcessPort stubProcessPort() {
        return new OllamaProcessPort() {
            @Override
            public boolean isBinaryAvailable() {
                return true;
            }

            @Override
            public String getInstalledVersion() {
                return "0.19.0";
            }

            @Override
            public void startServe(String endpoint) {
            }

            @Override
            public boolean isOwnedProcessAlive() {
                return false;
            }

            @Override
            public Integer getOwnedProcessExitCode() {
                return null;
            }

            @Override
            public void stopOwnedProcess() {
            }
        };
    }
}
