package me.golemcore.bot.adapter.outbound.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OllamaEmbeddingClientTest {

    private MockWebServer server;
    private OllamaEmbeddingClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new OllamaEmbeddingClient(new OkHttpClient(), new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void shouldCallOllamaEmbedEndpoint() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("""
                        {
                          "model": "qwen3-embedding:0.6b",
                          "embeddings": [
                            [0.4, 0.5, 0.6]
                          ]
                        }
                        """)
                .build());

        var response = client.embed(new me.golemcore.bot.port.outbound.EmbeddingPort.EmbeddingRequest(
                server.url("/").toString(),
                null,
                "qwen3-embedding:0.6b",
                3,
                5000,
                List.of("planner tactic")));

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("/api/embed", recordedRequest.getTarget());
        assertEquals(1, response.vectors().size());
        assertEquals(3, response.vectors().getFirst().size());
    }

    @Test
    void shouldRejectFailedEmbeddingResponseStatus() {
        server.enqueue(new MockResponse.Builder().code(503).build());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> client.embed(
                new me.golemcore.bot.port.outbound.EmbeddingPort.EmbeddingRequest(
                        server.url("/").toString(),
                        null,
                        "qwen3-embedding:0.6b",
                        3,
                        5000,
                        List.of("planner tactic"))));

        assertEquals("Failed to fetch Ollama embeddings", exception.getMessage());
        assertEquals("Embedding request failed with status 503", exception.getCause().getMessage());
    }
}
