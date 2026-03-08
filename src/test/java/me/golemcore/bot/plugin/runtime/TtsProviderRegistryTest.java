package me.golemcore.bot.plugin.runtime;

import me.golemcore.plugin.api.extension.port.outbound.VoicePort;
import me.golemcore.plugin.api.extension.spi.TtsProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsProviderRegistryTest {

    @Test
    void shouldFindProviderByIdOrAliasIgnoringCase() {
        TtsProviderRegistry registry = new TtsProviderRegistry();
        TestTtsProvider provider = new TestTtsProvider("golemcore/elevenlabs", Set.of("elevenlabs", "voice"));
        registry.replaceProviders("plugin-a", List.of(provider));

        assertSame(provider, registry.find("golemcore/elevenlabs").orElseThrow());
        assertSame(provider, registry.find("  ELEVENLABS ").orElseThrow());
        assertSame(provider, registry.find("VOICE").orElseThrow());
        assertTrue(registry.find(null).isEmpty());
        assertTrue(registry.find("   ").isEmpty());
    }

    @Test
    void shouldReplaceRemoveAndListProviders() {
        TtsProviderRegistry registry = new TtsProviderRegistry();
        TestTtsProvider original = new TestTtsProvider("golemcore/elevenlabs", Set.of());
        TestTtsProvider replacement = new TestTtsProvider("golemcore/custom-voice", Set.of("custom"));
        TestTtsProvider secondPlugin = new TestTtsProvider("golemcore/backup-voice", Set.of());

        registry.replaceProviders("plugin-a", List.of(original));
        registry.replaceProviders("plugin-a", List.of(replacement));
        registry.replaceProviders("plugin-b", List.of(secondPlugin));

        assertTrue(registry.find("golemcore/elevenlabs").isEmpty());
        assertSame(replacement, registry.find("custom").orElseThrow());
        assertEquals(Map.of(
                "golemcore/custom-voice", "golemcore/custom-voice",
                "golemcore/backup-voice", "golemcore/backup-voice"), registry.listProviderIds());

        registry.removeProviders("plugin-a");

        assertFalse(registry.find("golemcore/custom-voice").isPresent());
        assertSame(secondPlugin, registry.find("golemcore/backup-voice").orElseThrow());
    }

    private static final class TestTtsProvider implements TtsProvider {

        private final String providerId;
        private final Set<String> aliases;

        private TestTtsProvider(String providerId, Set<String> aliases) {
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
        public CompletableFuture<byte[]> synthesize(String text, VoicePort.VoiceConfig config) {
            return CompletableFuture.completedFuture(new byte[] { 1, 2, 3 });
        }
    }
}
