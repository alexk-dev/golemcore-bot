package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.List;
import me.golemcore.bot.domain.model.hive.HiveCardSearchRequest;
import me.golemcore.bot.domain.model.hive.HiveCreateCardRequest;
import me.golemcore.bot.domain.model.hive.HiveRequestReviewRequest;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveApiClientSdlcTest {

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
    void shouldGetCardWithBearerToken() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("""
                {
                  "id": "card-1",
                  "serviceId": "service-1",
                  "boardId": "board-1",
                  "threadId": "thread-1",
                  "title": "Implement SDLC",
                  "prompt": "Do it",
                  "columnId": "in_progress",
                  "archived": false,
                  "dependsOnCardIds": [],
                  "reviewerGolemIds": []
                }
                """).build());

        assertEquals("card-1", hiveApiClient.getCard(server.url("/").toString(), "golem-1", "access", "card-1").id());

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("/api/v1/golems/golem-1/sdlc/cards/card-1", recordedRequest.getTarget());
        assertEquals("GET", recordedRequest.getMethod());
        assertEquals("Bearer access", recordedRequest.getHeaders().get("Authorization"));
    }

    @Test
    void shouldSearchCardsWithFiltersAndBearerToken() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("""
                [{
                  "id": "card-1",
                  "serviceId": "service-1",
                  "boardId": "board-1",
                  "threadId": "thread-1",
                  "title": "Implement SDLC",
                  "columnId": "ready",
                  "archived": false,
                  "dependsOnCardIds": [],
                  "reviewerGolemIds": []
                }]
                """).build());

        assertEquals(1, hiveApiClient.searchCards(
                server.url("/").toString(),
                "golem-1",
                "access",
                new HiveCardSearchRequest("service-1", "board-1", "task", null, null, null, null, false)).size());

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals(
                "/api/v1/golems/golem-1/sdlc/cards?serviceId=service-1&boardId=board-1&kind=task&includeArchived=false",
                recordedRequest.getTarget());
        assertEquals("GET", recordedRequest.getMethod());
        assertEquals("Bearer access", recordedRequest.getHeaders().get("Authorization"));
    }

    @Test
    void shouldCreateCardWithBearerToken() throws Exception {
        server.enqueue(new MockResponse.Builder().code(201).body("""
                {
                  "id": "card-2",
                  "serviceId": "service-1",
                  "boardId": "board-1",
                  "threadId": "thread-2",
                  "title": "Follow-up",
                  "prompt": "Check it",
                  "columnId": "ready",
                  "archived": false,
                  "dependsOnCardIds": ["card-1"],
                  "reviewerGolemIds": []
                }
                """).build());

        assertEquals("card-2", hiveApiClient.createCard(
                server.url("/").toString(),
                "golem-1",
                "access",
                new HiveCreateCardRequest(
                        "service-1",
                        "board-1",
                        "Follow-up",
                        "desc",
                        "Check it",
                        "ready",
                        "task",
                        "card-1",
                        null,
                        null,
                        List.of("card-1"),
                        null,
                        null,
                        null,
                        null,
                        false))
                .id());

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("/api/v1/golems/golem-1/sdlc/cards", recordedRequest.getTarget());
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("Bearer access", recordedRequest.getHeaders().get("Authorization"));
        JsonNode payload = new ObjectMapper().readTree(recordedRequest.getBody().utf8());
        assertEquals("Follow-up", payload.get("title").asText());
        assertEquals("card-1", payload.get("parentCardId").asText());
        assertEquals("card-1", payload.get("dependsOnCardIds").get(0).asText());
    }

    @Test
    void shouldPostThreadMessageWithBearerToken() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("""
                {
                  "id": "msg-1",
                  "threadId": "thread-1",
                  "cardId": "card-1",
                  "type": "NOTE",
                  "participantType": "OPERATOR",
                  "body": "Evidence attached",
                  "createdAt": "2026-03-18T00:00:00Z"
                }
                """).build());

        assertEquals("msg-1", hiveApiClient.postThreadMessage(
                server.url("/").toString(), "golem-1", "access", "thread-1", "Evidence attached").id());

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("/api/v1/golems/golem-1/sdlc/threads/thread-1/messages", recordedRequest.getTarget());
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("Bearer access", recordedRequest.getHeaders().get("Authorization"));
        JsonNode payload = new ObjectMapper().readTree(recordedRequest.getBody().utf8());
        assertEquals("Evidence attached", payload.get("body").asText());
    }

    @Test
    void shouldRequestReviewWithBearerToken() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("""
                {
                  "id": "card-1",
                  "serviceId": "service-1",
                  "boardId": "board-1",
                  "threadId": "thread-1",
                  "title": "Implement SDLC",
                  "prompt": "Do it",
                  "columnId": "review",
                  "archived": false,
                  "dependsOnCardIds": [],
                  "reviewerGolemIds": ["golem-reviewer"]
                }
                """).build());

        assertEquals("card-1", hiveApiClient.requestReview(
                server.url("/").toString(),
                "golem-1",
                "access",
                "card-1",
                new HiveRequestReviewRequest(List.of("golem-reviewer"), null, 1)).id());

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("/api/v1/golems/golem-1/sdlc/cards/card-1:request-review", recordedRequest.getTarget());
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("Bearer access", recordedRequest.getHeaders().get("Authorization"));
        JsonNode payload = new ObjectMapper().readTree(recordedRequest.getBody().utf8());
        assertEquals("golem-reviewer", payload.get("reviewerGolemIds").get(0).asText());
        assertEquals(1, payload.get("requiredReviewCount").asInt());
    }
}
