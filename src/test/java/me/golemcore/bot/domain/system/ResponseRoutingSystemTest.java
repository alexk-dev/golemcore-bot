package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.VoiceResponseHandler;
import me.golemcore.bot.domain.service.VoiceResponseHandler.VoiceSendResult;
import me.golemcore.bot.port.inbound.ChannelPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
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

    private ResponseRoutingSystem system;
    private ChannelPort channelPort;
    private UserPreferencesService preferencesService;
    private VoiceResponseHandler voiceHandler;

    @BeforeEach
    void setUp() {
        channelPort = mock(ChannelPort.class);
        when(channelPort.getChannelType()).thenReturn(CHANNEL_TELEGRAM);
        when(channelPort.sendMessage(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
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

        system = new ResponseRoutingSystem(List.of(channelPort), preferencesService, voiceHandler);
    }

    private AgentContext createContext() {
        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TELEGRAM)
                .messages(new ArrayList<>())
                .build();

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

        verify(channelPort).sendMessage(eq(CHAT_ID), eq(CONTENT_RESPONSE));
        assertEquals(true, context.getAttribute(ContextAttributes.RESPONSE_SENT));
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

        verify(channelPort).sendMessage(eq(CHAT_ID), eq("Error occurred"));
        assertEquals(true, context.getAttribute(ContextAttributes.RESPONSE_SENT));
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
    }

    // ===== Send failure =====

    @Test
    void channelSendFailureSetsRoutingError() {
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")));

        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly(CONTENT_RESPONSE));

        system.process(context);

        String error = (String) context.getAttribute(ContextAttributes.ROUTING_ERROR);
        assertNotNull(error);
        assertFalse(error.isBlank());
        assertNull(context.getAttribute(ContextAttributes.RESPONSE_SENT));
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

        verify(channelPort).sendMessage(eq(CHAT_ID), eq("Hello there"));
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
        verify(channelPort).sendMessage(eq(CHAT_ID), eq("Text with voice and attachment"));
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
        verify(channelPort).sendMessage(eq(CHAT_ID), eq("Hello voice"));
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

    // ===== RESPONSE_SENT tracking =====

    @Test
    void shouldSetResponseSentOnSuccessfulTextSend() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("hello"));

        system.process(context);

        assertEquals(true, context.getAttribute(ContextAttributes.RESPONSE_SENT));
    }

    @Test
    void shouldNotSetResponseSentOnSendFailure() {
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")));

        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly(CONTENT_RESPONSE));

        system.process(context);

        assertNull(context.getAttribute(ContextAttributes.RESPONSE_SENT));
    }

    // ===== Channel registration =====

    @Test
    void registerChannelAddsNewChannel() {
        ChannelPort newChannel = mock(ChannelPort.class);
        when(newChannel.getChannelType()).thenReturn("slack");
        when(newChannel.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        system.registerChannel(newChannel);

        AgentContext context = createContext();
        context.getSession().setChannelType("slack");
        context.getSession().setChatId("slack-chat");
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("hello slack"));

        system.process(context);

        verify(newChannel).sendMessage(eq("slack-chat"), eq("hello slack"));
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
    }

    @Test
    void noOutgoingResponseDoesNothing() {
        AgentContext context = createContext();
        // No OUTGOING_RESPONSE set
        system.process(context);
        verify(channelPort, never()).sendMessage(anyString(), anyString());
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
        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);
        context.setAttribute(ContextAttributes.VOICE_TEXT, "LEGACY_VOICE_SHOULD_BE_IGNORED");

        // Set OutgoingResponse with specific text (no voice, no attachments)
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE,
                OutgoingResponse.builder().text("ACTUAL_RESPONSE").build());

        system.process(context);

        // Only the OutgoingResponse text is sent
        verify(channelPort).sendMessage(eq(CHAT_ID), eq("ACTUAL_RESPONSE"));
        // No voice sent (OutgoingResponse.voiceRequested is false)
        verify(voiceHandler, never()).trySendVoice(any(), anyString(), anyString());
        // No attachments sent
        verify(channelPort, never()).sendPhoto(anyString(), any(byte[].class), anyString(), any());
        verify(channelPort, never()).sendDocument(anyString(), any(byte[].class), anyString(), any());
    }
}
