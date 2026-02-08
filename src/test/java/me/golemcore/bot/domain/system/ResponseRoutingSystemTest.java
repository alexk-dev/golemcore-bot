package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.*;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.VoiceResponseHandler;
import me.golemcore.bot.infrastructure.config.BotProperties;
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

    private ResponseRoutingSystem system;
    private ChannelPort channelPort;
    private UserPreferencesService preferencesService;
    private VoiceResponseHandler voiceHandler;
    private BotProperties properties;

    @BeforeEach
    void setUp() {
        channelPort = mock(ChannelPort.class);
        when(channelPort.getChannelType()).thenReturn("telegram");
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

        properties = new BotProperties();

        system = new ResponseRoutingSystem(List.of(channelPort), preferencesService,
                voiceHandler, properties);
    }

    private AgentContext createContext() {
        AgentSession session = AgentSession.builder()
                .id("test-session")
                .chatId("chat1")
                .channelType("telegram")
                .messages(new ArrayList<>())
                .build();

        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();
    }

    @Test
    void sendsPendingImageAttachment() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Here is the screenshot").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1, 2, 3 })
                .filename("screenshot.png")
                .mimeType("image/png")
                .caption("A screenshot")
                .build();

        List<Attachment> pending = new ArrayList<>();
        pending.add(attachment);
        context.setAttribute("pendingAttachments", pending);

        system.process(context);

        verify(channelPort).sendMessage(eq("chat1"), eq("Here is the screenshot"));
        verify(channelPort).sendPhoto(eq("chat1"), eq(new byte[] { 1, 2, 3 }), eq("screenshot.png"),
                eq("A screenshot"));
    }

    @Test
    void sendsPendingDocumentAttachment() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Here is the file").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.DOCUMENT)
                .data(new byte[] { 4, 5, 6 })
                .filename("report.pdf")
                .mimeType("application/pdf")
                .caption("The report")
                .build();

        List<Attachment> pending = new ArrayList<>();
        pending.add(attachment);
        context.setAttribute("pendingAttachments", pending);

        system.process(context);

        verify(channelPort).sendDocument(eq("chat1"), eq(new byte[] { 4, 5, 6 }), eq("report.pdf"), eq("The report"));
    }

    @Test
    void sendsMultipleAttachments() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Multiple files").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

        List<Attachment> pending = new ArrayList<>();
        pending.add(Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename("img.png")
                .mimeType("image/png")
                .build());
        pending.add(Attachment.builder()
                .type(Attachment.Type.DOCUMENT)
                .data(new byte[] { 2 })
                .filename("doc.pdf")
                .mimeType("application/pdf")
                .build());
        context.setAttribute("pendingAttachments", pending);

        system.process(context);

        verify(channelPort).sendPhoto(eq("chat1"), any(byte[].class), eq("img.png"), isNull());
        verify(channelPort).sendDocument(eq("chat1"), any(byte[].class), eq("doc.pdf"), isNull());
    }

    @Test
    void pendingAttachmentsClearedAfterSending() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Done").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

        List<Attachment> pending = new ArrayList<>();
        pending.add(Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename("img.png")
                .mimeType("image/png")
                .build());
        context.setAttribute("pendingAttachments", pending);

        system.process(context);

        assertNull(context.getAttribute("pendingAttachments"));
    }

    @Test
    void shouldProcessWithPendingAttachmentsOnly() {
        AgentContext context = createContext();
        List<Attachment> pending = new ArrayList<>();
        pending.add(Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename("img.png")
                .mimeType("image/png")
                .build());
        context.setAttribute("pendingAttachments", pending);

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWithNothing() {
        AgentContext context = createContext();
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void attachmentsSentEvenOnLlmError() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.LLM_ERROR, "model crashed");

        List<Attachment> pending = new ArrayList<>();
        pending.add(Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename("img.png")
                .mimeType("image/png")
                .caption("Before error")
                .build());
        context.setAttribute("pendingAttachments", pending);

        system.process(context);

        verify(channelPort).sendMessage(eq("chat1"), anyString()); // error message
        verify(channelPort).sendPhoto(eq("chat1"), any(byte[].class), eq("img.png"), eq("Before error"));
    }

    @Test
    void attachmentSendFailureDoesNotBreakResponse() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("text").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

        when(channelPort.sendPhoto(anyString(), any(byte[].class), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("upload failed")));

        List<Attachment> pending = new ArrayList<>();
        pending.add(Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename("img.png")
                .mimeType("image/png")
                .build());
        context.setAttribute("pendingAttachments", pending);

        assertDoesNotThrow(() -> system.process(context));
        verify(channelPort).sendMessage(eq("chat1"), eq("text"));
    }

    // ===== Edge Cases =====

    @Test
    void orderIsSixty() {
        assertEquals(60, system.getOrder());
    }

    @Test
    void nameIsResponseRoutingSystem() {
        assertEquals("ResponseRoutingSystem", system.getName());
    }

    @Test
    void skipsResponseWhenPipelineTransitionPending() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("response text").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        context.setAttribute("skill.transition.target", "next-skill");

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void autoModeMessageStoresInSessionOnly() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("auto response").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

        Message autoMsg = Message.builder()
                .role("user")
                .content("auto task")
                .timestamp(Instant.now())
                .metadata(Map.of("auto.mode", true))
                .build();
        context.getMessages().add(autoMsg);

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
        assertFalse(context.getSession().getMessages().isEmpty());
    }

    @Test
    void shouldProcessWithLlmError() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.LLM_ERROR, "model error");

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWithLlmResponse() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, LlmResponse.builder().content("hi").build());

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void sendsLlmErrorToUser() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.LLM_ERROR, "model crashed");

        system.process(context);

        verify(channelPort).sendMessage(eq("chat1"), eq("Error occurred"));
    }

    @Test
    void nullResponseContentSkipsRouting() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, LlmResponse.builder().content(null).build());

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void blankResponseContentSkipsRouting() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, LlmResponse.builder().content("   ").build());

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void unknownChannelTypeDoesNotThrow() {
        AgentContext context = createContext();
        context.getSession().setChannelType("unknown_channel");
        context.setAttribute(ContextAttributes.LLM_RESPONSE, LlmResponse.builder().content("response").build());

        assertDoesNotThrow(() -> system.process(context));
        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void channelSendFailureSetsRoutingError() {
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")));

        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, LlmResponse.builder().content("response").build());

        system.process(context);

        String error = (String) context.getAttribute("routing.error");
        assertNotNull(error);
        assertTrue(error.contains("send failed"));
    }

    @Test
    void toolCallsPendingSkipsResponse() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder()
                .content("Let me check")
                .toolCalls(List.of(Message.ToolCall.builder().id("tc1").name("shell").build()))
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        context.setAttribute(ContextAttributes.TOOLS_EXECUTED, true);

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    // ===== Voice Response Tests =====

    @Test
    void sendsVoiceAfterTextWhenVoiceRequested() {
        when(voiceHandler.isAvailable()).thenReturn(true);
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(true);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Hello there").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);

        system.process(context);

        verify(channelPort).sendMessage(eq("chat1"), eq("Hello there"));
        verify(voiceHandler).trySendVoice(eq(channelPort), eq("chat1"), eq("Hello there"));
    }

    @Test
    void sendsVoiceWithCustomTextFromTool() {
        when(voiceHandler.isAvailable()).thenReturn(true);
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(true);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Full response with details").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);
        context.setAttribute(ContextAttributes.VOICE_TEXT, "Short spoken version");

        system.process(context);

        verify(voiceHandler).trySendVoice(eq(channelPort), eq("chat1"), eq("Short spoken version"));
    }

    @Test
    void sendsVoiceForIncomingVoiceMessage() {
        when(voiceHandler.isAvailable()).thenReturn(true);
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(true);
        properties.getVoice().getTelegram().setRespondWithVoice(true);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Voice reply").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

        context.getMessages().add(Message.builder()
                .role("user")
                .content("transcribed text")
                .voiceData(new byte[] { 10, 20 })
                .audioFormat(AudioFormat.OGG_OPUS)
                .timestamp(Instant.now())
                .build());

        system.process(context);

        verify(voiceHandler).trySendVoice(eq(channelPort), eq("chat1"), eq("Voice reply"));
    }

    @Test
    void doesNotSendVoiceForNormalTextMessage() {
        when(voiceHandler.isAvailable()).thenReturn(true);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("normal response").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

        context.getMessages().add(Message.builder()
                .role("user")
                .content("hello")
                .timestamp(Instant.now())
                .build());

        system.process(context);

        verify(voiceHandler, never()).trySendVoice(any(), anyString(), anyString());
    }

    @Test
    void voiceSynthesisFailureDoesNotBreakResponse() {
        when(voiceHandler.isAvailable()).thenReturn(true);
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(false);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("response text").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);

        assertDoesNotThrow(() -> system.process(context));
        verify(channelPort).sendMessage(eq("chat1"), eq("response text"));
    }

    // ===== Voice Prefix Detection Tests =====

    @Test
    void voicePrefixSendsVoiceInsteadOfText() {
        when(voiceHandler.isAvailable()).thenReturn(true);
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(true);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("\uD83D\uDD0A Hello, this is voice").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

        system.process(context);

        verify(voiceHandler).trySendVoice(eq(channelPort), eq("chat1"), eq("Hello, this is voice"));
        verify(channelPort, never()).sendMessage(anyString(), anyString());
        assertFalse(context.getSession().getMessages().isEmpty());
        assertEquals("Hello, this is voice", context.getSession().getMessages().get(0).getContent());
    }

    @Test
    void voicePrefixWithTtsFailureFallsBackToText() {
        when(voiceHandler.isAvailable()).thenReturn(true);
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(false);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("\uD83D\uDD0A Fallback text").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

        system.process(context);

        verify(channelPort).sendMessage(eq("chat1"), eq("Fallback text"));
    }

    @Test
    void noPrefixSendsNormalText() {
        when(voiceHandler.isAvailable()).thenReturn(true);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Normal response").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

        system.process(context);

        verify(channelPort).sendMessage(eq("chat1"), eq("Normal response"));
        verify(voiceHandler, never()).trySendVoice(any(), anyString(), anyString());
    }

    @Test
    void voicePrefixIgnoredWhenVoiceNotAvailable() {
        when(voiceHandler.isAvailable()).thenReturn(false);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("\uD83D\uDD0A Should be text").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

        system.process(context);

        verify(channelPort).sendMessage(eq("chat1"), eq("\uD83D\uDD0A Should be text"));
        verify(voiceHandler, never()).trySendVoice(any(), anyString(), anyString());
    }

    @Test
    void voicePrefixPrefersToolVoiceText() {
        when(voiceHandler.isAvailable()).thenReturn(true);
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(true);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content(
                "\uD83D\uDD0A \u0441\u043E\u043E\u0431\u0449\u0435\u043D\u0438\u0435 \u043E\u0442\u043F\u0440\u0430\u0432\u043B\u0435\u043D\u043E")
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);
        context.setAttribute(ContextAttributes.VOICE_TEXT, "This is the actual joke that should be spoken");

        system.process(context);

        verify(voiceHandler).trySendVoice(eq(channelPort), eq("chat1"),
                eq("This is the actual joke that should be spoken"));
        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    // ===== Voice-Only Response Tests (send_voice tool with blank LLM content)
    // =====

    @Test
    void voiceOnlyResponseSendsVoiceWhenNoLlmContent() {
        when(voiceHandler.sendVoiceWithFallback(any(), anyString(), anyString())).thenReturn(true);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder()
                .content("")
                .toolCalls(List.of(Message.ToolCall.builder().id("tc1").name("send_voice").build()))
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);
        context.setAttribute(ContextAttributes.VOICE_TEXT, "Here is a joke for you");

        system.process(context);

        verify(voiceHandler).sendVoiceWithFallback(eq(channelPort), eq("chat1"), eq("Here is a joke for you"));
        verify(channelPort, never()).sendMessage(anyString(), anyString());
        assertFalse(context.getSession().getMessages().isEmpty());
        assertEquals("Here is a joke for you", context.getSession().getMessages().get(0).getContent());
    }

    @Test
    void voiceOnlyFallsBackToTextWhenVoiceNotAvailable() {
        when(voiceHandler.sendVoiceWithFallback(any(), anyString(), anyString())).thenReturn(true);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);
        context.setAttribute(ContextAttributes.VOICE_TEXT, "Fallback joke text");

        system.process(context);

        verify(voiceHandler).sendVoiceWithFallback(eq(channelPort), eq("chat1"), eq("Fallback joke text"));
        assertFalse(context.getSession().getMessages().isEmpty());
        assertEquals("Fallback joke text", context.getSession().getMessages().get(0).getContent());
    }

    @Test
    void voiceOnlyFallsBackToTextOnTtsFailure() {
        when(voiceHandler.sendVoiceWithFallback(any(), anyString(), anyString())).thenReturn(false);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content(null).build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);
        context.setAttribute(ContextAttributes.VOICE_TEXT, "Joke that fails TTS");

        system.process(context);

        verify(voiceHandler).sendVoiceWithFallback(eq(channelPort), eq("chat1"), eq("Joke that fails TTS"));
        // When sendVoiceWithFallback returns false, no assistant message added
        assertTrue(context.getSession().getMessages().isEmpty());
    }

    @Test
    void shouldProcessWithVoiceRequestedOnly() {
        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void hasVoicePrefixDetectsPrefix() {
        assertTrue(system.hasVoicePrefix("\uD83D\uDD0A Hello"));
        assertTrue(system.hasVoicePrefix("  \uD83D\uDD0A Hello with leading spaces"));
        assertFalse(system.hasVoicePrefix("Normal text"));
        assertFalse(system.hasVoicePrefix("Hello \uD83D\uDD0A not at start"));
        assertFalse(system.hasVoicePrefix(null));
        assertFalse(system.hasVoicePrefix(""));
    }

    @Test
    void stripVoicePrefixRemovesPrefix() {
        assertEquals("Hello", system.stripVoicePrefix("\uD83D\uDD0A Hello"));
        assertEquals("Hello", system.stripVoicePrefix("  \uD83D\uDD0A Hello"));
        assertEquals("", system.stripVoicePrefix("\uD83D\uDD0A"));
        assertEquals("Normal text", system.stripVoicePrefix("Normal text"));
    }

    // ===== Edge Cases: Voice =====

    @Test
    void voiceOnlySkippedWhenVoiceTextBlank() {
        // VOICE_REQUESTED=true but VOICE_TEXT="" → should NOT enter voice-only path
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);
        context.setAttribute(ContextAttributes.VOICE_TEXT, "");

        system.process(context);

        // Blank voiceText → voice-only guard fails, blank content guard hits → no
        // output
        verify(voiceHandler, never()).sendVoiceWithFallback(any(), anyString(), anyString());
        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void voiceOnlySkippedWhenVoiceTextNull() {
        // VOICE_REQUESTED=true but VOICE_TEXT=null → should NOT enter voice-only path
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);
        // VOICE_TEXT not set (null)

        system.process(context);

        verify(voiceHandler, never()).sendVoiceWithFallback(any(), anyString(), anyString());
        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void voicePrefixWithWhitespaceOnlyAfterEmoji() {
        when(voiceHandler.isAvailable()).thenReturn(true);
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(true);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("\uD83D\uDD0A   ").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

        system.process(context);

        // stripVoicePrefix returns "" → trySendVoice called with empty string
        verify(voiceHandler).trySendVoice(eq(channelPort), eq("chat1"), eq(""));
    }

    @Test
    void voiceAndAttachmentsTogether() {
        when(voiceHandler.isAvailable()).thenReturn(true);
        when(voiceHandler.trySendVoice(any(), anyString(), anyString())).thenReturn(true);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Text with voice and attachment").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);

        List<Attachment> pending = new ArrayList<>();
        pending.add(Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename("img.png")
                .mimeType("image/png")
                .build());
        context.setAttribute("pendingAttachments", pending);

        system.process(context);

        // Text sent
        verify(channelPort).sendMessage(eq("chat1"), eq("Text with voice and attachment"));
        // Voice sent after text
        verify(voiceHandler).trySendVoice(eq(channelPort), eq("chat1"),
                eq("Text with voice and attachment"));
        // Attachment also sent
        verify(channelPort).sendPhoto(eq("chat1"), any(byte[].class), eq("img.png"), isNull());
    }

    @Test
    void voiceOnlyWithAttachments() {
        when(voiceHandler.sendVoiceWithFallback(any(), anyString(), anyString())).thenReturn(true);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);
        context.setAttribute(ContextAttributes.VOICE_TEXT, "Voice text");

        List<Attachment> pending = new ArrayList<>();
        pending.add(Attachment.builder()
                .type(Attachment.Type.DOCUMENT)
                .data(new byte[] { 2 })
                .filename("doc.pdf")
                .mimeType("application/pdf")
                .build());
        context.setAttribute("pendingAttachments", pending);

        system.process(context);

        verify(voiceHandler).sendVoiceWithFallback(eq(channelPort), eq("chat1"), eq("Voice text"));
        verify(channelPort).sendDocument(eq("chat1"), any(byte[].class), eq("doc.pdf"), isNull());
        assertNull(context.getAttribute("pendingAttachments"));
    }

    @Test
    void autoModeWithVoicePrefixStoresWithoutPrefix() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder()
                .content("\uD83D\uDD0A Auto voice response").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);

        Message autoMsg = Message.builder()
                .role("user")
                .content("auto task")
                .timestamp(Instant.now())
                .metadata(Map.of("auto.mode", true))
                .build();
        context.getMessages().add(autoMsg);

        system.process(context);

        // Auto mode stores raw content in session, never sends to channel
        verify(channelPort, never()).sendMessage(anyString(), anyString());
        verify(voiceHandler, never()).trySendVoice(any(), anyString(), anyString());
        assertFalse(context.getSession().getMessages().isEmpty());
        // Stores with prefix since auto mode returns early before prefix detection
        assertEquals("\uD83D\uDD0A Auto voice response",
                context.getSession().getMessages().get(0).getContent());
    }

    @Test
    void voiceAfterText_noChannelSilentlySkips() {
        when(voiceHandler.isAvailable()).thenReturn(true);

        AgentContext context = createContext();
        context.getSession().setChannelType("unknown_channel");
        LlmResponse response = LlmResponse.builder().content("response").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);

        // Unknown channel → early return, no send, no exception
        assertDoesNotThrow(() -> system.process(context));
        verify(voiceHandler, never()).trySendVoice(any(), anyString(), anyString());
    }

    @Test
    void shouldRespondWithVoice_respondWithVoiceConfigDisabled() {
        properties.getVoice().getTelegram().setRespondWithVoice(false);

        AgentContext context = createContext();
        context.getMessages().add(Message.builder()
                .role("user")
                .content("transcribed")
                .voiceData(new byte[] { 1 })
                .audioFormat(AudioFormat.OGG_OPUS)
                .timestamp(Instant.now())
                .build());

        // Config says no auto-respond → should return false
        assertFalse(system.shouldRespondWithVoice(context));
    }

    @Test
    void shouldRespondWithVoice_voiceRequestedOverridesConfig() {
        properties.getVoice().getTelegram().setRespondWithVoice(false);

        AgentContext context = createContext();
        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);

        // Explicit tool request overrides config
        assertTrue(system.shouldRespondWithVoice(context));
    }
}
