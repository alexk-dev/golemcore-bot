package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.golemcore.bot.domain.view.SessionDetailView;
import me.golemcore.bot.domain.view.SessionMessagesPageView;
import me.golemcore.bot.domain.view.SessionSummaryView;
import me.golemcore.bot.domain.view.SessionTraceExportView;
import me.golemcore.bot.domain.view.SessionTraceSnapshotView;
import me.golemcore.bot.domain.view.SessionTraceSpanView;
import me.golemcore.bot.domain.view.SessionTraceStorageStatsView;
import me.golemcore.bot.domain.view.SessionTraceSummaryView;
import me.golemcore.bot.domain.view.SessionTraceView;
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
        JsonNode payload = new ObjectMapper().readTree(recordedRequest.getBody().utf8());
        assertEquals(1, payload.get("schemaVersion").asInt());
        assertEquals("golem-1", payload.get("golemId").asText());
        assertEquals("runtime_event", payload.get("events").get(0).get("eventType").asText());
    }

    @Test
    void shouldSerializeInspectionPayloadWithStableJsonShape() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("{\"acceptedEvents\":1}").build());
        HiveInspectionPayloadMapper payloadMapper = new HiveInspectionPayloadMapper();
        Object inspectionPayload = payloadMapper.toSessionListPayload(List.of(SessionSummaryView.builder()
                .id("web:conv-1")
                .channelType("web")
                .chatId("legacy-chat")
                .conversationKey("conv-1")
                .transportChatId("client-1")
                .messageCount(2)
                .state("ACTIVE")
                .createdAt(Instant.parse("2026-03-20T10:00:00Z"))
                .updatedAt(Instant.parse("2026-03-20T10:05:00Z"))
                .title("Session conv-1")
                .preview("hello")
                .active(false)
                .build()));

        hiveApiClient.publishEventsBatch(
                server.url("/").toString(),
                "golem-1",
                "access",
                List.of(HiveEventPayload.builder()
                        .schemaVersion(1)
                        .eventType("inspection_response")
                        .requestId("req-1")
                        .operation("sessions.list")
                        .success(true)
                        .payload(inspectionPayload)
                        .createdAt(Instant.parse("2026-03-20T10:06:00Z"))
                        .build()));

        RecordedRequest recordedRequest = server.takeRequest();
        JsonNode payload = new ObjectMapper().readTree(recordedRequest.getBody().utf8());
        JsonNode inspection = payload.get("events").get(0);
        assertEquals("inspection_response", inspection.get("eventType").asText());
        assertEquals("sessions.list", inspection.get("operation").asText());
        assertEquals("web:conv-1", inspection.get("payload").get(0).get("id").asText());
        assertEquals("2026-03-20T10:00:00Z", inspection.get("payload").get(0).get("createdAt").asText());
        assertEquals("2026-03-20T10:05:00Z", inspection.get("payload").get(0).get("updatedAt").asText());
    }

    @Test
    void shouldSerializeSessionDetailAndMessagesPayloadsWithoutWebRelativeAttachmentUrls() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("{\"acceptedEvents\":2}").build());
        HiveInspectionPayloadMapper payloadMapper = new HiveInspectionPayloadMapper();
        SessionDetailView.MessageView message = SessionDetailView.MessageView.builder()
                .id("m-1")
                .role("assistant")
                .content("Rendered trace")
                .timestamp(Instant.parse("2026-03-20T10:01:00Z"))
                .attachments(List.of(
                        SessionDetailView.AttachmentView.builder()
                                .type("image")
                                .name("capture.png")
                                .internalFilePath(".golemcore/tool-artifacts/session/tool/capture.png")
                                .thumbnailBase64("thumb")
                                .build(),
                        SessionDetailView.AttachmentView.builder()
                                .type("file")
                                .name("report.txt")
                                .directUrl("https://cdn.example.com/report.txt")
                                .internalFilePath(".golemcore/tool-artifacts/session/tool/report.txt")
                                .build()))
                .build();
        Object detailPayload = payloadMapper.toSessionDetailPayload(SessionDetailView.builder()
                .id("web:conv-1")
                .createdAt(Instant.parse("2026-03-20T10:00:00Z"))
                .updatedAt(Instant.parse("2026-03-20T10:05:00Z"))
                .messages(List.of(message))
                .build());
        Object messagesPayload = payloadMapper.toSessionMessagesPayload(SessionMessagesPageView.builder()
                .sessionId("web:conv-1")
                .messages(List.of(message))
                .hasMore(false)
                .oldestMessageId("m-1")
                .build());

        hiveApiClient.publishEventsBatch(
                server.url("/").toString(),
                "golem-1",
                "access",
                List.of(
                        HiveEventPayload.builder()
                                .schemaVersion(1)
                                .eventType("inspection_response")
                                .requestId("req-detail")
                                .operation("session.detail")
                                .success(true)
                                .payload(detailPayload)
                                .createdAt(Instant.parse("2026-03-20T10:06:00Z"))
                                .build(),
                        HiveEventPayload.builder()
                                .schemaVersion(1)
                                .eventType("inspection_response")
                                .requestId("req-messages")
                                .operation("session.messages")
                                .success(true)
                                .payload(messagesPayload)
                                .createdAt(Instant.parse("2026-03-20T10:06:01Z"))
                                .build()));

        RecordedRequest recordedRequest = server.takeRequest();
        JsonNode events = new ObjectMapper().readTree(recordedRequest.getBody().utf8()).get("events");
        JsonNode detail = events.get(0);
        JsonNode detailAttachment = detail.get("payload").get("messages").get(0).get("attachments").get(0);
        assertEquals("session.detail", detail.get("operation").asText());
        assertEquals(".golemcore/tool-artifacts/session/tool/capture.png",
                detailAttachment.get("internalFilePath").asText());
        assertEquals("thumb", detailAttachment.get("thumbnailBase64").asText());
        assertNull(detailAttachment.get("url"));
        assertNull(detailAttachment.get("directUrl"));
        JsonNode directAttachment = detail.get("payload").get("messages").get(0).get("attachments").get(1);
        assertEquals("https://cdn.example.com/report.txt", directAttachment.get("directUrl").asText());
        assertNull(directAttachment.get("url"));
        JsonNode messages = events.get(1);
        assertEquals("session.messages", messages.get("operation").asText());
        assertEquals("2026-03-20T10:01:00Z", messages.get("payload").get("messages").get(0).get("timestamp").asText());
    }

    @Test
    void shouldSerializeTraceInspectionPayloadFamiliesWithStableJsonShape() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("{\"acceptedEvents\":3}").build());
        HiveInspectionPayloadMapper payloadMapper = new HiveInspectionPayloadMapper();
        Object summaryPayload = payloadMapper.toSessionTraceSummaryPayload(SessionTraceSummaryView.builder()
                .sessionId("web:conv-1")
                .traceCount(1)
                .spanCount(1)
                .snapshotCount(1)
                .storageStats(SessionTraceStorageStatsView.builder().compressedSnapshotBytes(10L).build())
                .traces(List.of(SessionTraceSummaryView.TraceSummaryView.builder()
                        .traceId("trace-1")
                        .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                        .endedAt(Instant.parse("2026-03-20T10:00:01Z"))
                        .durationMs(1000L)
                        .build()))
                .build());
        Object detailPayload = payloadMapper.toSessionTracePayload(SessionTraceView.builder()
                .sessionId("web:conv-1")
                .storageStats(SessionTraceStorageStatsView.builder().compressedSnapshotBytes(10L).build())
                .traces(List.of(SessionTraceView.TraceView.builder()
                        .traceId("trace-1")
                        .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                        .endedAt(Instant.parse("2026-03-20T10:00:01Z"))
                        .spans(List.of(SessionTraceSpanView.builder()
                                .spanId("span-1")
                                .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                                .endedAt(Instant.parse("2026-03-20T10:00:01Z"))
                                .events(List.of(SessionTraceSpanView.EventView.builder()
                                        .name("event-1")
                                        .timestamp(Instant.parse("2026-03-20T10:00:00.500Z"))
                                        .attributes(Map.of("kind", "ingress"))
                                        .build()))
                                .snapshots(List.of(SessionTraceSnapshotView.builder()
                                        .snapshotId("snap-1")
                                        .payloadAvailable(true)
                                        .payloadPreview("{\"ok\":true}")
                                        .payloadPreviewTruncated(false)
                                        .build()))
                                .build()))
                        .build()))
                .build());
        Object exportPayload = payloadMapper.toSessionTraceExportPayload(SessionTraceExportView.builder()
                .sessionId("web:conv-1")
                .storageStats(SessionTraceStorageStatsView.builder().compressedSnapshotBytes(10L).build())
                .traces(List.of(SessionTraceExportView.TraceExportView.builder()
                        .traceId("trace-1")
                        .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                        .endedAt(Instant.parse("2026-03-20T10:00:01Z"))
                        .spans(List.of(SessionTraceExportView.SpanExportView.builder()
                                .spanId("span-1")
                                .status(SessionTraceExportView.StatusView.builder()
                                        .code("OK")
                                        .message("done")
                                        .build())
                                .events(List.of(SessionTraceExportView.EventExportView.builder()
                                        .name("event-1")
                                        .timestamp(Instant.parse("2026-03-20T10:00:00.500Z"))
                                        .attributes(Map.of("kind", "ingress"))
                                        .build()))
                                .snapshots(List.of(SessionTraceExportView.SnapshotExportView.builder()
                                        .snapshotId("snap-1")
                                        .payloadText("{\"ok\":true}")
                                        .build()))
                                .build()))
                        .build()))
                .build());

        hiveApiClient.publishEventsBatch(
                server.url("/").toString(),
                "golem-1",
                "access",
                List.of(
                        HiveEventPayload.builder()
                                .schemaVersion(1)
                                .eventType("inspection_response")
                                .requestId("req-summary")
                                .operation("session.trace.summary")
                                .success(true)
                                .payload(summaryPayload)
                                .createdAt(Instant.parse("2026-03-20T10:06:00Z"))
                                .build(),
                        HiveEventPayload.builder()
                                .schemaVersion(1)
                                .eventType("inspection_response")
                                .requestId("req-detail")
                                .operation("session.trace.detail")
                                .success(true)
                                .payload(detailPayload)
                                .createdAt(Instant.parse("2026-03-20T10:06:01Z"))
                                .build(),
                        HiveEventPayload.builder()
                                .schemaVersion(1)
                                .eventType("inspection_response")
                                .requestId("req-export")
                                .operation("session.trace.export")
                                .success(true)
                                .payload(exportPayload)
                                .createdAt(Instant.parse("2026-03-20T10:06:02Z"))
                                .build()));

        RecordedRequest recordedRequest = server.takeRequest();
        JsonNode events = new ObjectMapper().readTree(recordedRequest.getBody().utf8()).get("events");
        assertEquals("2026-03-20T10:00:00Z",
                events.get(0).get("payload").get("traces").get(0).get("startedAt").asText());
        assertEquals("2026-03-20T10:00:00.500Z",
                events.get(1).get("payload").get("traces").get(0).get("spans").get(0).get("events").get(0)
                        .get("timestamp").asText());
        assertEquals("OK", events.get(2).get("payload").get("traces").get(0).get("spans").get(0).get("status")
                .get("code").asText());
        assertEquals("{\"ok\":true}",
                events.get(2).get("payload").get("traces").get(0).get("spans").get(0).get("snapshots").get(0)
                        .get("payloadText").asText());
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
