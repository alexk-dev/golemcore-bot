package me.golemcore.bot.adapter.outbound.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;
import org.junit.jupiter.api.Test;

class JacksonModelRegistryDocumentAdapterTest {

    private final JacksonModelRegistryDocumentAdapter adapter = new JacksonModelRegistryDocumentAdapter();

    @Test
    void shouldParseCatalogEntry() {
        ModelCatalogEntry entry = adapter.parseCatalogEntry("""
                {
                  "provider": "openai",
                  "displayName": "GPT-5.1",
                  "supportsVision": true,
                  "supportsTemperature": false,
                  "maxInputTokens": 1000000
                }
                """);

        assertEquals("openai", entry.getProvider());
        assertEquals("GPT-5.1", entry.getDisplayName());
        assertEquals(1000000, entry.getMaxInputTokens());
    }

    @Test
    void shouldRejectInvalidCatalogEntryJson() {
        assertThrows(IllegalStateException.class, () -> adapter.parseCatalogEntry("{ invalid json"));
    }
}
