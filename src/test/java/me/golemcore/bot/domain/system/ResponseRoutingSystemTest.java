package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.*;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.VoicePort;
import me.golemcore.bot.voice.TelegramVoiceHandler;
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
    private VoicePort voicePort;
    private TelegramVoiceHandler voiceHandler;
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

        voicePort = mock(VoicePort.class);
        when(voicePort.isAvailable()).thenReturn(false);

        voiceHandler = mock(TelegramVoiceHandler.class);

        properties = new BotProperties();

        system = new ResponseRoutingSystem(List.of(channelPort), preferencesService,
                voicePort, voiceHandler, properties);
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
        context.setAttribute("llm.response", response);

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
        context.setAttribute("llm.response", response);

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
        context.setAttribute("llm.response", response);

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
        context.setAttribute("llm.response", response);

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
        // No llm.response, no llm.error, but has pending attachments
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
        context.setAttribute("llm.error", "model crashed");

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
        context.setAttribute("llm.response", response);

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

        // Should not throw
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
        context.setAttribute("llm.response", response);
        context.setAttribute("skill.transition.target", "next-skill");

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void autoModeMessageStoresInSessionOnly() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("auto response").build();
        context.setAttribute("llm.response", response);

        Message autoMsg = Message.builder()
                .role("user")
                .content("auto task")
                .timestamp(Instant.now())
                .metadata(Map.of("auto.mode", true))
                .build();
        context.getMessages().add(autoMsg);

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
        // Should be added to session
        assertFalse(context.getSession().getMessages().isEmpty());
    }

    @Test
    void shouldProcessWithLlmError() {
        AgentContext context = createContext();
        context.setAttribute("llm.error", "model error");

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWithLlmResponse() {
        AgentContext context = createContext();
        context.setAttribute("llm.response", LlmResponse.builder().content("hi").build());

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void sendsLlmErrorToUser() {
        AgentContext context = createContext();
        context.setAttribute("llm.error", "model crashed");

        system.process(context);

        verify(channelPort).sendMessage(eq("chat1"), eq("Error occurred"));
    }

    @Test
    void nullResponseContentSkipsRouting() {
        AgentContext context = createContext();
        context.setAttribute("llm.response", LlmResponse.builder().content(null).build());

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void blankResponseContentSkipsRouting() {
        AgentContext context = createContext();
        context.setAttribute("llm.response", LlmResponse.builder().content("   ").build());

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void unknownChannelTypeDoesNotThrow() {
        AgentContext context = createContext();
        context.getSession().setChannelType("unknown_channel");
        context.setAttribute("llm.response", LlmResponse.builder().content("response").build());

        assertDoesNotThrow(() -> system.process(context));
        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void channelSendFailureSetsRoutingError() {
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")));

        AgentContext context = createContext();
        context.setAttribute("llm.response", LlmResponse.builder().content("response").build());

        system.process(context);

        // CompletableFuture.get() wraps in ExecutionException, getMessage() includes
        // cause class
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
        context.setAttribute("llm.response", response);
        context.setAttribute("tools.executed", true);

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    // ===== Voice Response Tests =====

    @Test
    void sendsVoiceWhenVoiceRequested() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voiceHandler.synthesizeForTelegram(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new byte[] { 1, 2, 3 }));

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Hello there").build();
        context.setAttribute("llm.response", response);
        context.setAttribute("voiceRequested", true);

        system.process(context);

        verify(channelPort).sendMessage(eq("chat1"), eq("Hello there"));
        verify(voiceHandler).synthesizeForTelegram("Hello there");
        verify(channelPort).sendVoice(eq("chat1"), eq(new byte[] { 1, 2, 3 }));
    }

    @Test
    void sendsVoiceWithCustomTextFromTool() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voiceHandler.synthesizeForTelegram(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new byte[] { 4, 5, 6 }));

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Full response with details").build();
        context.setAttribute("llm.response", response);
        context.setAttribute("voiceRequested", true);
        context.setAttribute("voiceText", "Short spoken version");

        system.process(context);

        verify(voiceHandler).synthesizeForTelegram("Short spoken version");
        verify(channelPort).sendVoice(eq("chat1"), any(byte[].class));
    }

    @Test
    void sendsVoiceForIncomingVoiceMessage() {
        when(voicePort.isAvailable()).thenReturn(true);
        properties.getVoice().getTelegram().setRespondWithVoice(true);
        when(voiceHandler.synthesizeForTelegram(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new byte[] { 7, 8 }));

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Voice reply").build();
        context.setAttribute("llm.response", response);

        // Add a user message with voice data
        context.getMessages().add(Message.builder()
                .role("user")
                .content("transcribed text")
                .voiceData(new byte[] { 10, 20 })
                .audioFormat(AudioFormat.OGG_OPUS)
                .timestamp(Instant.now())
                .build());

        system.process(context);

        verify(voiceHandler).synthesizeForTelegram("Voice reply");
        verify(channelPort).sendVoice(eq("chat1"), any(byte[].class));
    }

    @Test
    void doesNotSendVoiceWhenNotAvailable() {
        when(voicePort.isAvailable()).thenReturn(false);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("response").build();
        context.setAttribute("llm.response", response);
        context.setAttribute("voiceRequested", true);

        system.process(context);

        verify(voiceHandler, never()).synthesizeForTelegram(anyString());
        verify(channelPort, never()).sendVoice(anyString(), any(byte[].class));
    }

    @Test
    void doesNotSendVoiceForNormalTextMessage() {
        when(voicePort.isAvailable()).thenReturn(true);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("normal response").build();
        context.setAttribute("llm.response", response);

        // Add normal text message (no voice)
        context.getMessages().add(Message.builder()
                .role("user")
                .content("hello")
                .timestamp(Instant.now())
                .build());

        system.process(context);

        verify(voiceHandler, never()).synthesizeForTelegram(anyString());
        verify(channelPort, never()).sendVoice(anyString(), any(byte[].class));
    }

    @Test
    void voiceSynthesisFailureDoesNotBreakResponse() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voiceHandler.synthesizeForTelegram(anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("TTS failed")));

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("response text").build();
        context.setAttribute("llm.response", response);
        context.setAttribute("voiceRequested", true);

        assertDoesNotThrow(() -> system.process(context));
        verify(channelPort).sendMessage(eq("chat1"), eq("response text"));
    }

    // ===== Voice Prefix Detection Tests =====

    @Test
    void voicePrefixSendsVoiceInsteadOfText() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voiceHandler.synthesizeForTelegram(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new byte[] { 10, 20, 30 }));

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("\uD83D\uDD0A Hello, this is voice").build();
        context.setAttribute("llm.response", response);

        system.process(context);

        // Voice should be sent
        verify(voiceHandler).synthesizeForTelegram("Hello, this is voice");
        verify(channelPort).sendVoice(eq("chat1"), eq(new byte[] { 10, 20, 30 }));
        // Text should NOT be sent
        verify(channelPort, never()).sendMessage(anyString(), anyString());
        // Session should have clean text (without prefix)
        assertFalse(context.getSession().getMessages().isEmpty());
        assertEquals("Hello, this is voice", context.getSession().getMessages().get(0).getContent());
    }

    @Test
    void voicePrefixWithTtsFailureFallsBackToText() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voiceHandler.synthesizeForTelegram(anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("TTS failed")));

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("\uD83D\uDD0A Fallback text").build();
        context.setAttribute("llm.response", response);

        system.process(context);

        // Text sent as fallback — without prefix
        verify(channelPort).sendMessage(eq("chat1"), eq("Fallback text"));
        // Voice send should NOT have been called (synthesis failed before send)
        verify(channelPort, never()).sendVoice(anyString(), any(byte[].class));
    }

    @Test
    void noPrefixSendsNormalText() {
        when(voicePort.isAvailable()).thenReturn(true);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Normal response").build();
        context.setAttribute("llm.response", response);

        system.process(context);

        // Normal text flow
        verify(channelPort).sendMessage(eq("chat1"), eq("Normal response"));
        // No voice
        verify(voiceHandler, never()).synthesizeForTelegram(anyString());
        verify(channelPort, never()).sendVoice(anyString(), any(byte[].class));
    }

    @Test
    void voicePrefixIgnoredWhenVoiceNotAvailable() {
        when(voicePort.isAvailable()).thenReturn(false);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("\uD83D\uDD0A Should be text").build();
        context.setAttribute("llm.response", response);

        system.process(context);

        // Sent as normal text (with prefix intact since voice not available)
        verify(channelPort).sendMessage(eq("chat1"), eq("\uD83D\uDD0A Should be text"));
        verify(voiceHandler, never()).synthesizeForTelegram(anyString());
    }

    @Test
    void voicePrefixPrefersToolVoiceText() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voiceHandler.synthesizeForTelegram(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new byte[] { 11, 22, 33 }));

        AgentContext context = createContext();
        // LLM responds with prefix + short confirmation, but tool already queued the
        // real content
        LlmResponse response = LlmResponse.builder().content("\uD83D\uDD0A сообщение отправлено").build();
        context.setAttribute("llm.response", response);
        context.setAttribute("voiceRequested", true);
        context.setAttribute("voiceText", "This is the actual joke that should be spoken");

        system.process(context);

        // Should synthesize the tool's text, NOT the prefix text
        verify(voiceHandler).synthesizeForTelegram("This is the actual joke that should be spoken");
        verify(channelPort).sendVoice(eq("chat1"), eq(new byte[] { 11, 22, 33 }));
        // Text should NOT be sent (voice succeeded)
        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    // ===== Voice-Only Response Tests (send_voice tool with blank LLM content)
    // =====

    @Test
    void voiceOnlyResponseSendsVoiceWhenNoLlmContent() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voiceHandler.synthesizeForTelegram(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new byte[] { 99, 88, 77 }));

        AgentContext context = createContext();
        // LLM response has blank content (only tool calls, no text)
        LlmResponse response = LlmResponse.builder()
                .content("")
                .toolCalls(List.of(Message.ToolCall.builder().id("tc1").name("send_voice").build()))
                .build();
        context.setAttribute("llm.response", response);
        context.setAttribute("voiceRequested", true);
        context.setAttribute("voiceText", "Here is a joke for you");

        system.process(context);

        verify(voiceHandler).synthesizeForTelegram("Here is a joke for you");
        verify(channelPort).sendVoice(eq("chat1"), eq(new byte[] { 99, 88, 77 }));
        verify(channelPort, never()).sendMessage(anyString(), anyString());
        // Assistant message should be added to session
        assertFalse(context.getSession().getMessages().isEmpty());
        assertEquals("Here is a joke for you", context.getSession().getMessages().get(0).getContent());
    }

    @Test
    void voiceOnlyFallsBackToTextWhenVoiceNotAvailable() {
        when(voicePort.isAvailable()).thenReturn(false);

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("").build();
        context.setAttribute("llm.response", response);
        context.setAttribute("voiceRequested", true);
        context.setAttribute("voiceText", "Fallback joke text");

        system.process(context);

        verify(channelPort).sendMessage(eq("chat1"), eq("Fallback joke text"));
        verify(channelPort, never()).sendVoice(anyString(), any(byte[].class));
        verify(voiceHandler, never()).synthesizeForTelegram(anyString());
        // Assistant message should be added to session
        assertFalse(context.getSession().getMessages().isEmpty());
        assertEquals("Fallback joke text", context.getSession().getMessages().get(0).getContent());
    }

    @Test
    void voiceOnlyFallsBackToTextOnTtsFailure() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voiceHandler.synthesizeForTelegram(anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("TTS error")));

        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content(null).build();
        context.setAttribute("llm.response", response);
        context.setAttribute("voiceRequested", true);
        context.setAttribute("voiceText", "Joke that fails TTS");

        system.process(context);

        verify(voiceHandler).synthesizeForTelegram("Joke that fails TTS");
        verify(channelPort).sendMessage(eq("chat1"), eq("Joke that fails TTS"));
        verify(channelPort, never()).sendVoice(anyString(), any(byte[].class));
        // Assistant message should still be added
        assertFalse(context.getSession().getMessages().isEmpty());
        assertEquals("Joke that fails TTS", context.getSession().getMessages().get(0).getContent());
    }

    @Test
    void shouldProcessWithVoiceRequestedOnly() {
        AgentContext context = createContext();
        // No llm.response, no llm.error, no pending attachments, but voiceRequested
        context.setAttribute("voiceRequested", true);

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
}
