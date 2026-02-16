package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.VoiceResponseHandler.VoiceSendResult;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.VoicePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoiceResponseHandlerTest {

    private static final String CHAT_ID = "chat1";
    private static final String TEXT_HELLO = "Hello";

    private VoicePort voicePort;
    private RuntimeConfigService runtimeConfigService;
    private ChannelPort channel;
    private VoiceResponseHandler handler;

    @BeforeEach
    void setUp() {
        voicePort = mock(VoicePort.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getVoiceId()).thenReturn("test-voice");
        when(runtimeConfigService.getTtsModelId()).thenReturn("test-model");
        when(runtimeConfigService.getVoiceSpeed()).thenReturn(1.0f);

        channel = mock(ChannelPort.class);
        when(channel.sendVoice(anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        handler = new VoiceResponseHandler(voicePort, runtimeConfigService);
    }

    // ===== trySendVoice =====

    @Test
    void trySendVoice_successPath() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(new byte[] { 1, 2, 3 }));

        VoiceSendResult result = handler.trySendVoice(channel, CHAT_ID, TEXT_HELLO);

        assertEquals(VoiceSendResult.SUCCESS, result);
        verify(voicePort).synthesize(eq(TEXT_HELLO), any(VoicePort.VoiceConfig.class));
        verify(channel).sendVoice(eq(CHAT_ID), eq(new byte[] { 1, 2, 3 }));
    }

    @Test
    void trySendVoice_voiceNotAvailable() {
        when(voicePort.isAvailable()).thenReturn(false);

        VoiceSendResult result = handler.trySendVoice(channel, CHAT_ID, TEXT_HELLO);

        assertEquals(VoiceSendResult.FAILED, result);
        verify(voicePort, never()).synthesize(anyString(), any());
        verify(channel, never()).sendVoice(anyString(), any(byte[].class));
    }

    @Test
    void trySendVoice_synthesisThrows() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("TTS error")));

        VoiceSendResult result = handler.trySendVoice(channel, CHAT_ID, TEXT_HELLO);

        assertEquals(VoiceSendResult.FAILED, result);
        verify(channel, never()).sendVoice(anyString(), any(byte[].class));
    }

    @Test
    void trySendVoice_sendVoiceThrows() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(new byte[] { 1 }));
        when(channel.sendVoice(anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")));

        VoiceSendResult result = handler.trySendVoice(channel, CHAT_ID, TEXT_HELLO);

        assertEquals(VoiceSendResult.FAILED, result);
    }

    @Test
    void trySendVoice_quotaExceeded() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new VoicePort.QuotaExceededException("Quota exceeded")));

        VoiceSendResult result = handler.trySendVoice(channel, CHAT_ID, TEXT_HELLO);

        assertEquals(VoiceSendResult.QUOTA_EXCEEDED, result);
        verify(channel, never()).sendVoice(anyString(), any(byte[].class));
    }

    @Test
    void trySendVoice_usesConfigFromProperties() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(runtimeConfigService.getVoiceId()).thenReturn("my-voice");
        when(runtimeConfigService.getTtsModelId()).thenReturn("my-model");
        when(runtimeConfigService.getVoiceSpeed()).thenReturn(1.5f);

        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenAnswer(inv -> {
                    VoicePort.VoiceConfig config = inv.getArgument(1);
                    assertEquals("my-voice", config.voiceId());
                    assertEquals("my-model", config.modelId());
                    assertEquals(1.5f, config.speed());
                    assertEquals(AudioFormat.MP3, config.outputFormat());
                    return CompletableFuture.completedFuture(new byte[] { 1 });
                });

        handler.trySendVoice(channel, CHAT_ID, "Test");

        verify(voicePort).synthesize(eq("Test"), any(VoicePort.VoiceConfig.class));
    }

    // ===== sendVoiceWithFallback =====

    @Test
    void sendVoiceWithFallback_voiceSucceeds() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(new byte[] { 1, 2 }));

        VoiceSendResult result = handler.sendVoiceWithFallback(channel, CHAT_ID, TEXT_HELLO);

        assertEquals(VoiceSendResult.SUCCESS, result);
        verify(channel).sendVoice(eq(CHAT_ID), any(byte[].class));
        verify(channel, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void sendVoiceWithFallback_voiceFailsTextSucceeds() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("TTS failed")));

        VoiceSendResult result = handler.sendVoiceWithFallback(channel, CHAT_ID, TEXT_HELLO);

        assertEquals(VoiceSendResult.FAILED, result);
        verify(channel).sendMessage(eq(CHAT_ID), eq(TEXT_HELLO));
    }

    @Test
    void sendVoiceWithFallback_voiceUnavailableFallsBackToText() {
        when(voicePort.isAvailable()).thenReturn(false);

        VoiceSendResult result = handler.sendVoiceWithFallback(channel, CHAT_ID, TEXT_HELLO);

        assertEquals(VoiceSendResult.FAILED, result);
        verify(channel, never()).sendVoice(anyString(), any(byte[].class));
        verify(channel).sendMessage(eq(CHAT_ID), eq(TEXT_HELLO));
    }

    @Test
    void sendVoiceWithFallback_bothFail() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("TTS failed")));
        when(channel.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("text send failed")));

        VoiceSendResult result = handler.sendVoiceWithFallback(channel, CHAT_ID, TEXT_HELLO);

        assertEquals(VoiceSendResult.FAILED, result);
    }

    @Test
    void sendVoiceWithFallback_quotaExceededFallsBackToTextAndPreservesResult() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new VoicePort.QuotaExceededException("Quota exceeded")));

        VoiceSendResult result = handler.sendVoiceWithFallback(channel, CHAT_ID, TEXT_HELLO);

        assertEquals(VoiceSendResult.QUOTA_EXCEEDED, result);
        verify(channel).sendMessage(eq(CHAT_ID), eq(TEXT_HELLO));
    }

    @Test
    void isAvailable_delegatesToVoicePort() {
        when(voicePort.isAvailable()).thenReturn(true);
        assertTrue(handler.isAvailable());

        when(voicePort.isAvailable()).thenReturn(false);
        assertFalse(handler.isAvailable());
    }
}
