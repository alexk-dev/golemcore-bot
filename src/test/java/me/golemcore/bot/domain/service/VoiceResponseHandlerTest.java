package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.VoicePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoiceResponseHandlerTest {

    private VoicePort voicePort;
    private BotProperties properties;
    private ChannelPort channel;
    private VoiceResponseHandler handler;

    @BeforeEach
    void setUp() {
        voicePort = mock(VoicePort.class);
        properties = new BotProperties();
        properties.getVoice().setEnabled(true);
        properties.getVoice().setVoiceId("test-voice");
        properties.getVoice().setTtsModelId("test-model");
        properties.getVoice().setSpeed(1.0f);

        channel = mock(ChannelPort.class);
        when(channel.sendVoice(anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        handler = new VoiceResponseHandler(voicePort, properties);
    }

    // ===== trySendVoice =====

    @Test
    void trySendVoice_successPath() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(new byte[] { 1, 2, 3 }));

        boolean result = handler.trySendVoice(channel, "chat1", "Hello");

        assertTrue(result);
        verify(voicePort).synthesize(eq("Hello"), any(VoicePort.VoiceConfig.class));
        verify(channel).sendVoice(eq("chat1"), eq(new byte[] { 1, 2, 3 }));
    }

    @Test
    void trySendVoice_voiceNotAvailable() {
        when(voicePort.isAvailable()).thenReturn(false);

        boolean result = handler.trySendVoice(channel, "chat1", "Hello");

        assertFalse(result);
        verify(voicePort, never()).synthesize(anyString(), any());
        verify(channel, never()).sendVoice(anyString(), any(byte[].class));
    }

    @Test
    void trySendVoice_synthesisThrows() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("TTS error")));

        boolean result = handler.trySendVoice(channel, "chat1", "Hello");

        assertFalse(result);
        verify(channel, never()).sendVoice(anyString(), any(byte[].class));
    }

    @Test
    void trySendVoice_sendVoiceThrows() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(new byte[] { 1 }));
        when(channel.sendVoice(anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")));

        boolean result = handler.trySendVoice(channel, "chat1", "Hello");

        assertFalse(result);
    }

    @Test
    void trySendVoice_usesConfigFromProperties() {
        when(voicePort.isAvailable()).thenReturn(true);
        properties.getVoice().setVoiceId("my-voice");
        properties.getVoice().setTtsModelId("my-model");
        properties.getVoice().setSpeed(1.5f);

        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenAnswer(inv -> {
                    VoicePort.VoiceConfig config = inv.getArgument(1);
                    assertEquals("my-voice", config.voiceId());
                    assertEquals("my-model", config.modelId());
                    assertEquals(1.5f, config.speed());
                    assertEquals(AudioFormat.MP3, config.outputFormat());
                    return CompletableFuture.completedFuture(new byte[] { 1 });
                });

        handler.trySendVoice(channel, "chat1", "Test");

        verify(voicePort).synthesize(eq("Test"), any(VoicePort.VoiceConfig.class));
    }

    // ===== sendVoiceWithFallback =====

    @Test
    void sendVoiceWithFallback_voiceSucceeds() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(new byte[] { 1, 2 }));

        boolean result = handler.sendVoiceWithFallback(channel, "chat1", "Hello");

        assertTrue(result);
        verify(channel).sendVoice(eq("chat1"), any(byte[].class));
        verify(channel, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void sendVoiceWithFallback_voiceFailsTextSucceeds() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("TTS failed")));

        boolean result = handler.sendVoiceWithFallback(channel, "chat1", "Hello");

        assertTrue(result);
        verify(channel).sendMessage(eq("chat1"), eq("Hello"));
    }

    @Test
    void sendVoiceWithFallback_voiceUnavailableFallsBackToText() {
        when(voicePort.isAvailable()).thenReturn(false);

        boolean result = handler.sendVoiceWithFallback(channel, "chat1", "Hello");

        assertTrue(result);
        verify(channel, never()).sendVoice(anyString(), any(byte[].class));
        verify(channel).sendMessage(eq("chat1"), eq("Hello"));
    }

    @Test
    void sendVoiceWithFallback_bothFail() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("TTS failed")));
        when(channel.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("text send failed")));

        boolean result = handler.sendVoiceWithFallback(channel, "chat1", "Hello");

        assertFalse(result);
    }

    @Test
    void isAvailable_delegatesToVoicePort() {
        when(voicePort.isAvailable()).thenReturn(true);
        assertTrue(handler.isAvailable());

        when(voicePort.isAvailable()).thenReturn(false);
        assertFalse(handler.isAvailable());
    }
}
