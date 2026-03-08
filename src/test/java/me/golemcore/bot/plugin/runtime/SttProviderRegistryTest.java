package me.golemcore.bot.plugin.runtime;

import me.golemcore.plugin.api.extension.model.AudioFormat;
import me.golemcore.plugin.api.extension.port.outbound.VoicePort;
import me.golemcore.plugin.api.extension.spi.SttProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SttProviderRegistryTest {

    @Test
    void shouldFindProviderByIdOrAliasIgnoringCase() {
        SttProviderRegistry registry = new SttProviderRegistry();
        TestSttProvider provider = new TestSttProvider("golemcore/whisper", Set.of("whisper", "local-whisper"));
        registry.replaceProviders("plugin-a", List.of(provider));

        assertSame(provider, registry.find("golemcore/whisper").orElseThrow());
        assertSame(provider, registry.find("  WHISPER ").orElseThrow());
        assertSame(provider, registry.find("LOCAL-WHISPER").orElseThrow());
        assertTrue(registry.find(null).isEmpty());
        assertTrue(registry.find("   ").isEmpty());
    }

    @Test
    void shouldReplaceRemoveAndListProviders() {
        SttProviderRegistry registry = new SttProviderRegistry();
        TestSttProvider original = new TestSttProvider("golemcore/whisper", Set.of());
        TestSttProvider replacement = new TestSttProvider("golemcore/parakeet", Set.of("parakeet"));
        TestSttProvider secondPlugin = new TestSttProvider("golemcore/custom", Set.of());

        registry.replaceProviders("plugin-a", List.of(original));
        registry.replaceProviders("plugin-a", List.of(replacement));
        registry.replaceProviders("plugin-b", List.of(secondPlugin));

        assertTrue(registry.find("golemcore/whisper").isEmpty());
        assertSame(replacement, registry.find("parakeet").orElseThrow());
        assertEquals(Map.of(
                "golemcore/parakeet", "golemcore/parakeet",
                "golemcore/custom", "golemcore/custom"), registry.listProviderIds());

        registry.removeProviders("plugin-a");

        assertFalse(registry.find("golemcore/parakeet").isPresent());
        assertSame(secondPlugin, registry.find("golemcore/custom").orElseThrow());
    }

    private static final class TestSttProvider implements SttProvider {

        private final String providerId;
        private final Set<String> aliases;

        private TestSttProvider(String providerId, Set<String> aliases) {
            this.providerId = providerId;
            this.aliases = aliases;
        }

        @Override
        public String getProviderId() {
            return providerId;
        }

        @Override
        public Set<String> getAliases() {
            return aliases;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public CompletableFuture<VoicePort.TranscriptionResult> transcribe(byte[] audioData, AudioFormat format) {
            return CompletableFuture.completedFuture(new VoicePort.TranscriptionResult(
                    "ok",
                    "en",
                    1.0f,
                    Duration.ZERO,
                    List.of()));
        }
    }
}
