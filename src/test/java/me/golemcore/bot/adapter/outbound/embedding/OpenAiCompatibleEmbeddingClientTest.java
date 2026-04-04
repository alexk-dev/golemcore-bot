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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiCompatibleEmbeddingClientTest {

    private MockWebServer server;
    private OpenAiCompatibleEmbeddingClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new OpenAiCompatibleEmbeddingClient(new OkHttpClient(), new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void shouldCallOpenAiCompatibleEmbeddingsEndpoint() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("""
                        {
                          "model": "text-embedding-3-large",
                          "data": [
                            {"index": 0, "embedding": [0.1, 0.2, 0.3]}
                          ]
                        }
                        """)
                .build());

        var response = client.embed(new me.golemcore.bot.port.outbound.EmbeddingPort.EmbeddingRequest(
                server.url("/").toString(),
                "test-key",
                "text-embedding-3-large",
                3,
                5000,
                List.of("planner tactic")));

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("/embeddings", recordedRequest.getTarget());
        assertEquals("Bearer test-key", recordedRequest.getHeaders().get("Authorization"));
        assertEquals(1, response.vectors().size());
        assertEquals(3, response.vectors().getFirst().size());
    }

    @Test
    void shouldRejectEmptyEmbeddingResponseBody() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("")
                .build());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> client.embed(
                new me.golemcore.bot.port.outbound.EmbeddingPort.EmbeddingRequest(
                        server.url("/").toString(),
                        "test-key",
                        "text-embedding-3-large",
                        3,
                        5000,
                        List.of("planner tactic"))));

        assertEquals("Embedding response body is empty", exception.getMessage());
    }

    @Test
    void shouldRejectFailedEmbeddingResponseStatus() {
        server.enqueue(new MockResponse.Builder().code(502).build());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> client.embed(
                new me.golemcore.bot.port.outbound.EmbeddingPort.EmbeddingRequest(
                        server.url("/").toString(),
                        "test-key",
                        "text-embedding-3-large",
                        3,
                        5000,
                        List.of("planner tactic"))));

        assertEquals("Embedding request failed with status 502", exception.getMessage());
    }

    @Test
    void shouldOmitAuthorizationHeaderWhenApiKeyBlank() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("""
                        {
                          "model": "text-embedding-3-large",
                          "data": [
                            {"index": 0, "embedding": [0.1, 0.2, 0.3]}
                          ]
                        }
                        """)
                .build());

        client.embed(new me.golemcore.bot.port.outbound.EmbeddingPort.EmbeddingRequest(
                server.url("/").toString(),
                "   ",
                "text-embedding-3-large",
                3,
                5000,
                List.of("planner tactic")));

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("/embeddings", recordedRequest.getTarget());
        assertNull(recordedRequest.getHeaders().get("Authorization"));
    }
}
