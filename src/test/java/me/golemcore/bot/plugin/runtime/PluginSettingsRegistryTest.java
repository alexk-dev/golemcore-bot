package me.golemcore.bot.plugin.runtime;

import me.golemcore.plugin.api.extension.spi.PluginActionResult;
import me.golemcore.plugin.api.extension.spi.PluginDescriptor;
import me.golemcore.plugin.api.extension.spi.PluginSettingsCatalogItem;
import me.golemcore.plugin.api.extension.spi.PluginSettingsContributor;
import me.golemcore.plugin.api.extension.spi.PluginSettingsSection;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginSettingsRegistryTest {

    @Test
    void shouldBuildStableRouteKeyWithoutRegexArtifacts() {
        assertEquals("plugin-golemcore-browser",
                PluginSettingsRegistry.buildRouteKey("golemcore/browser", "main"));
        assertEquals("plugin-golemcore-browser-advanced-settings",
                PluginSettingsRegistry.buildRouteKey("golemcore/browser", " Advanced settings!!! "));
    }

    @Test
    void shouldDecorateSectionsAndDelegateActions() {
        PluginSettingsRegistry registry = new PluginSettingsRegistry();
        PluginDescriptor descriptor = PluginDescriptor.builder()
                .id("golemcore/browser")
                .name("Browser")
                .provider("golemcore")
                .build();
        PluginSettingsContributor contributor = new StubContributor();

        registry.replaceContributors(descriptor, List.of(contributor));

        List<PluginSettingsCatalogItem> catalogItems = registry.listCatalogItems();
        assertEquals(1, catalogItems.size());
        PluginSettingsCatalogItem catalogItem = catalogItems.getFirst();
        assertEquals("plugin-golemcore-browser-advanced-settings", catalogItem.getRouteKey());
        assertEquals("Plugins", catalogItem.getBlockTitle());

        PluginSettingsSection section = registry.getSection(catalogItem.getRouteKey());
        assertEquals("golemcore/browser", section.getPluginId());
        assertEquals("Browser", section.getPluginName());
        assertEquals("golemcore", section.getProvider());
        assertEquals("advanced-settings", section.getSectionKey());
        assertEquals("Advanced Settings", section.getTitle());
        assertEquals("Stored in plugin config", section.getDescription());

        PluginSettingsSection saved = registry.saveSection(catalogItem.getRouteKey(), Map.of("enabled", true));
        assertEquals(Boolean.TRUE, saved.getValues().get("enabled"));
        assertEquals("plugin-golemcore-browser-advanced-settings", saved.getRouteKey());

        PluginActionResult actionResult = registry.executeAction(catalogItem.getRouteKey(), "reload",
                Map.of("force", true));
        assertEquals("ok", actionResult.getStatus());
        assertEquals("reload", actionResult.getMessage());
    }

    private static final class StubContributor implements PluginSettingsContributor {

        @Override
        public String getPluginId() {
            return "golemcore/browser";
        }

        @Override
        public List<PluginSettingsCatalogItem> getCatalogItems() {
            return List.of(PluginSettingsCatalogItem.builder()
                    .sectionKey("advanced-settings")
                    .title("Advanced Settings")
                    .description("Stored in plugin config")
                    .build());
        }

        @Override
        public PluginSettingsSection getSection(String sectionKey) {
            return PluginSettingsSection.builder()
                    .sectionKey(sectionKey)
                    .build();
        }

        @Override
        public PluginSettingsSection saveSection(String sectionKey, Map<String, Object> values) {
            return PluginSettingsSection.builder()
                    .sectionKey(sectionKey)
                    .values(new LinkedHashMap<>(values))
                    .build();
        }

        @Override
        public PluginActionResult executeAction(String sectionKey, String actionId, Map<String, Object> payload) {
            return PluginActionResult.builder()
                    .status("ok")
                    .message(actionId)
                    .build();
        }
    }
}
