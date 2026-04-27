package me.golemcore.bot.application.models;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;
import me.golemcore.bot.domain.model.catalog.ModelReasoningLevel;
import me.golemcore.bot.domain.model.catalog.ModelReasoningProfile;
import org.junit.jupiter.api.Test;

class ModelCatalogContractTest {

    @Test
    void shouldExposeModelRegistryDefaultsThroughDomainCatalogEntry() {
        ModelCatalogEntry entry = new ModelCatalogEntry(
                "openai",
                "GPT-5.1",
                true,
                false,
                1_000_000,
                new ModelReasoningProfile(
                        "medium",
                        Map.of("low", new ModelReasoningLevel(1_000_000))));

        ModelRegistryService.ResolveResult result = new ModelRegistryService.ResolveResult(
                entry,
                "shared",
                "remote-hit");

        assertSame(entry, result.defaultCatalogEntry());
    }

    @Test
    void shouldExposeDiscoveredModelDefaultsThroughDomainCatalogEntry() {
        ModelCatalogEntry entry = new ModelCatalogEntry(
                "openrouter",
                "OpenAI: GPT-5",
                true,
                false,
                400_000,
                new ModelReasoningProfile(
                        "medium",
                        Map.of("medium", new ModelReasoningLevel(500_000))));

        ProviderModelDiscoveryService.DiscoveredModel discoveredModel = new ProviderModelDiscoveryService.DiscoveredModel(
                "openrouter",
                "openai/gpt-5",
                "OpenAI: GPT-5",
                "openai",
                entry);

        assertSame(entry, discoveredModel.defaultCatalogEntry());
    }
}
