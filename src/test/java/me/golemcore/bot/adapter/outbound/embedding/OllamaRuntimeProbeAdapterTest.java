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
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
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
}
