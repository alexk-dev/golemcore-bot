package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.ActiveSessionRequest;
import me.golemcore.bot.adapter.inbound.web.dto.ActiveSessionResponse;
import me.golemcore.bot.adapter.inbound.web.dto.CreateSessionRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SessionDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionSummaryDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceSnapshotDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceSummaryDto;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.ActiveSessionPointerService;
import me.golemcore.bot.domain.service.TraceSnapshotCompressionService;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSnapshot;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.model.trace.TraceStorageStats;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.security.Principal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionsControllerTest {

    private SessionPort sessionPort;
    private ActiveSessionPointerService pointerService;
    private TraceSnapshotCompressionService compressionService;
    private SessionsController controller;

    @BeforeEach
    void setUp() {
        sessionPort = mock(SessionPort.class);
        pointerService = mock(ActiveSessionPointerService.class);
        compressionService = new TraceSnapshotCompressionService();
        controller = new SessionsController(sessionPort, pointerService, compressionService);
    }

    @Test
    void shouldListSessions() {
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("telegram")
                .chatId("123")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .messages(List.of())
                .build();
        when(sessionPort.listAll()).thenReturn(List.of(session));

        StepVerifier.create(controller.listSessions(null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<SessionSummaryDto> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(1, body.size());
                    assertEquals("s1", body.get(0).getId());
                })
                .verifyComplete();
    }

    @Test
    void shouldFilterSessionsByChannel() {
        AgentSession webSession = AgentSession.builder()
                .id("s2").channelType("web").chatId("456")
                .createdAt(Instant.now()).messages(List.of()).build();
        when(sessionPort.listByChannelType("web")).thenReturn(List.of(webSession));

        StepVerifier.create(controller.listSessions("web"))
                .assertNext(response -> {
                    assertEquals(1, response.getBody().size());
                    assertEquals("s2", response.getBody().get(0).getId());
                })
                .verifyComplete();
    }

    @Test
    void shouldResolveSessionByChatIdAlias() {
        AgentSession session = AgentSession.builder()
                .id("web:conv-1")
                .channelType("web")
                .chatId("legacy-chat-id")
                .metadata(Map.of(ContextAttributes.CONVERSATION_KEY, "conv-1"))
                .createdAt(Instant.now())
                .messages(List.of())
                .build();
        when(sessionPort.listByChannelType("web")).thenReturn(List.of(session));

        StepVerifier.create(controller.resolveSession("web", "legacy-chat-id"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SessionSummaryDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals("web:conv-1", body.getId());
                    assertEquals("conv-1", body.getConversationKey());
                })
                .verifyComplete();
    }

    @Test
    void shouldGetSessionById() {
        Message msg = Message.builder()
                .id("m1").role("user").content("hello")
                .timestamp(Instant.now()).build();
        AgentSession session = AgentSession.builder()
                .id("s1").channelType("telegram").chatId("123")
                .createdAt(Instant.now()).messages(List.of(msg)).build();
        when(sessionPort.get("s1")).thenReturn(Optional.of(session));

        StepVerifier.create(controller.getSession("s1"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SessionDetailDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals("s1", body.getId());
                    assertEquals(1, body.getMessages().size());
                    assertEquals("hello", body.getMessages().get(0).getContent());
                })
                .verifyComplete();
    }

    @Test
    void shouldExposeSessionTraceDetail() {
        TraceSnapshot snapshot = TraceSnapshot.builder()
                .snapshotId("snap-1")
                .role("request")
                .contentType("application/json")
                .encoding("zstd")
                .compressedPayload(
                        compressionService.compress("{\"prompt\":\"hello\"}".getBytes(StandardCharsets.UTF_8)))
                .originalSize(18L)
                .compressedSize(26L)
                .build();
        TraceSpanRecord rootSpan = TraceSpanRecord.builder()
                .spanId("span-root")
                .name("web.request")
                .kind(TraceSpanKind.INGRESS)
                .statusCode(TraceStatusCode.OK)
                .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                .endedAt(Instant.parse("2026-03-20T10:00:01Z"))
                .attributes(Map.of("channel", "web"))
                .snapshots(List.of(snapshot))
                .build();
        TraceSpanRecord llmSpan = TraceSpanRecord.builder()
                .spanId("span-llm")
                .parentSpanId("span-root")
                .name("llm.chat")
                .kind(TraceSpanKind.LLM)
                .statusCode(TraceStatusCode.OK)
                .startedAt(Instant.parse("2026-03-20T10:00:00.200Z"))
                .endedAt(Instant.parse("2026-03-20T10:00:00.900Z"))
                .attributes(Map.of("attempt", 1))
                .build();
        AgentSession session = AgentSession.builder()
                .id("s-trace")
                .channelType("web")
                .chatId("chat-1")
                .createdAt(Instant.parse("2026-03-20T09:59:00Z"))
                .updatedAt(Instant.parse("2026-03-20T10:00:01Z"))
                .traces(List.of(TraceRecord.builder()
                        .traceId("trace-1")
                        .rootSpanId("span-root")
                        .traceName("web.message")
                        .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                        .endedAt(Instant.parse("2026-03-20T10:00:01Z"))
                        .compressedSnapshotBytes(26L)
                        .uncompressedSnapshotBytes(18L)
                        .spans(List.of(rootSpan, llmSpan))
                        .build()))
                .traceStorageStats(TraceStorageStats.builder()
                        .compressedSnapshotBytes(26L)
                        .uncompressedSnapshotBytes(18L)
                        .build())
                .messages(List.of())
                .build();
        when(sessionPort.get("s-trace")).thenReturn(Optional.of(session));

        StepVerifier.create(controller.getSessionTrace("s-trace"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("s-trace", response.getBody().getSessionId());
                    assertEquals(1, response.getBody().getTraces().size());
                    assertEquals("trace-1", response.getBody().getTraces().get(0).getTraceId());
                    assertEquals(2, response.getBody().getTraces().get(0).getSpans().size());
                    assertEquals(1, response.getBody().getTraces().get(0).getSpans().get(0).getSnapshots().size());
                    assertEquals(26L, response.getBody().getStorageStats().getCompressedSnapshotBytes());
                })
                .verifyComplete();
    }

    @Test
    void shouldExposeSessionTraceSummaryForSparseTraceData() {
        TraceSpanRecord timedSpan = TraceSpanRecord.builder()
                .spanId("span-timed")
                .name("tool.run")
                .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                .endedAt(Instant.parse("2026-03-20T10:00:05Z"))
                .build();
        TraceRecord timedTrace = TraceRecord.builder()
                .traceId("trace-early")
                .rootSpanId("missing-root")
                .traceName("tool.trace")
                .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                .endedAt(Instant.parse("2026-03-20T10:00:05Z"))
                .spans(List.of(timedSpan))
                .build();
        TraceRecord sparseTrace = TraceRecord.builder()
                .traceId("trace-late")
                .rootSpanId("missing-root")
                .traceName("sparse.trace")
                .startedAt(null)
                .endedAt(null)
                .truncated(true)
                .spans(Arrays.asList((TraceSpanRecord) null))
                .build();
        AgentSession session = AgentSession.builder()
                .id("s-summary")
                .channelType("web")
                .chatId("chat-summary")
                .traces(Arrays.asList(sparseTrace, null, timedTrace))
                .messages(List.of())
                .build();
        when(sessionPort.get("s-summary")).thenReturn(Optional.of(session));

        StepVerifier.create(controller.getSessionTraceSummary("s-summary"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SessionTraceSummaryDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals("s-summary", body.getSessionId());
                    assertEquals(2, body.getTraceCount());
                    assertEquals(2, body.getSpanCount());
                    assertEquals(0, body.getSnapshotCount());
                    assertEquals("trace-early", body.getTraces().get(0).getTraceId());
                    assertEquals("trace-late", body.getTraces().get(1).getTraceId());
                    assertEquals("missing-root", body.getTraces().get(0).getRootSpanId());
                    assertEquals(5000L, body.getTraces().get(0).getDurationMs());
                    assertNull(body.getTraces().get(1).getRootKind());
                    assertNull(body.getTraces().get(1).getRootStatusCode());
                    assertNull(body.getTraces().get(1).getStartedAt());
                    assertNull(body.getTraces().get(1).getEndedAt());
                    assertNull(body.getTraces().get(1).getDurationMs());
                    assertTrue(body.getTraces().get(1).isTruncated());
                    assertEquals(0L, body.getStorageStats().getCompressedSnapshotBytes());
                })
                .verifyComplete();
    }

    @Test
    void shouldThrowNotFoundWhenTraceSummarySessionMissing() {
        when(sessionPort.get("missing")).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> controller.getSessionTraceSummary("missing"));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void shouldExposeTraceDetailAndExportForSparseSnapshots() {
        String longPayload = "x".repeat(5000);
        byte[] compressedPayload = compressionService.compress(longPayload.getBytes(StandardCharsets.UTF_8));
        TraceSnapshot previewableSnapshot = TraceSnapshot.builder()
                .snapshotId("snap-preview")
                .role("request")
                .contentType("text/plain")
                .encoding("zstd")
                .compressedPayload(compressedPayload)
                .originalSize((long) longPayload.length())
                .compressedSize((long) compressedPayload.length)
                .build();
        TraceSnapshot missingPayloadSnapshot = TraceSnapshot.builder()
                .snapshotId("snap-empty")
                .role("response")
                .contentType("application/json")
                .encoding("zstd")
                .compressedPayload(null)
                .originalSize(0L)
                .compressedSize(0L)
                .build();
        TraceSpanRecord rootSpan = TraceSpanRecord.builder()
                .spanId("span-root")
                .name("web.request")
                .kind(TraceSpanKind.INGRESS)
                .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                .endedAt(Instant.parse("2026-03-20T10:00:01Z"))
                .events(List.of(me.golemcore.bot.domain.model.trace.TraceEventRecord.builder()
                        .name("request.received")
                        .timestamp(Instant.parse("2026-03-20T10:00:00.500Z"))
                        .attributes(Map.of("channel", "web"))
                        .build()))
                .snapshots(List.of(previewableSnapshot))
                .build();
        TraceSpanRecord childSpan = TraceSpanRecord.builder()
                .spanId("span-child")
                .parentSpanId("span-root")
                .name("response.route")
                .kind(null)
                .statusCode(null)
                .startedAt(Instant.parse("2026-03-20T10:00:02Z"))
                .endedAt(Instant.parse("2026-03-20T10:00:02.500Z"))
                .events(null)
                .snapshots(List.of(missingPayloadSnapshot))
                .build();
        TraceRecord trace = TraceRecord.builder()
                .traceId("trace-detail")
                .rootSpanId("missing-root")
                .traceName("web.message")
                .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                .endedAt(Instant.parse("2026-03-20T10:00:03Z"))
                .spans(Arrays.asList(childSpan, null, rootSpan))
                .build();
        AgentSession session = AgentSession.builder()
                .id("s-detail")
                .channelType("web")
                .chatId("chat-detail")
                .traces(List.of(trace))
                .messages(List.of())
                .build();
        when(sessionPort.get("s-detail")).thenReturn(Optional.of(session));

        StepVerifier.create(controller.getSessionTrace("s-detail"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals(2, response.getBody().getTraces().get(0).getSpans().size());
                    assertEquals("span-root", response.getBody().getTraces().get(0).getSpans().get(0).getSpanId());
                    assertEquals("span-child", response.getBody().getTraces().get(0).getSpans().get(1).getSpanId());
                    assertEquals("request.received", response.getBody().getTraces().get(0).getSpans().get(0)
                            .getEvents().get(0).getName());
                    assertTrue(response.getBody().getTraces().get(0).getSpans().get(0)
                            .getSnapshots().get(0).isPayloadAvailable());
                    assertTrue(response.getBody().getTraces().get(0).getSpans().get(0)
                            .getSnapshots().get(0).isPayloadPreviewTruncated());
                    assertFalse(response.getBody().getTraces().get(0).getSpans().get(1)
                            .getSnapshots().get(0).isPayloadAvailable());
                    assertNull(response.getBody().getTraces().get(0).getSpans().get(1)
                            .getSnapshots().get(0).getPayloadPreview());
                })
                .verifyComplete();

        StepVerifier.create(controller.exportSessionTrace("s-detail"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> traces = (List<Map<String, Object>>) response.getBody().get("traces");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> spans = (List<Map<String, Object>>) traces.get(0).get("spans");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> firstSnapshots = (List<Map<String, Object>>) spans.get(0)
                            .get("snapshots");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> secondSnapshots = (List<Map<String, Object>>) spans.get(1)
                            .get("snapshots");
                    assertEquals(longPayload, firstSnapshots.get(0).get("payloadText"));
                    assertNull(secondSnapshots.get(0).get("payloadText"));
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> events = (List<Map<String, Object>>) spans.get(0).get("events");
                    assertEquals("request.received", events.get(0).get("name"));
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void shouldHideSnapshotPreviewWhenPreviewDisabled() throws Exception {
        TraceSnapshot snapshot = TraceSnapshot.builder()
                .snapshotId("snap-hidden")
                .role("request")
                .contentType("text/plain")
                .encoding("zstd")
                .compressedPayload(compressionService.compress("hidden".getBytes(StandardCharsets.UTF_8)))
                .originalSize(6L)
                .compressedSize(18L)
                .build();

        Method method = SessionsController.class.getDeclaredMethod(
                "toTraceSnapshotDto", TraceSnapshot.class, boolean.class);
        method.setAccessible(true);
        SessionTraceSnapshotDto dto = (SessionTraceSnapshotDto) method.invoke(controller, snapshot, false);

        assertFalse(dto.isPayloadAvailable());
        assertNull(dto.getPayloadPreview());
        assertFalse(dto.isPayloadPreviewTruncated());
    }

    @Test
    void shouldExportSessionTraceSnapshotPayloads() {
        TraceSnapshot snapshot = TraceSnapshot.builder()
                .snapshotId("snap-1")
                .role("response")
                .contentType("application/json")
                .encoding("zstd")
                .compressedPayload(compressionService.compress("{\"answer\":\"ok\"}".getBytes(StandardCharsets.UTF_8)))
                .originalSize(15L)
                .compressedSize(24L)
                .build();
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("span-root")
                .name("response.route")
                .kind(TraceSpanKind.OUTBOUND)
                .statusCode(TraceStatusCode.OK)
                .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                .endedAt(Instant.parse("2026-03-20T10:00:01Z"))
                .snapshots(List.of(snapshot))
                .build();
        AgentSession session = AgentSession.builder()
                .id("s-export")
                .channelType("web")
                .chatId("chat-2")
                .createdAt(Instant.parse("2026-03-20T09:59:00Z"))
                .updatedAt(Instant.parse("2026-03-20T10:00:01Z"))
                .traces(List.of(TraceRecord.builder()
                        .traceId("trace-export")
                        .rootSpanId("span-root")
                        .traceName("web.message")
                        .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                        .endedAt(Instant.parse("2026-03-20T10:00:01Z"))
                        .spans(List.of(span))
                        .build()))
                .messages(List.of())
                .build();
        when(sessionPort.get("s-export")).thenReturn(Optional.of(session));

        StepVerifier.create(controller.exportSessionTrace("s-export"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("s-export", response.getBody().get("sessionId"));
                    assertTrue(response.getBody().containsKey("traces"));
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> traces = (List<Map<String, Object>>) response.getBody().get("traces");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> spans = (List<Map<String, Object>>) traces.get(0).get("spans");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> snapshots = (List<Map<String, Object>>) spans.get(0).get("snapshots");
                    assertEquals("{\"answer\":\"ok\"}", snapshots.get(0).get("payloadText"));
                })
                .verifyComplete();
    }

    @Test
    void shouldExportFullSessionTraceSnapshotPayloadWhenPreviewWouldBeTruncated() {
        String payloadText = "{\"payload\":\"" + "x".repeat(4096) + "\"}";
        TraceSnapshot snapshot = TraceSnapshot.builder()
                .snapshotId("snap-full")
                .role("response")
                .contentType("application/json")
                .encoding("zstd")
                .compressedPayload(compressionService.compress(payloadText.getBytes(StandardCharsets.UTF_8)))
                .originalSize((long) payloadText.length())
                .compressedSize(128L)
                .build();
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("span-root")
                .name("response.route")
                .kind(TraceSpanKind.OUTBOUND)
                .statusCode(TraceStatusCode.OK)
                .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                .endedAt(Instant.parse("2026-03-20T10:00:01Z"))
                .snapshots(List.of(snapshot))
                .build();
        AgentSession session = AgentSession.builder()
                .id("s-snapshot-export")
                .channelType("web")
                .chatId("chat-3")
                .createdAt(Instant.parse("2026-03-20T09:59:00Z"))
                .updatedAt(Instant.parse("2026-03-20T10:00:01Z"))
                .traces(List.of(TraceRecord.builder()
                        .traceId("trace-export")
                        .rootSpanId("span-root")
                        .traceName("web.message")
                        .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                        .endedAt(Instant.parse("2026-03-20T10:00:01Z"))
                        .spans(List.of(span))
                        .build()))
                .messages(List.of())
                .build();
        when(sessionPort.get("s-snapshot-export")).thenReturn(Optional.of(session));

        StepVerifier.create(controller.exportSessionTraceSnapshotPayload("s-snapshot-export", "snap-full"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(payloadText, response.getBody());
                    assertNotNull(response.getHeaders().getContentDisposition());
                    assertEquals("application/json", response.getHeaders().getContentType().toString());
                })
                .verifyComplete();
    }

    @Test
    void shouldExposeAssistantAttachmentsInSessionDetail() {
        Message msg = Message.builder()
                .id("m-image")
                .role("assistant")
                .content("Here is the screenshot")
                .metadata(Map.of(
                        "attachments", List.of(Map.of(
                                "type", "image",
                                "name", "capture.png",
                                "mimeType", "image/png",
                                "internalFilePath", ".golemcore/tool-artifacts/session/tool/capture.png",
                                "thumbnailBase64", "thumb-base64"))))
                .timestamp(Instant.now())
                .build();
        AgentSession session = AgentSession.builder()
                .id("s-image")
                .channelType("web")
                .chatId("123")
                .createdAt(Instant.now())
                .messages(List.of(msg))
                .build();
        when(sessionPort.get("s-image")).thenReturn(Optional.of(session));

        StepVerifier.create(controller.getSession("s-image"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SessionDetailDto.MessageDto message = response.getBody().getMessages().get(0);
                    assertNotNull(message.getAttachments());
                    assertEquals(1, message.getAttachments().size());
                    assertEquals("capture.png", message.getAttachments().get(0).getName());
                    assertEquals(".golemcore/tool-artifacts/session/tool/capture.png",
                            message.getAttachments().get(0).getInternalFilePath());
                    assertEquals("thumb-base64", message.getAttachments().get(0).getThumbnailBase64());
                    assertTrue(message.getAttachments().get(0).getUrl().contains("/api/files/download?path="));
                })
                .verifyComplete();
    }

    @Test
    void shouldHideInternalMessagesFromSessionDetailAndPaging() {
        Message visibleUser = Message.builder()
                .id("m-visible")
                .role("user")
                .content("hello")
                .timestamp(Instant.now())
                .build();
        Message internalUser = Message.builder()
                .id("m-internal")
                .role("user")
                .content("internal continue")
                .metadata(Map.of(ContextAttributes.MESSAGE_INTERNAL, true))
                .timestamp(Instant.now())
                .build();
        Message assistant = Message.builder()
                .id("m-assistant")
                .role("assistant")
                .content("reply")
                .timestamp(Instant.now())
                .build();
        AgentSession session = AgentSession.builder()
                .id("s-hidden")
                .channelType("web")
                .chatId("chat-hidden")
                .createdAt(Instant.now())
                .messages(List.of(visibleUser, internalUser, assistant))
                .build();
        when(sessionPort.get("s-hidden")).thenReturn(Optional.of(session));
        when(sessionPort.listAll()).thenReturn(List.of(session));

        StepVerifier.create(controller.getSession("s-hidden"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SessionDetailDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals(2, body.getMessages().size());
                    assertEquals("m-visible", body.getMessages().get(0).getId());
                    assertEquals("m-assistant", body.getMessages().get(1).getId());
                })
                .verifyComplete();

        StepVerifier.create(controller.getSessionMessages("s-hidden", 10, null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(2, response.getBody().getMessages().size());
                })
                .verifyComplete();

        StepVerifier.create(controller.listSessions(null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(2, response.getBody().get(0).getMessageCount());
                })
                .verifyComplete();
    }

    @Test
    void shouldPageVisibleMessagesAndKeepAttachmentOnlyEntries() {
        Message attachmentOnly = Message.builder()
                .id("m-1")
                .role("user")
                .content("")
                .metadata(Map.of(
                        "attachments", List.of(Map.of("type", "image", "name", "diagram.png")),
                        "clientMessageId", "client-msg-1"))
                .timestamp(Instant.now())
                .build();
        Message assistant = Message.builder()
                .id("m-2")
                .role("assistant")
                .content("Reasoned reply")
                .metadata(Map.of(
                        "model", "openai/o3-mini",
                        "modelTier", "smart",
                        "reasoning", "high"))
                .timestamp(Instant.now())
                .build();
        Message finalAssistant = Message.builder()
                .id("m-3")
                .role("assistant")
                .content("Final answer")
                .timestamp(Instant.now())
                .build();
        AgentSession session = AgentSession.builder()
                .id("s-page")
                .channelType("web")
                .chatId("chat-page")
                .createdAt(Instant.now())
                .messages(List.of(attachmentOnly, assistant, finalAssistant))
                .build();
        when(sessionPort.get("s-page")).thenReturn(Optional.of(session));

        StepVerifier.create(controller.getSessionMessages("s-page", 2, null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(2, response.getBody().getMessages().size());
                    assertTrue(response.getBody().isHasMore());
                    assertEquals("m-2", response.getBody().getOldestMessageId());
                    assertEquals("high", response.getBody().getMessages().get(0).getReasoning());
                    assertEquals("Final answer", response.getBody().getMessages().get(1).getContent());
                })
                .verifyComplete();

        StepVerifier.create(controller.getSessionMessages("s-page", 2, "m-2"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(1, response.getBody().getMessages().size());
                    SessionDetailDto.MessageDto message = response.getBody().getMessages().get(0);
                    assertEquals("[1 attachment]", message.getContent());
                    assertEquals("client-msg-1", message.getClientMessageId());
                    assertFalse(response.getBody().isHasMore());
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectUnknownBeforeMessageIdWhenPagingMessages() {
        AgentSession session = AgentSession.builder()
                .id("s-page")
                .channelType("web")
                .chatId("chat-page")
                .createdAt(Instant.now())
                .messages(List.of(Message.builder()
                        .id("m-1")
                        .role("user")
                        .content("hello")
                        .timestamp(Instant.now())
                        .build()))
                .build();
        when(sessionPort.get("s-page")).thenReturn(Optional.of(session));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.getSessionMessages("s-page", 50, "missing-message"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("beforeMessageId not found", exception.getReason());
    }

    @Test
    void shouldKeepAssistantTierNullWhenHistoryMetadataIsMissing() {
        Message msg = Message.builder()
                .id("m1")
                .role("assistant")
                .content("hello")
                .metadata(Map.of("model", "deepseek-coder-v2-lite"))
                .timestamp(Instant.now())
                .build();
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("web")
                .chatId("123")
                .createdAt(Instant.now())
                .messages(List.of(msg))
                .build();
        when(sessionPort.get("s1")).thenReturn(Optional.of(session));

        StepVerifier.create(controller.getSession("s1"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SessionDetailDto body = response.getBody();
                    assertNotNull(body);
                    assertNull(body.getMessages().get(0).getModelTier());
                })
                .verifyComplete();
    }

    @Test
    void shouldExposeSkillMetadataWhenPresent() {
        Message msg = Message.builder()
                .id("m-skill")
                .role("assistant")
                .content("hello")
                .metadata(
                        Map.of(ContextAttributes.ACTIVE_SKILL_NAME, "golemcore/superpowers/superpowers-code-reviewer"))
                .timestamp(Instant.now())
                .build();
        AgentSession session = AgentSession.builder()
                .id("s-skill")
                .channelType("web")
                .chatId("123")
                .createdAt(Instant.now())
                .messages(List.of(msg))
                .build();
        when(sessionPort.get("s-skill")).thenReturn(Optional.of(session));

        StepVerifier.create(controller.getSession("s-skill"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SessionDetailDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals(
                            "golemcore/superpowers/superpowers-code-reviewer",
                            body.getMessages().get(0).getSkill());
                })
                .verifyComplete();
    }

    @Test
    void shouldExposeAutoRunMetadataInSessionDetail() {
        Message msg = Message.builder()
                .id("m-auto")
                .role("assistant")
                .content("auto result")
                .metadata(Map.of(
                        ContextAttributes.AUTO_MODE, true,
                        ContextAttributes.AUTO_RUN_ID, "run-1",
                        ContextAttributes.AUTO_SCHEDULE_ID, "sched-1",
                        ContextAttributes.AUTO_GOAL_ID, "goal-1",
                        ContextAttributes.AUTO_TASK_ID, "task-1"))
                .timestamp(Instant.now())
                .build();
        AgentSession session = AgentSession.builder()
                .id("s-auto")
                .channelType("web")
                .chatId("123")
                .createdAt(Instant.now())
                .messages(List.of(msg))
                .build();
        when(sessionPort.get("s-auto")).thenReturn(Optional.of(session));

        StepVerifier.create(controller.getSession("s-auto"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SessionDetailDto body = response.getBody();
                    assertNotNull(body);
                    SessionDetailDto.MessageDto detailMessage = body.getMessages().get(0);
                    assertTrue(detailMessage.isAutoMode());
                    assertEquals("run-1", detailMessage.getAutoRunId());
                    assertEquals("sched-1", detailMessage.getAutoScheduleId());
                    assertEquals("goal-1", detailMessage.getAutoGoalId());
                    assertEquals("task-1", detailMessage.getAutoTaskId());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturn404ForMissingSession() {
        when(sessionPort.get("unknown")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSession("unknown"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Session not found", ex.getReason());
    }

    @Test
    void shouldDeleteSession() {
        StepVerifier.create(controller.deleteSession("s1"))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode()))
                .verifyComplete();
        verify(sessionPort).delete("s1");
    }

    @Test
    void shouldCompactSession() {
        when(sessionPort.compactMessages("s1", 20)).thenReturn(5);

        StepVerifier.create(controller.compactSession("s1", 20))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(5, response.getBody().get("removed"));
                })
                .verifyComplete();
    }

    @Test
    void shouldClearSession() {
        StepVerifier.create(controller.clearSession("s1"))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode()))
                .verifyComplete();
        verify(sessionPort).clearMessages("s1");
    }

    @Test
    void shouldReturnRecentSessionsWithActiveFlag() {
        AgentSession session = AgentSession.builder()
                .id("web:abc-session")
                .channelType("web")
                .chatId("abc-session")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .messages(List.of())
                .build();
        when(sessionPort.listByChannelType("web")).thenReturn(List.of(session));
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        when(pointerService.getActiveConversationKey("web|admin|client-1"))
                .thenReturn(Optional.of("abc-session"));

        Principal principal = () -> "admin";
        StepVerifier.create(controller.listRecentSessions("web", 5, "client-1", null, principal))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<SessionSummaryDto> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(1, body.size());
                    assertEquals("abc-session", body.get(0).getConversationKey());
                    assertTrue(body.get(0).isActive());
                })
                .verifyComplete();
    }

    @Test
    void shouldFilterTelegramRecentSessionsByTransportChatId() {
        AgentSession first = AgentSession.builder()
                .id("telegram:conv-1")
                .channelType("telegram")
                .chatId("conv-1")
                .metadata(Map.of(
                        "session.transport.chat.id", "100",
                        "session.conversation.key", "conv-1"))
                .updatedAt(Instant.now())
                .messages(List.of())
                .build();
        when(sessionPort.listByChannelTypeAndTransportChatId("telegram", "100")).thenReturn(List.of(first));
        when(pointerService.buildTelegramPointerKey("100")).thenReturn("telegram|100");
        when(pointerService.getActiveConversationKey("telegram|100")).thenReturn(Optional.of("conv-1"));

        StepVerifier.create(controller.listRecentSessions("telegram", 5, null, "100", null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<SessionSummaryDto> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(1, body.size());
                    assertEquals("conv-1", body.get(0).getConversationKey());
                    assertEquals("100", body.get(0).getTransportChatId());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnActiveSessionFromPointer() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        when(pointerService.getActiveConversationKey("web|admin|client-1"))
                .thenReturn(Optional.of("active-session"));
        AgentSession existing = AgentSession.builder()
                .id("web:active-session")
                .channelType("web")
                .chatId("active-session")
                .messages(List.of())
                .build();
        when(sessionPort.get("web:active-session")).thenReturn(Optional.of(existing));

        Principal principal = () -> "admin";
        StepVerifier.create(controller.getActiveSession("web", "client-1", null, principal))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    ActiveSessionResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("active-session", body.getConversationKey());
                })
                .verifyComplete();
    }

    @Test
    void shouldSetActiveSessionForWeb() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");

        ActiveSessionRequest request = ActiveSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey("session-1234")
                .build();
        Principal principal = () -> "admin";

        StepVerifier.create(controller.setActiveSession(request, principal))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    ActiveSessionResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("session-1234", body.getConversationKey());
                })
                .verifyComplete();

        verify(pointerService).setActiveConversationKey("web|admin|client-1", "session-1234");
    }

    @Test
    void shouldCreateSessionAndActivate() {
        AgentSession session = AgentSession.builder()
                .id("web:new-session")
                .channelType("web")
                .chatId("new-session")
                .createdAt(Instant.now())
                .messages(List.of())
                .build();

        when(sessionPort.getOrCreate("web", "new-session")).thenReturn(session);
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");

        CreateSessionRequest request = CreateSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey("new-session")
                .activate(true)
                .build();
        Principal principal = () -> "admin";

        StepVerifier.create(controller.createSession(request, principal))
                .assertNext(response -> {
                    assertEquals(HttpStatus.CREATED, response.getStatusCode());
                    SessionSummaryDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals("new-session", body.getConversationKey());
                    assertTrue(body.isActive());
                })
                .verifyComplete();

        verify(sessionPort).save(session);
        verify(pointerService).setActiveConversationKey("web|admin|client-1", "new-session");
    }

    @Test
    void shouldRejectSetActiveWhenConversationKeyMissing() {
        ActiveSessionRequest request = ActiveSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey(" ")
                .build();
        Principal principal = () -> "admin";

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.setActiveSession(request, principal));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void shouldRejectSetActiveWhenConversationKeyInvalid() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        ActiveSessionRequest request = ActiveSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey("bad:key")
                .build();
        Principal principal = () -> "admin";

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.setActiveSession(request, principal));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void shouldRejectRecentSessionsWithoutWebClientInstanceId() {
        Principal principal = () -> "admin";

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.listRecentSessions("web", 5, null, null, principal));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void shouldRejectActiveSessionWithoutTelegramTransportChatId() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.getActiveSession("telegram", null, null, null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void shouldRejectCreateSessionForNonWebChannel() {
        CreateSessionRequest request = CreateSessionRequest.builder()
                .channelType("telegram")
                .conversationKey("conv-1")
                .build();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.createSession(request, () -> "admin"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void shouldCreateSessionWithoutActivationWhenRequested() {
        AgentSession session = AgentSession.builder()
                .id("web:new-passive")
                .channelType("web")
                .chatId("new-passive")
                .createdAt(Instant.now())
                .messages(List.of())
                .build();

        when(sessionPort.getOrCreate("web", "new-passive")).thenReturn(session);

        CreateSessionRequest request = CreateSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey("new-passive")
                .activate(false)
                .build();

        StepVerifier.create(controller.createSession(request, () -> "admin"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.CREATED, response.getStatusCode());
                    SessionSummaryDto body = response.getBody();
                    assertNotNull(body);
                    assertFalse(body.isActive());
                })
                .verifyComplete();

        verify(pointerService, never()).setActiveConversationKey("web|admin|client-1", "new-passive");
    }

    @Test
    void shouldRejectActiveSessionRequestWhenPrincipalMissing() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.getActiveSession("web", "client-1", null, null));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    @Test
    void shouldRepairPointerWhenActiveConversationDoesNotExist() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        when(pointerService.getActiveConversationKey("web|admin|client-1"))
                .thenReturn(Optional.of("missing-session"));
        when(sessionPort.get("web:missing-session")).thenReturn(Optional.empty());

        AgentSession fallback = AgentSession.builder()
                .id("web:valid-session-123")
                .channelType("web")
                .chatId("valid-session-123")
                .updatedAt(Instant.parse("2026-02-22T10:00:00Z"))
                .messages(List.of())
                .build();
        when(sessionPort.listByChannelType("web")).thenReturn(List.of(fallback));

        StepVerifier.create(controller.getActiveSession("web", "client-1", null, () -> "admin"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    ActiveSessionResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("valid-session-123", body.getConversationKey());
                })
                .verifyComplete();

        verify(pointerService).setActiveConversationKey("web|admin|client-1", "valid-session-123");
    }

    @Test
    void shouldCreateAndActivateFreshConversationWhenPointerIsStaleAndNoFallbackExists() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        when(pointerService.getActiveConversationKey("web|admin|client-1"))
                .thenReturn(Optional.of("missing-session"));
        when(sessionPort.get("web:missing-session")).thenReturn(Optional.empty());
        when(sessionPort.listByChannelType("web")).thenReturn(List.of());
        when(sessionPort.getOrCreate(eq("web"), any(String.class))).thenAnswer(invocation -> AgentSession.builder()
                .id("web:" + invocation.getArgument(1))
                .channelType("web")
                .chatId(invocation.getArgument(1))
                .messages(List.of())
                .build());

        StepVerifier.create(controller.getActiveSession("web", "client-1", null, () -> "admin"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    ActiveSessionResponse body = response.getBody();
                    assertNotNull(body);
                    assertTrue(body.getConversationKey().length() >= 8);
                })
                .verifyComplete();

        ArgumentCaptor<String> conversationCaptor = ArgumentCaptor.forClass(String.class);
        verify(pointerService).setActiveConversationKey(eq("web|admin|client-1"), conversationCaptor.capture());
        verify(sessionPort).save(any(AgentSession.class));
        assertTrue(conversationCaptor.getValue().length() >= 8);
    }

    @Test
    void shouldRepointMatchingPointersWhenDeletingSession() {
        AgentSession deleted = AgentSession.builder()
                .id("web:to-delete")
                .channelType("web")
                .chatId("to-delete")
                .messages(List.of())
                .build();
        AgentSession fallback = AgentSession.builder()
                .id("web:latest-session")
                .channelType("web")
                .chatId("latest-session")
                .updatedAt(Instant.parse("2026-02-22T10:00:00Z"))
                .messages(List.of())
                .build();

        when(sessionPort.get("web:to-delete")).thenReturn(Optional.of(deleted));
        when(sessionPort.listByChannelType("web")).thenReturn(List.of(fallback));
        when(pointerService.getPointersSnapshot()).thenReturn(Map.of(
                "web|admin|client-1", "to-delete",
                "web|other|client-2", "other-session"));

        StepVerifier.create(controller.deleteSession("web:to-delete"))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode()))
                .verifyComplete();

        verify(pointerService).setActiveConversationKey("web|admin|client-1", "latest-session");
        verify(pointerService, never()).setActiveConversationKey("web|other|client-2", "latest-session");
    }

    @Test
    void shouldRejectCreateSessionWithTooShortConversationKey() {
        CreateSessionRequest request = CreateSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey("short_7")
                .build();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.createSession(request, () -> "admin"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void shouldAllowSetActiveForExistingLegacyConversationKey() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        AgentSession legacy = AgentSession.builder()
                .id("web:legacy7")
                .channelType("web")
                .chatId("legacy7")
                .messages(List.of())
                .build();
        when(sessionPort.get("web:legacy7")).thenReturn(Optional.of(legacy));

        ActiveSessionRequest request = ActiveSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey("legacy7")
                .build();

        StepVerifier.create(controller.setActiveSession(request, () -> "admin"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("legacy7", response.getBody().getConversationKey());
                })
                .verifyComplete();

        verify(pointerService).setActiveConversationKey("web|admin|client-1", "legacy7");
    }

    @Test
    void shouldRejectSetActiveForUnknownLegacyConversationKey() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        when(sessionPort.get("web:legacy7")).thenReturn(Optional.empty());

        ActiveSessionRequest request = ActiveSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey("legacy7")
                .build();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.setActiveSession(request, () -> "admin"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }
}
