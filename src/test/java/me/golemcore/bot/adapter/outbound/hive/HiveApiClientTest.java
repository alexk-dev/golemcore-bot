package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.hive.HiveCapabilitySnapshot;
import me.golemcore.bot.domain.model.hive.HivePolicyApplyResult;
import me.golemcore.bot.domain.model.hive.HivePolicyPackage;
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
                Set.of("web"),
                HiveCapabilitySnapshot.builder()
                        .enabledAutonomyFeatures(Set.of("policy-sync-v1"))
                        .supportedChannels(Set.of("web", "control"))
                        .snapshotHash("hash-1")
                        .defaultModel("openai/gpt-5.1")
                        .build());

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("/api/v1/golems/register", recordedRequest.getTarget());
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("golem-1", response.golemId());
        assertEquals(Instant.parse("2026-03-18T00:10:00Z"), response.accessTokenExpiresAt());
        JsonNode requestJson = new ObjectMapper().readTree(recordedRequest.getBody().utf8());
        assertEquals("policy-sync-v1", requestJson.get("capabilities").get("enabledAutonomyFeatures").get(0).asText());
        Set<String> supportedChannels = new HashSet<>();
        requestJson.get("capabilities").get("supportedChannels").forEach(node -> supportedChannels.add(node.asText()));
        assertTrue(supportedChannels.contains("web"));
        assertTrue(supportedChannels.contains("control"));
    }

    @Test
    void shouldSendBearerTokenWithHeartbeat() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("{\"ok\":true}").build());

        hiveApiClient.heartbeat(server.url("/").toString(),
                "golem-1",
                "access",
                "connected",
                "healthy",
                null,
                15L,
                "cap-hash",
                "pg-1",
                3,
                2,
                "OUT_OF_SYNC",
                "missing-provider",
                "https://bot.example.com/dashboard");

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("/api/v1/golems/golem-1/heartbeat", recordedRequest.getTarget());
        assertEquals("Bearer access", recordedRequest.getHeaders().get("Authorization"));
        JsonNode payload = new ObjectMapper().readTree(recordedRequest.getBody().utf8());
        assertEquals("pg-1", payload.get("policyGroupId").asText());
        assertEquals(3, payload.get("targetPolicyVersion").asInt());
        assertEquals(2, payload.get("appliedPolicyVersion").asInt());
        assertEquals("OUT_OF_SYNC", payload.get("syncStatus").asText());
        assertEquals("missing-provider", payload.get("lastPolicyErrorDigest").asText());
        assertEquals("cap-hash", payload.get("capabilitySnapshotHash").asText());
        assertEquals("https://bot.example.com/dashboard", payload.get("dashboardBaseUrl").asText());
    }

    @Test
    void shouldFetchPolicyPackageWithBearerToken() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("""
                {
                  "policyGroupId": "pg-1",
                  "targetVersion": 4,
                  "checksum": "sha256:abcd",
                  "llmProviders": {
                    "openai": {
                      "apiKey": "sk-test",
                      "baseUrl": "https://api.openai.com/v1",
                      "requestTimeoutSeconds": 30,
                      "apiType": "openai",
                      "legacyApi": false
                    }
                  },
                  "modelRouter": {
                    "temperature": 0.2,
                    "routing": {
                      "model": "openai/gpt-5.1",
                      "reasoning": "none"
                    },
                    "tiers": {
                      "balanced": {
                        "model": "openai/gpt-5.1",
                        "reasoning": "none"
                      }
                    },
                    "dynamicTierEnabled": true
                  },
                  "modelCatalog": {
                    "defaultModel": "openai/gpt-5.1",
                    "models": {
                      "openai/gpt-5.1": {
                        "provider": "openai",
                        "displayName": "GPT-5.1",
                        "supportsVision": true,
                        "supportsTemperature": false,
                        "maxInputTokens": 400000
                      }
                    }
                  }
                }
                """).build());

        HivePolicyPackage response = hiveApiClient.getPolicyPackage(
                server.url("/").toString(),
                "golem-1",
                "access");

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("/api/v1/golems/golem-1/policy-package", recordedRequest.getTarget());
        assertEquals("GET", recordedRequest.getMethod());
        assertEquals("Bearer access", recordedRequest.getHeaders().get("Authorization"));
        assertEquals("pg-1", response.getPolicyGroupId());
        assertEquals(4, response.getTargetVersion());
        assertEquals("sha256:abcd", response.getChecksum());
        assertEquals("sk-test", Secret.valueOrEmpty(response.getLlmProviders().get("openai").getApiKey()));
        assertEquals("openai/gpt-5.1", response.getModelCatalog().getDefaultModel());
    }

    @Test
    void shouldReportPolicyApplyResultWithBearerToken() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("""
                {
                  "policyGroupId": "pg-1",
                  "targetVersion": 4,
                  "appliedVersion": 4,
                  "syncStatus": "IN_SYNC",
                  "checksum": "sha256:abcd",
                  "errorDigest": null,
                  "errorDetails": null
                }
                """).build());

        HivePolicyApplyResult response = hiveApiClient.reportPolicyApplyResult(
                server.url("/").toString(),
                "golem-1",
                "access",
                HivePolicyApplyResult.builder()
                        .policyGroupId("pg-1")
                        .targetVersion(4)
                        .appliedVersion(4)
                        .syncStatus("IN_SYNC")
                        .checksum("sha256:abcd")
                        .build());

        RecordedRequest recordedRequest = server.takeRequest();
        assertEquals("/api/v1/golems/golem-1/policy-apply-result", recordedRequest.getTarget());
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("Bearer access", recordedRequest.getHeaders().get("Authorization"));
        JsonNode payload = new ObjectMapper().readTree(recordedRequest.getBody().utf8());
        assertEquals("pg-1", payload.get("policyGroupId").asText());
        assertEquals(4, payload.get("targetVersion").asInt());
        assertEquals("IN_SYNC", payload.get("syncStatus").asText());
        assertEquals("IN_SYNC", response.getSyncStatus());
        assertEquals(4, response.getAppliedVersion());
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
