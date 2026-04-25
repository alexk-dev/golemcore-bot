package me.golemcore.bot.integration;

import me.golemcore.bot.port.outbound.OllamaProcessPort;
import me.golemcore.bot.port.outbound.OllamaRuntimeApiPort;
import me.golemcore.bot.port.outbound.VoiceProviderCatalogPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import static org.mockito.Mockito.mock;

@TestConfiguration(proxyBeanMethods = false)
@Profile("integration-test")
class GolemCoreBotIntegrationTestConfiguration {

    @Bean
    @Primary
    VoiceProviderCatalogPort voiceProviderCatalogPort() {
        return new StaticVoiceProviderCatalogPort();
    }

    @Bean
    @Primary
    OllamaRuntimeApiPort testOllamaRuntimeApiPort() {
        return mock(OllamaRuntimeApiPort.class);
    }

    @Bean
    @Primary
    OllamaProcessPort testOllamaProcessPort() {
        return mock(OllamaProcessPort.class);
    }

    private static final class StaticVoiceProviderCatalogPort implements VoiceProviderCatalogPort {

        @Override
        public boolean hasSttProvider(String providerId) {
            return "golemcore/elevenlabs".equals(providerId) || "golemcore/whisper".equals(providerId);
        }

        @Override
        public boolean hasTtsProvider(String providerId) {
            return "golemcore/elevenlabs".equals(providerId) || "golemcore/whisper".equals(providerId);
        }

        @Override
        public String firstSttProviderId() {
            return "golemcore/elevenlabs";
        }

        @Override
        public String firstTtsProviderId() {
            return "golemcore/elevenlabs";
        }
    }
}
