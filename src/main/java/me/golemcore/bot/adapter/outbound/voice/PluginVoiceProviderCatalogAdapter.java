package me.golemcore.bot.adapter.outbound.voice;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.plugin.runtime.SttProviderRegistry;
import me.golemcore.bot.plugin.runtime.TtsProviderRegistry;
import me.golemcore.bot.port.outbound.VoiceProviderCatalogPort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PluginVoiceProviderCatalogAdapter implements VoiceProviderCatalogPort {

    private final SttProviderRegistry sttProviderRegistry;
    private final TtsProviderRegistry ttsProviderRegistry;

    @Override
    public boolean hasSttProvider(String providerId) {
        return providerId != null && sttProviderRegistry.find(providerId).isPresent();
    }

    @Override
    public boolean hasTtsProvider(String providerId) {
        return providerId != null && ttsProviderRegistry.find(providerId).isPresent();
    }

    @Override
    public String firstSttProviderId() {
        return sttProviderRegistry.listProviderIds().keySet().stream().findFirst().orElse(null);
    }

    @Override
    public String firstTtsProviderId() {
        return ttsProviderRegistry.listProviderIds().keySet().stream().findFirst().orElse(null);
    }
}
