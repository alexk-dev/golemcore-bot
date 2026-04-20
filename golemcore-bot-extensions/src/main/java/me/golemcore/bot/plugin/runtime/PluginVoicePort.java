package me.golemcore.bot.plugin.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.port.outbound.RuntimeConfigQueryPort;
import me.golemcore.bot.plugin.runtime.extension.PluginExtensionApiMapper;
import me.golemcore.bot.port.outbound.VoicePort;
import me.golemcore.plugin.api.extension.spi.SttProvider;
import me.golemcore.plugin.api.extension.spi.TtsProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Composite voice port backed by plugin-contributed STT/TTS providers.
 */
@Component
@Primary
@RequiredArgsConstructor
@Slf4j
public class PluginVoicePort implements VoicePort {

    private static final String CANONICAL_ELEVENLABS_PROVIDER = "golemcore/elevenlabs";
    private static final String LEGACY_ELEVENLABS_PROVIDER = "elevenlabs";
    private static final String LEGACY_WHISPER_PROVIDER = "whisper";
    private static final String DEFAULT_WHISPER_PROVIDER = "golemcore/whisper";

    private final RuntimeConfigQueryPort runtimeConfigQueryPort;
    private final SttProviderRegistry sttProviderRegistry;
    private final TtsProviderRegistry ttsProviderRegistry;
    private final PluginExtensionApiMapper pluginApiMapper;

    @Override
    public CompletableFuture<TranscriptionResult> transcribe(byte[] audioData, AudioFormat format) {
        String providerId = resolveSttProviderId();
        if (providerId == null) {
            throw new IllegalStateException("No STT provider loaded");
        }
        SttProvider provider = sttProviderRegistry.find(providerId)
                .orElseThrow(() -> new IllegalStateException("No STT provider loaded for " + providerId));
        if (!provider.isAvailable()) {
            throw new IllegalStateException("STT provider is not available: " + providerId);
        }
        try {
            return adaptProviderFuture(
                    provider.transcribe(audioData, pluginApiMapper.toPluginAudioFormat(format)),
                    pluginApiMapper::toHostTranscriptionResult);
        } catch (me.golemcore.plugin.api.extension.port.outbound.VoicePort.QuotaExceededException ex) {
            throw new VoicePort.QuotaExceededException(ex.getMessage());
        }
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text, VoiceConfig config) {
        String providerId = resolveTtsProviderId();
        if (providerId == null) {
            throw new IllegalStateException("No TTS provider loaded");
        }
        TtsProvider provider = ttsProviderRegistry.find(providerId)
                .orElseThrow(() -> new IllegalStateException("No TTS provider loaded for " + providerId));
        if (!provider.isAvailable()) {
            throw new IllegalStateException("TTS provider is not available: " + providerId);
        }
        try {
            return adaptProviderFuture(
                    provider.synthesize(text, pluginApiMapper.toPluginVoiceConfig(config)),
                    bytes -> bytes);
        } catch (me.golemcore.plugin.api.extension.port.outbound.VoicePort.QuotaExceededException ex) {
            throw new VoicePort.QuotaExceededException(ex.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        if (!runtimeConfigQueryPort.isVoiceEnabled()) {
            return false;
        }
        String sttProviderId = resolveSttProviderId();
        String ttsProviderId = resolveTtsProviderId();
        return (sttProviderId != null
                && sttProviderRegistry.find(sttProviderId).map(SttProvider::isAvailable).orElse(false))
                || (ttsProviderId != null
                        && ttsProviderRegistry.find(ttsProviderId).map(TtsProvider::isAvailable).orElse(false));
    }

    private String normalizeProviderId(String rawProviderId) {
        if (rawProviderId == null || rawProviderId.isBlank()) {
            return null;
        }
        String normalized = rawProviderId.trim().toLowerCase(Locale.ROOT);
        if (LEGACY_ELEVENLABS_PROVIDER.equals(normalized)) {
            return CANONICAL_ELEVENLABS_PROVIDER;
        }
        if (LEGACY_WHISPER_PROVIDER.equals(normalized)) {
            return DEFAULT_WHISPER_PROVIDER;
        }
        return normalized;
    }

    private String resolveSttProviderId() {
        String configuredProviderId = normalizeProviderId(runtimeConfigQueryPort.getSttProvider());
        if (configuredProviderId != null) {
            return configuredProviderId;
        }
        return sttProviderRegistry.listProviderIds().keySet().stream().findFirst().orElse(null);
    }

    private String resolveTtsProviderId() {
        String configuredProviderId = normalizeProviderId(runtimeConfigQueryPort.getTtsProvider());
        if (configuredProviderId != null) {
            return configuredProviderId;
        }
        return ttsProviderRegistry.listProviderIds().keySet().stream().findFirst().orElse(null);
    }

    private <S, T> CompletableFuture<T> adaptProviderFuture(
            CompletableFuture<S> providerFuture,
            java.util.function.Function<S, T> successMapper) {
        CompletableFuture<T> mapped = new CompletableFuture<>();
        providerFuture.whenComplete((value, error) -> {
            if (error != null) {
                mapped.completeExceptionally(translatePluginVoiceFailure(error));
                return;
            }
            try {
                mapped.complete(successMapper.apply(value));
            } catch (RuntimeException ex) {
                mapped.completeExceptionally(ex);
            }
        });
        return mapped;
    }

    private Throwable translatePluginVoiceFailure(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof me.golemcore.plugin.api.extension.port.outbound.VoicePort.QuotaExceededException ex) {
            return new VoicePort.QuotaExceededException(ex.getMessage());
        }
        return cause;
    }

    private Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException completionException && completionException.getCause() != null) {
            return unwrap(completionException.getCause());
        }
        return error;
    }
}
