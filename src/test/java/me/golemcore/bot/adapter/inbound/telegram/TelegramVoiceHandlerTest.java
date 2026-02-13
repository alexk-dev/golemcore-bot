package me.golemcore.bot.adapter.inbound.telegram;

import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.VoicePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramVoiceHandlerTest {

    private VoicePort voicePort;
    private BotProperties properties;
    private TelegramVoiceHandler handler;

    @BeforeEach
    void setUp() {
        voicePort = mock(VoicePort.class);
        properties = new BotProperties();
        properties.getVoice().setEnabled(true);
        properties.getVoice().setApiKey("test-key");

        handler = new TelegramVoiceHandler(voicePort, properties);
    }

    // ===== handleIncomingVoice =====

    @Test
    void handleIncomingVoice_success() throws Exception {
        VoicePort.TranscriptionResult transcription = new VoicePort.TranscriptionResult(
                "Hello world", "en", 0.95f, Duration.ofSeconds(2), Collections.emptyList());
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.transcribe(any(byte[].class), any(AudioFormat.class)))
                .thenReturn(CompletableFuture.completedFuture(transcription));

        String result = handler.handleIncomingVoice(new byte[] { 1, 2, 3 }).get();

        assertEquals("Hello world", result);
        verify(voicePort).transcribe(eq(new byte[] { 1, 2, 3 }), eq(AudioFormat.OGG_OPUS));
    }

    @Test
    void handleIncomingVoice_voiceDisabled() throws Exception {
        properties.getVoice().setEnabled(false);

        String result = handler.handleIncomingVoice(new byte[] { 1 }).join();

        assertEquals("[Voice messages disabled]", result);
        verify(voicePort, never()).transcribe(any(), any());
    }

    @Test
    void handleIncomingVoice_voiceUnavailable() throws Exception {
        when(voicePort.isAvailable()).thenReturn(false);

        String result = handler.handleIncomingVoice(new byte[] { 1 }).join();

        assertEquals("[Voice processing unavailable]", result);
        verify(voicePort, never()).transcribe(any(), any());
    }

    @Test
    void handleIncomingVoice_transcriptionFails() throws Exception {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.transcribe(any(byte[].class), any(AudioFormat.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("STT error")));

        String result = handler.handleIncomingVoice(new byte[] { 1 }).join();

        assertEquals("[Failed to transcribe voice message]", result);
    }

    @Test
    void handleIncomingVoice_emptyTranscription() throws Exception {
        VoicePort.TranscriptionResult transcription = new VoicePort.TranscriptionResult(
                "", "en", 0.0f, Duration.ZERO, Collections.emptyList());
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.transcribe(any(byte[].class), any(AudioFormat.class)))
                .thenReturn(CompletableFuture.completedFuture(transcription));

        String result = handler.handleIncomingVoice(new byte[] { 1 }).join();

        assertEquals("", result);
    }

    // ===== processVoiceMessage =====

    @Test
    void processVoiceMessage_buildsCorrectMessage() throws Exception {
        VoicePort.TranscriptionResult transcription = new VoicePort.TranscriptionResult(
                "Transcribed text", "ru", 0.9f, Duration.ofSeconds(5), Collections.emptyList());
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.transcribe(any(byte[].class), any(AudioFormat.class)))
                .thenReturn(CompletableFuture.completedFuture(transcription));

        byte[] voiceData = new byte[] { 10, 20, 30 };
        Message msg = handler.processVoiceMessage("chat42", voiceData).join();

        assertEquals("telegram", msg.getChannelType());
        assertEquals("chat42", msg.getChatId());
        assertEquals("user", msg.getRole());
        assertEquals("Transcribed text", msg.getContent());
        assertEquals("Transcribed text", msg.getVoiceTranscription());
        assertArrayEquals(voiceData, msg.getVoiceData());
        assertEquals(AudioFormat.OGG_OPUS, msg.getAudioFormat());
        assertNotNull(msg.getTimestamp());
    }

    @Test
    void processVoiceMessage_failedTranscriptionStillBuildsMessage() throws Exception {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.transcribe(any(byte[].class), any(AudioFormat.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("STT error")));

        Message msg = assertDoesNotThrow(() -> handler.processVoiceMessage("chat1", new byte[] { 1 }).join());

        assertEquals("[Failed to transcribe voice message]", msg.getContent());
        assertEquals("[Failed to transcribe voice message]", msg.getVoiceTranscription());
        assertEquals("user", msg.getRole());
    }

    @Test
    void processVoiceMessage_disabledVoiceStillBuildsMessage() throws Exception {
        properties.getVoice().setEnabled(false);

        Message msg = handler.processVoiceMessage("chat1", new byte[] { 1 }).join();

        assertEquals("[Voice messages disabled]", msg.getContent());
        assertEquals("[Voice messages disabled]", msg.getVoiceTranscription());
    }
}
