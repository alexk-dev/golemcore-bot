package me.golemcore.bot.plugin.runtime;

import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.plugin.runtime.extension.PluginExtensionApiMapper;
import me.golemcore.bot.port.outbound.VoicePort;
import me.golemcore.plugin.api.extension.spi.SttProvider;
import me.golemcore.plugin.api.extension.spi.TtsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginVoicePortTest {

    private RuntimeConfigService runtimeConfigService;
    private SttProviderRegistry sttProviderRegistry;
    private TtsProviderRegistry ttsProviderRegistry;
    private PluginVoicePort pluginVoicePort;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        sttProviderRegistry = new SttProviderRegistry();
        ttsProviderRegistry = new TtsProviderRegistry();
        pluginVoicePort = new PluginVoicePort(
                runtimeConfigService,
                sttProviderRegistry,
                ttsProviderRegistry,
                new PluginExtensionApiMapper());
    }

    @Test
    void shouldNormalizeLegacyProviderIdsForAvailabilityAndTranscription() {
        SttProvider sttProvider = mock(SttProvider.class);
        TtsProvider ttsProvider = mock(TtsProvider.class);
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(true);
        when(runtimeConfigService.getSttProvider()).thenReturn("whisper");
        when(runtimeConfigService.getTtsProvider()).thenReturn("elevenlabs");
        when(sttProvider.getProviderId()).thenReturn("golemcore/whisper");
        when(sttProvider.isAvailable()).thenReturn(true);
        when(sttProvider.transcribe(any(byte[].class), eq(me.golemcore.plugin.api.extension.model.AudioFormat.WAV)))
                .thenReturn(CompletableFuture.completedFuture(
                        new me.golemcore.plugin.api.extension.port.outbound.VoicePort.TranscriptionResult(
                                "hello world",
                                "en",
                                0.99f,
                                Duration.ofSeconds(1),
                                List.of())));
        when(ttsProvider.getProviderId()).thenReturn("golemcore/elevenlabs");
        when(ttsProvider.isAvailable()).thenReturn(true);

        sttProviderRegistry.replaceProviders("golemcore/whisper", List.of(sttProvider));
        ttsProviderRegistry.replaceProviders("golemcore/elevenlabs", List.of(ttsProvider));

        VoicePort.TranscriptionResult result = pluginVoicePort.transcribe(new byte[] { 1, 2, 3 }, AudioFormat.WAV)
                .join();

        assertTrue(pluginVoicePort.isAvailable());
        assertEquals("hello world", result.text());
        assertEquals("en", result.language());
    }

    @Test
    void shouldTranslateAsyncQuotaFailureFromPluginFuture() {
        TtsProvider ttsProvider = mock(TtsProvider.class);
        when(runtimeConfigService.getTtsProvider()).thenReturn("golemcore/elevenlabs");
        when(ttsProvider.getProviderId()).thenReturn("golemcore/elevenlabs");
        when(ttsProvider.isAvailable()).thenReturn(true);
        when(ttsProvider.synthesize(anyString(), any(me.golemcore.plugin.api.extension.port.outbound.VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new me.golemcore.plugin.api.extension.port.outbound.VoicePort.QuotaExceededException(
                                "quota exceeded")));

        ttsProviderRegistry.replaceProviders("golemcore/elevenlabs", List.of(ttsProvider));

        CompletionException exception = assertThrows(CompletionException.class,
                () -> pluginVoicePort.synthesize("hello", VoicePort.VoiceConfig.defaultConfig()).join());

        assertTrue(exception.getCause() instanceof VoicePort.QuotaExceededException);
        assertEquals("quota exceeded", exception.getCause().getMessage());
    }

    @Test
    void shouldTranslateSynchronousQuotaFailureFromPluginCall() {
        SttProvider sttProvider = mock(SttProvider.class);
        when(runtimeConfigService.getSttProvider()).thenReturn("golemcore/whisper");
        when(sttProvider.getProviderId()).thenReturn("golemcore/whisper");
        when(sttProvider.isAvailable()).thenReturn(true);
        when(sttProvider.transcribe(any(byte[].class), any(me.golemcore.plugin.api.extension.model.AudioFormat.class)))
                .thenThrow(new me.golemcore.plugin.api.extension.port.outbound.VoicePort.QuotaExceededException(
                        "sync quota"));

        sttProviderRegistry.replaceProviders("golemcore/whisper", List.of(sttProvider));

        VoicePort.QuotaExceededException exception = assertThrows(VoicePort.QuotaExceededException.class,
                () -> pluginVoicePort.transcribe(new byte[] { 9 }, AudioFormat.MP3));

        assertEquals("sync quota", exception.getMessage());
    }

    @Test
    void shouldReturnUnavailableWhenVoiceIsDisabled() {
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(false);

        assertFalse(pluginVoicePort.isAvailable());
    }

    @Test
    void shouldFailWhenConfiguredProviderIsMissing() {
        when(runtimeConfigService.getTtsProvider()).thenReturn("golemcore/elevenlabs");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> pluginVoicePort.synthesize("hello", VoicePort.VoiceConfig.defaultConfig()));

        assertTrue(exception.getMessage().contains("No TTS provider loaded"));
    }
}
