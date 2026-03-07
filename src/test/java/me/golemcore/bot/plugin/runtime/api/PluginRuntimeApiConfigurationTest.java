package me.golemcore.bot.plugin.runtime.api;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginRuntimeApiConfigurationTest {

    @Test
    void shouldPreserveHostRuntimeSectionsWhenPluginUpdatesPartialConfig() {
        PluginRuntimeApiConfiguration configuration = new PluginRuntimeApiConfiguration();
        me.golemcore.bot.domain.service.RuntimeConfigService delegate = mock(
                me.golemcore.bot.domain.service.RuntimeConfigService.class);
        PluginRuntimeApiMapper mapper = new PluginRuntimeApiMapper();

        RuntimeConfig current = RuntimeConfig.builder()
                .telegram(RuntimeConfig.TelegramConfig.builder()
                        .enabled(true)
                        .token(Secret.of("telegram-token"))
                        .allowedUsers(java.util.List.of("123"))
                        .build())
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder()
                        .balancedModel("openai/gpt-5.1")
                        .build())
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(new LinkedHashMap<>(Map.of(
                                "openai", RuntimeConfig.LlmProviderConfig.builder()
                                        .apiKey(Secret.of("openai-secret"))
                                        .build())))
                        .build())
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .sttProvider("golemcore/elevenlabs")
                        .ttsProvider("golemcore/elevenlabs")
                        .build())
                .build();
        when(delegate.getRuntimeConfig()).thenReturn(current);

        me.golemcore.plugin.api.runtime.RuntimeConfigService pluginService = configuration
                .pluginRuntimeConfigService(delegate, mapper);

        me.golemcore.plugin.api.runtime.model.RuntimeConfig incoming = me.golemcore.plugin.api.runtime.model.RuntimeConfig
                .builder()
                .voice(me.golemcore.plugin.api.runtime.model.RuntimeConfig.VoiceConfig.builder()
                        .sttProvider("golemcore/whisper")
                        .ttsProvider("golemcore/elevenlabs")
                        .whisperSttUrl("http://localhost:5092")
                        .build())
                .build();

        pluginService.updateRuntimeConfig(incoming);

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(delegate).updateRuntimeConfig(captor.capture());
        RuntimeConfig saved = captor.getValue();

        assertEquals("openai/gpt-5.1", saved.getModelRouter().getBalancedModel());
        assertEquals("openai-secret", saved.getLlm().getProviders().get("openai").getApiKey().getValue());
        assertEquals("telegram-token", saved.getTelegram().getToken().getValue());
        assertTrue(saved.getTelegram().getAllowedUsers().contains("123"));
        assertEquals("golemcore/whisper", saved.getVoice().getSttProvider());
        assertEquals("http://localhost:5092", saved.getVoice().getWhisperSttUrl());
    }
}
