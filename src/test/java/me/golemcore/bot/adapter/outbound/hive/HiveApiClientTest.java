package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveApiClientTest {

    private MockWebServer server;
    private HiveApiClient hiveApiClient;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        hiveApiClient = new HiveApiClient(new OkHttpClient(), new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void shouldRegisterGolem() throws Exception {
        server.enqueue(new MockResponse.Builder().code(201).body("""
                {
                  "golemId": "golem-1",
                  "accessToken": "access",
                  "refreshToken": "refresh",
                  "accessTokenExpiresAt": "2026-03-18T00:10:00Z",
                  "refreshTokenExpiresAt": "2026-03-19T00:10:00Z",
                  "issuer": "hive",
                  "audience": "golems",
                  "controlChannelUrl": "wss://hive.example.com/ws/golems/golem-1",
                  "heartbeatIntervalSeconds": 30,
                  "scopes": ["golems:heartbeat"]
                }
                """).build());

        HiveApiClient.GolemAuthResponse response = hiveApiClient.register(
                server.url("/").toString(),
                "token-id.secret",
                "Builder",
                "lab-a",
                "1.0.0",
                "abc1234",
                Set.of("web"));

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("/api/v1/golems/register", recordedRequest.getTarget());
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("golem-1", response.golemId());
        assertEquals(Instant.parse("2026-03-18T00:10:00Z"), response.accessTokenExpiresAt());
    }

    @Test
    void shouldSendBearerTokenWithHeartbeat() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("{\"ok\":true}").build());

        hiveApiClient.heartbeat(server.url("/").toString(), "golem-1", "access", "connected", "healthy", null, 15L);

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("/api/v1/golems/golem-1/heartbeat", recordedRequest.getTarget());
        assertEquals("Bearer access", recordedRequest.getHeaders().get("Authorization"));
    }

    @Test
    void shouldPublishEventBatchWithBearerToken() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("{\"acceptedEvents\":1}").build());

        hiveApiClient.publishEventsBatch(
                server.url("/").toString(),
                "golem-1",
                "access",
                List.of(HiveEventPayload.builder()
                        .schemaVersion(1)
                        .eventType("runtime_event")
                        .runtimeEventType("COMMAND_ACKNOWLEDGED")
                        .threadId("thread-1")
                        .commandId("cmd-1")
                        .createdAt(Instant.parse("2026-03-18T00:00:00Z"))
                        .build()));

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("/api/v1/golems/golem-1/events:batch", recordedRequest.getTarget());
        assertEquals("Bearer access", recordedRequest.getHeaders().get("Authorization"));
    }

    @Test
    void shouldExposeHiveStatusCodeOnApiError() {
        server.enqueue(new MockResponse.Builder().code(401).body("{\"message\":\"Invalid refresh token\"}").build());

        HiveApiClient.HiveApiException error = assertThrows(HiveApiClient.HiveApiException.class,
                () -> hiveApiClient.rotate(server.url("/").toString(), "golem-1", "refresh"));

        assertEquals(401, error.getStatusCode());
        assertEquals("Invalid refresh token", error.getMessage());
    }
}
