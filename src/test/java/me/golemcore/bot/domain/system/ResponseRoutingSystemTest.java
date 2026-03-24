package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RoutingOutcome;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.TraceBudgetService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.TraceSnapshotCompressionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.VoiceResponseHandler;
import me.golemcore.bot.domain.service.VoiceResponseHandler.VoiceSendResult;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.inbound.ChannelPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResponseRoutingSystemTest {

    private static final String CHAT_ID = "chat1";
    private static final String SESSION_ID = "test-session";
    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final String CHANNEL_UNKNOWN = "unknown_channel";
    private static final String MIME_IMAGE_PNG = "image/png";
    private static final String FILENAME_IMG_PNG = "img.png";
    private static final String FILENAME_DOC_PDF = "doc.pdf";
    private static final String ROLE_USER = "user";
    private static final String MSG_VOICE_QUOTA = "Voice quota exceeded!";
    private static final String CONTENT_RESPONSE = "response";
    private static final String VOICE_TEXT_CONTENT = "Voice text";
    private static final String ATTR_ROUTING_OUTCOME = ContextAttributes.ROUTING_OUTCOME;
    private static final String CONTENT_HELLO = "hello";

    private ResponseRoutingSystem system;
    private ChannelPort channelPort;
    private UserPreferencesService preferencesService;
    private VoiceResponseHandler voiceHandler;
    private RuntimeConfigService runtimeConfigService;
    private TraceService traceService;

    @BeforeEach
    void setUp() {
        channelPort = mock(ChannelPort.class);
        when(channelPort.getChannelType()).thenReturn(CHANNEL_TELEGRAM);
        when(channelPort.sendMessage(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(channelPort.sendMessage(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(channelPort.sendPhoto(anyString(), any(byte[].class), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(channelPort.sendDocument(anyString(), any(byte[].class), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(channelPort.sendVoice(anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(anyString())).thenReturn("Error occurred");

        voiceHandler = mock(VoiceResponseHandler.class);
        when(voiceHandler.isAvailable()).thenReturn(false);

        runtimeConfigService = mock(RuntimeConfigService.class);
        traceService = new TraceService(new TraceSnapshotCompressionService(), new TraceBudgetService());
        system = new ResponseRoutingSystem(
                new ChannelRegistry(List.of(channelPort)),
                preferencesService,
                voiceHandler,
                runtimeConfigService,
                traceService);
    }

    private AgentContext createContext() {
        return createContext(CHAT_ID, null);
    }

    private AgentContext createContext(String sessionChatId, String transportChatId) {
        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(sessionChatId)
                .channelType(CHANNEL_TELEGRAM)
                .messages(new ArrayList<>())
                .build();
        if (transportChatId != null) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(ContextAttributes.TRANSPORT_CHAT_ID, transportChatId);
            session.setMetadata(metadata);
        }

        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();
    }

    // ===== OutgoingResponse text routing =====

    @Test
    void shouldSendOutgoingResponseText() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly(CONTENT_RESPONSE));

        system.process(context);

        verify(channelPort).sendMessage(eq(CHAT_ID), eq(CONTENT_RESPONSE), any());
        RoutingOutcome outcome = context.getAttribute(ATTR_ROUTING_OUTCOME);
        assertNotNull(outcome);
        assertTrue(outcome.isSentText());
    }

    @Test
    void shouldRecordOutboundSpanWithSnapshot() {
        AgentContext context = createContext();
        TraceContext traceContext = traceService.startRootTrace(
                context.getSession(),
                TraceContext.builder()
                        .traceId("trace-1")
                        .spanId("root-span")
                        .rootKind(TraceSpanKind.INGRESS.name())
                        .build(),
                "telegram.message",
                TraceSpanKind.INGRESS,
                Instant.now(),
                Map.of("session.id", SESSION_ID));
        context.setTraceContext(traceContext);
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly(CONTENT_RESPONSE));

        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        when(runtimeConfigService.isPayloadSnapshotsEnabled()).thenReturn(true);
        when(runtimeConfigService.isTraceOutboundPayloadCaptureEnabled()).thenReturn(true);
        when(runtimeConfigService.getSessionTraceBudgetMb()).thenReturn(8);
        when(runtimeConfigService.getTraceMaxSnapshotSizeKb()).thenReturn(64);
        when(runtimeConfigService.getTraceMaxSnapshotsPerSpan()).thenReturn(4);

        system.process(context);

        TraceRecord trace = context.getSession().getTraces().get(0);
        assertTrue(trace.getSpans().stream().anyMatch(span -> "response.route".equals(span.getName())));
        assertTrue(trace.getSpans().stream()
                .filter(span -> "response.route".equals(span.getName()))
                .allMatch(span -> !span.getSnapshots().isEmpty()));
    }

    @Test
    void shouldSendOutgoingResponseTextToTransportChatIdFromMetadata() {
        AgentContext context = createContext("conversation-42", "transport-777");
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly(CONTENT_RESPONSE));

        system.process(context);

        verify(channelPort).sendMessage(eq("transport-777"), eq(CONTENT_RESPONSE), any());
    }

    @Test
    void shouldPropagateHiveRoutingHints() {
        ChannelPort hiveChannel = mock(ChannelPort.class);
        when(hiveChannel.getChannelType()).thenReturn("hive");
        when(hiveChannel.sendMessage(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        ResponseRoutingSystem hiveSystem = new ResponseRoutingSystem(
                new ChannelRegistry(List.of(hiveChannel)),
                preferencesService,
                voiceHandler);

        AgentSession session = AgentSession.builder()
                .id("hive:thread-1")
                .chatId("thread-1")
                .channelType("hive")
                .messages(new ArrayList<>())
                .build();
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.HIVE_THREAD_ID, "thread-1");
        context.setAttribute(ContextAttributes.HIVE_CARD_ID, "card-1");
        context.setAttribute(ContextAttributes.HIVE_COMMAND_ID, "cmd-1");
        context.setAttribute(ContextAttributes.HIVE_RUN_ID, "run-1");
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder()
                .text("Hive reply")
                .hints(Map.of("model", "gpt-5"))
                .build());

        hiveSystem.process(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> hintsCaptor = ArgumentCaptor.forClass((Class) Map.class);
        verify(hiveChannel).sendMessage(eq("thread-1"), eq("Hive reply"), hintsCaptor.capture());
        assertEquals("thread-1", hintsCaptor.getValue().get(ContextAttributes.HIVE_THREAD_ID));
        assertEquals("card-1", hintsCaptor.getValue().get(ContextAttributes.HIVE_CARD_ID));
        assertEquals("cmd-1", hintsCaptor.getValue().get(ContextAttributes.HIVE_COMMAND_ID));
        assertEquals("run-1", hintsCaptor.getValue().get(ContextAttributes.HIVE_RUN_ID));
        assertEquals("gpt-5", hintsCaptor.getValue().get("model"));
    }

    @Test
    void shouldSendOutgoingResponseWithDocumentAttachment() {
        AgentContext context = createContext();

        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.DOCUMENT)
                .data(new byte[] { 4, 5, 6 })
                .filename("report.pdf")
                .mimeType("application/pdf")
                .caption("The report")
                .build();

        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder()
                .text("Here is the file")
                .attachment(attachment)
                .build());

        system.process(context);

        verify(channelPort).sendDocument(eq(CHAT_ID), eq(new byte[] { 4, 5, 6 }), eq("report.pdf"), eq("The report"));
    }

    @Test
    void shouldSendAttachmentToTransportChatIdFromMetadata() {
        AgentContext context = createContext("conversation-5", "transport-5");
        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.DOCUMENT)
                .data(new byte[] { 7, 8, 9 })
                .filename("edge.pdf")
                .mimeType("application/pdf")
                .caption("Edge")
                .build();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder()
                .text("File")
                .attachment(attachment)
                .build());

        system.process(context);

        verify(channelPort).sendDocument(eq("transport-5"), eq(new byte[] { 7, 8, 9 }), eq("edge.pdf"), eq("Edge"));
    }

    @Test
    void shouldSendWebAssistantMessageWithAllAttachmentsInOnePayload() {
        ChannelPort webChannel = mock(ChannelPort.class);
        when(webChannel.getChannelType()).thenReturn("web");
        when(webChannel.sendMessage(any(Message.class))).thenReturn(CompletableFuture.completedFuture(null));

        ResponseRoutingSystem webSystem = new ResponseRoutingSystem(
                new ChannelRegistry(List.of(webChannel)),
                preferencesService,
                voiceHandler);

        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId("web-chat")
                .channelType("web")
                .messages(new ArrayList<>())
                .build();
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();

        Attachment first = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1, 2, 3 })
                .filename("first.png")
                .mimeType("image/png")
                .downloadUrl("/api/files/download?path=first")
                .internalFilePath(".golemcore/tool-artifacts/first")
                .thumbnailBase64("thumb-first")
                .build();
        Attachment second = Attachment.builder()
                .type(Attachment.Type.DOCUMENT)
                .data(new byte[] { 4, 5, 6 })
                .filename("report.pdf")
                .mimeType("application/pdf")
                .downloadUrl("/api/files/download?path=report")
                .internalFilePath(".golemcore/tool-artifacts/report")
                .build();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder()
                .text("Done")
                .attachment(first)
                .attachment(second)
                .hints(Map.of("model", "gemini-3.1-pro", "tier", "smart"))
                .build());

        webSystem.process(context);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(webChannel).sendMessage(messageCaptor.capture());
        verify(webChannel, never()).sendPhoto(anyString(), any(byte[].class), anyString(), any());
        verify(webChannel, never()).sendDocument(anyString(), any(byte[].class), anyString(), any());
        Message delivered = messageCaptor.getValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments = (List<Map<String, Object>>) delivered.getMetadata().get("attachments");
        assertEquals(2, attachments.size());
        assertEquals("first.png", attachments.get(0).get("name"));
        assertEquals(".golemcore/tool-artifacts/first", attachments.get(0).get("internalFilePath"));
        assertEquals("thumb-first", attachments.get(0).get("thumbnailBase64"));
        assertEquals("report.pdf", attachments.get(1).get("name"));
        assertEquals("smart", delivered.getMetadata().get("modelTier"));
    }

    @Test
    void shouldNotProcessWithNothing() {
        AgentContext context = createContext();
        assertFalse(system.shouldProcess(context));
    }

    // ===== Metadata =====

    @Test
    void orderIsSixty() {
        assertEquals(60, system.getOrder());
    }

    @Test
    void nameIsResponseRoutingSystem() {
        assertEquals("ResponseRoutingSystem", system.getName());
    }

    // ===== Transition =====

    @Test
    void skipsResponseWhenPipelineTransitionPending() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("response text"));
        context.setSkillTransitionRequest(me.golemcore.bot.domain.model.SkillTransitionRequest.explicit("next-skill"));

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
        verify(channelPort, never()).sendMessage(anyString(), anyString(), any());
    }

    // ===== Auto mode =====

    @Test
    void autoModeMessageDoesNotSend() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("auto response"));

        Message autoMsg = Message.builder()
                .role(ROLE_USER)
                .content("auto task")
                .timestamp(Instant.now())
                .metadata(Map.of("auto.mode", true))
                .build();
        context.getMessages().add(autoMsg);

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
        verify(channelPort, never()).sendMessage(anyString(), anyString(), any());
    }

    // ===== shouldProcess =====

    @Test
    void shouldProcessWithOutgoingResponseText() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("hi"));
        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWithOutgoingResponseVoice() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.voiceOnly("voice text"));
        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWithOutgoingResponseAttachments() {
        AgentContext context = createContext();
        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename(FILENAME_IMG_PNG)
                .mimeType(MIME_IMAGE_PNG)
                .build();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder()
                .attachment(attachment)
                .build());
        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWithoutOutgoingResponse() {
        AgentContext context = createContext();
        assertFalse(system.shouldProcess(context));
    }

    // ===== Error path (via OutgoingResponse) =====

    @Test
    void shouldSendErrorMessageViaOutgoingResponse() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("Error occurred"));

        system.process(context);

        verify(channelPort).sendMessage(eq(CHAT_ID), eq("Error occurred"), any());
        RoutingOutcome errorOutcome = context.getAttribute(ATTR_ROUTING_OUTCOME);
        assertNotNull(errorOutcome);
        assertTrue(errorOutcome.isSentText());
    }

    // ===== Blank/null content =====

    @Test
    void blankOutgoingResponseTextSkipsTextSend() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder()
                .text("   ")
                .build());
        // shouldProcess returns false for blank-only text with no voice or attachments
        assertFalse(system.shouldProcess(context));
    }

    // ===== Unknown channel =====

    @Test
    void unknownChannelTypeDoesNotThrow() {
        AgentContext context = createContext();
        context.getSession().setChannelType(CHANNEL_UNKNOWN);
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly(CONTENT_RESPONSE));

        assertDoesNotThrow(() -> system.process(context));
        verify(channelPort, never()).sendMessage(anyString(), anyString());
        verify(channelPort, never()).sendMessage(anyString(), anyString(), any());
    }

    // ===== Send failure =====

    @Test
    void channelSendFailureRecordsRoutingOutcomeWithError() {
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")));
        when(channelPort.sendMessage(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")));

        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly(CONTENT_RESPONSE));

        system.process(context);

        RoutingOutcome outcome = context.getAttribute(ATTR_ROUTING_OUTCOME);
        assertNotNull(outcome);
        assertTrue(outcome.isAttempted());
        assertFalse(outcome.isSentText());
        assertNotNull(outcome.getErrorMessage());
        assertFalse(outcome.getErrorMessage().isBlank());
    }

    // ===== Voice via OutgoingResponse =====

    @Test
    void sendsVoiceWhenOutgoingResponseHasVoiceRequested() {
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(VoiceSendResult.SUCCESS);

        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder()
                .text("Hello there")
                .voiceRequested(true)
                .build());

        system.process(context);

        verify(channelPort).sendMessage(eq(CHAT_ID), eq("Hello there"), any());
        verify(voiceHandler).trySendVoice(eq(channelPort), eq(CHAT_ID), eq("Hello there"));
    }

    @Test
    void sendsVoiceWithCustomVoiceText() {
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(VoiceSendResult.SUCCESS);

        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder()
                .text("Full response with details")
                .voiceRequested(true)
                .voiceText("Short spoken version")
                .build());

        system.process(context);

        verify(voiceHandler).trySendVoice(eq(channelPort), eq(CHAT_ID), eq("Short spoken version"));
    }

    @Test
    void voiceOnlyOutgoingResponseSendsVoice() {
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(VoiceSendResult.SUCCESS);

        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE,
                OutgoingResponse.voiceOnly("Hello from OutgoingResponse"));

        system.process(context);

        verify(voiceHandler).trySendVoice(eq(channelPort), eq(CHAT_ID), eq("Hello from OutgoingResponse"));
        verify(channelPort, never()).sendMessage(anyString(), anyString());
        verify(channelPort, never()).sendMessage(anyString(), anyString(), any());
    }

    @Test
    void voiceAndAttachmentsTogether() {
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(VoiceSendResult.SUCCESS);

        AgentContext context = createContext();

        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename(FILENAME_IMG_PNG)
                .mimeType(MIME_IMAGE_PNG)
                .build();

        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE,
                OutgoingResponse.builder()
                        .text("Text with voice and attachment")
                        .voiceRequested(true)
                        .attachment(attachment)
                        .build());

        system.process(context);

        // Text sent
        verify(channelPort).sendMessage(eq(CHAT_ID), eq("Text with voice and attachment"), any());
        // Voice sent after text
        verify(voiceHandler).trySendVoice(eq(channelPort), eq(CHAT_ID),
                eq("Text with voice and attachment"));
        // Attachment also sent
        verify(channelPort).sendPhoto(eq(CHAT_ID), any(byte[].class), eq(FILENAME_IMG_PNG), isNull());
    }

    @Test
    void voiceOnlyWithAttachments() {
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(VoiceSendResult.SUCCESS);

        AgentContext context = createContext();

        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.DOCUMENT)
                .data(new byte[] { 2 })
                .filename(FILENAME_DOC_PDF)
                .mimeType("application/pdf")
                .build();

        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE,
                OutgoingResponse.builder()
                        .voiceRequested(true)
                        .voiceText(VOICE_TEXT_CONTENT)
                        .attachment(attachment)
                        .build());

        system.process(context);

        verify(voiceHandler).trySendVoice(channelPort, CHAT_ID, VOICE_TEXT_CONTENT);
        verify(channelPort).sendDocument(eq(CHAT_ID), any(byte[].class), eq(FILENAME_DOC_PDF), isNull());
    }

    // ===== Voice quota exceeded =====

    @Test
    void voiceQuotaExceededSendsNotification() {
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(VoiceSendResult.QUOTA_EXCEEDED);
        when(preferencesService.getMessage("voice.error.quota")).thenReturn(MSG_VOICE_QUOTA);

        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder()
                .text("Hello voice")
                .voiceRequested(true)
                .build());

        system.process(context);

        // Text sent
        verify(channelPort).sendMessage(eq(CHAT_ID), eq("Hello voice"), any());
        // Quota notification sent
        verify(channelPort).sendMessage(eq(CHAT_ID), eq(MSG_VOICE_QUOTA));
    }

    @Test
    void voiceOnlyQuotaExceededSendsNotification() {
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(VoiceSendResult.QUOTA_EXCEEDED);
        when(preferencesService.getMessage("voice.error.quota")).thenReturn(MSG_VOICE_QUOTA);

        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE,
                OutgoingResponse.voiceOnly("Speak this"));

        system.process(context);

        verify(voiceHandler).trySendVoice(eq(channelPort), eq(CHAT_ID), eq("Speak this"));
        verify(channelPort).sendMessage(eq(CHAT_ID), eq(MSG_VOICE_QUOTA));
    }

    // ===== RoutingOutcome sentText tracking =====

    @Test
    void shouldRecordSentTextTrueOnSuccessfulSend() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly(CONTENT_HELLO));

        system.process(context);

        RoutingOutcome outcome = context.getAttribute(ATTR_ROUTING_OUTCOME);
        assertNotNull(outcome);
        assertTrue(outcome.isSentText());
    }

    @Test
    void shouldRecordSentTextFalseOnSendFailure() {
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")));
        when(channelPort.sendMessage(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")));

        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly(CONTENT_RESPONSE));

        system.process(context);

        RoutingOutcome outcome = context.getAttribute(ATTR_ROUTING_OUTCOME);
        assertNotNull(outcome);
        assertFalse(outcome.isSentText());
    }

    // ===== Channel registration =====

    @Test
    void registerChannelAddsNewChannel() {
        ChannelPort newChannel = mock(ChannelPort.class);
        when(newChannel.getChannelType()).thenReturn("slack");
        when(newChannel.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(newChannel.sendMessage(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        system.registerChannel(newChannel);

        AgentContext context = createContext();
        context.getSession().setChannelType("slack");
        context.getSession().setChatId("slack-chat");
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("hello slack"));

        system.process(context);

        verify(newChannel).sendMessage(eq("slack-chat"), eq("hello slack"), any());
    }

    // ===== Edge cases =====

    @Test
    void shouldProcessFalseWhenAllNull() {
        AgentContext context = createContext();
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void unknownChannelForVoiceDoesNotThrow() {
        AgentContext context = createContext();
        context.getSession().setChannelType(CHANNEL_UNKNOWN);
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE,
                OutgoingResponse.voiceOnly(VOICE_TEXT_CONTENT));

        assertDoesNotThrow(() -> system.process(context));
        verify(voiceHandler, never()).trySendVoice(any(), anyString(), anyString());
    }

    @Test
    void errorChannelUnknownDoesNotThrow() {
        AgentContext context = createContext();
        context.getSession().setChannelType(CHANNEL_UNKNOWN);
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("some error"));

        assertDoesNotThrow(() -> system.process(context));
        verify(channelPort, never()).sendMessage(anyString(), anyString());
        verify(channelPort, never()).sendMessage(anyString(), anyString(), any());
    }

    @Test
    void noOutgoingResponseDoesNothing() {
        AgentContext context = createContext();
        // No OUTGOING_RESPONSE set
        system.process(context);
        verify(channelPort, never()).sendMessage(anyString(), anyString());
        verify(channelPort, never()).sendMessage(anyString(), anyString(), any());
    }

    // ===== ADR-0002 Phase 3: regression guard =====

    @Test
    void shouldIgnoreLegacyAttributesAndRouteOnlyOutgoingResponse() {
        // Regression test: even if legacy attributes are present in context,
        // routing must send ONLY what OutgoingResponse specifies.
        AgentContext context = createContext();

        // Simulate legacy attributes that historically drove routing
        context.setAttribute("llm.response.text", "LEGACY_TEXT_SHOULD_BE_IGNORED");
        context.setAttribute("pending.attachments", List.of("LEGACY_ATTACHMENT"));
        context.setVoiceRequested(true);
        context.setVoiceText("LEGACY_VOICE_SHOULD_BE_IGNORED");

        // Set OutgoingResponse with specific text (no voice, no attachments)
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE,
                OutgoingResponse.builder().text("ACTUAL_RESPONSE").build());

        system.process(context);

        // Only the OutgoingResponse text is sent
        verify(channelPort).sendMessage(eq(CHAT_ID), eq("ACTUAL_RESPONSE"), any());
        // No voice sent (OutgoingResponse.voiceRequested is false)
        verify(voiceHandler, never()).trySendVoice(any(), anyString(), anyString());
        // No attachments sent
        verify(channelPort, never()).sendPhoto(anyString(), any(byte[].class), anyString(), any());
        verify(channelPort, never()).sendDocument(anyString(), any(byte[].class), anyString(), any());
    }

    // ===== RoutingOutcome tests =====

    @Test
    void shouldRecordRoutingOutcomeOnSuccessfulTextSend() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly(CONTENT_HELLO));

        system.process(context);

        RoutingOutcome outcome = context.getAttribute(ATTR_ROUTING_OUTCOME);
        assertNotNull(outcome);
        assertTrue(outcome.isAttempted());
        assertTrue(outcome.isSentText());
        assertFalse(outcome.isSentVoice());
        assertEquals(0, outcome.getSentAttachments());
        assertNull(outcome.getErrorMessage());
    }

    @Test
    void shouldRecordRoutingOutcomeOnTextSendFailure() {
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("network error")));
        when(channelPort.sendMessage(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("network error")));

        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly(CONTENT_RESPONSE));

        system.process(context);

        RoutingOutcome outcome = context.getAttribute(ATTR_ROUTING_OUTCOME);
        assertNotNull(outcome);
        assertTrue(outcome.isAttempted());
        assertFalse(outcome.isSentText());
        assertNotNull(outcome.getErrorMessage());
    }

    @Test
    void shouldRecordRoutingOutcomeWithVoiceAndAttachments() {
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(VoiceSendResult.SUCCESS);

        AgentContext context = createContext();
        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename(FILENAME_IMG_PNG)
                .mimeType(MIME_IMAGE_PNG)
                .build();

        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE,
                OutgoingResponse.builder()
                        .text("text")
                        .voiceRequested(true)
                        .attachment(attachment)
                        .build());

        system.process(context);

        RoutingOutcome outcome = context.getAttribute(ATTR_ROUTING_OUTCOME);
        assertNotNull(outcome);
        assertTrue(outcome.isSentText());
        assertTrue(outcome.isSentVoice());
        assertEquals(1, outcome.getSentAttachments());
    }

    @Test
    void shouldUpdateTurnOutcomeWithRoutingOutcome() {
        AgentContext context = createContext();
        TurnOutcome turnOutcome = TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .assistantText(CONTENT_HELLO)
                .build();
        context.setTurnOutcome(turnOutcome);
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly(CONTENT_HELLO));

        system.process(context);

        TurnOutcome updated = context.getTurnOutcome();
        assertNotNull(updated.getRoutingOutcome());
        assertTrue(updated.getRoutingOutcome().isSentText());
        // Original fields preserved
        assertEquals(FinishReason.SUCCESS, updated.getFinishReason());
        assertEquals(CONTENT_HELLO, updated.getAssistantText());
    }
}
