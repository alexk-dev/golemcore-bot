package me.golemcore.bot.plugin.runtime;

import me.golemcore.plugin.api.extension.spi.PluginActionResult;
import me.golemcore.plugin.api.extension.spi.PluginDescriptor;
import me.golemcore.plugin.api.extension.spi.PluginSettingsCatalogItem;
import me.golemcore.plugin.api.extension.spi.PluginSettingsContributor;
import me.golemcore.plugin.api.extension.spi.PluginSettingsSection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime registry for plugin-driven settings catalog and actions.
 */
@Component
public class PluginSettingsRegistry {

    private final Map<String, RegisteredContributor> contributorsByRouteKey = new LinkedHashMap<>();
    private final Map<String, List<String>> routeKeysByPlugin = new LinkedHashMap<>();

    public synchronized void replaceContributors(
            PluginDescriptor descriptor,
            Collection<PluginSettingsContributor> contributors) {
        removePlugin(descriptor.getId());
        List<String> routeKeys = new ArrayList<>();
        for (PluginSettingsContributor contributor : contributors) {
            for (PluginSettingsCatalogItem item : contributor.getCatalogItems()) {
                String routeKey = buildRouteKey(descriptor.getId(), item.getSectionKey());
                PluginSettingsCatalogItem normalized = PluginSettingsCatalogItem.builder()
                        .pluginId(descriptor.getId())
                        .pluginName(descriptor.getName())
                        .provider(descriptor.getProvider())
                        .sectionKey(item.getSectionKey())
                        .routeKey(routeKey)
                        .title(item.getTitle())
                        .description(item.getDescription())
                        .blockKey(item.getBlockKey() != null ? item.getBlockKey() : "plugins")
                        .blockTitle(item.getBlockTitle() != null ? item.getBlockTitle() : "Plugins")
                        .blockDescription(item.getBlockDescription())
                        .order(item.getOrder())
                        .build();
                contributorsByRouteKey.put(routeKey, new RegisteredContributor(contributor, normalized));
                routeKeys.add(routeKey);
            }
        }
        routeKeysByPlugin.put(descriptor.getId(), routeKeys);
    }

    public synchronized void removePlugin(String pluginId) {
        List<String> routeKeys = routeKeysByPlugin.remove(pluginId);
        if (routeKeys == null) {
            return;
        }
        routeKeys.forEach(contributorsByRouteKey::remove);
    }

    public synchronized List<PluginSettingsCatalogItem> listCatalogItems() {
        return contributorsByRouteKey.values().stream()
                .map(RegisteredContributor::catalogItem)
                .sorted(java.util.Comparator.comparing(
                        (PluginSettingsCatalogItem item) -> item.getBlockTitle() != null ? item.getBlockTitle() : "")
                        .thenComparing(item -> item.getOrder() != null ? item.getOrder() : Integer.MAX_VALUE)
                        .thenComparing(PluginSettingsCatalogItem::getTitle))
                .toList();
    }

    public synchronized PluginSettingsSection getSection(String routeKey) {
        RegisteredContributor registered = require(routeKey);
        PluginSettingsSection section = registered.contributor().getSection(registered.catalogItem().getSectionKey());
        return decorateSection(section, registered.catalogItem());
    }

    public synchronized PluginSettingsSection saveSection(String routeKey, Map<String, Object> values) {
        RegisteredContributor registered = require(routeKey);
        PluginSettingsSection section = registered.contributor()
                .saveSection(registered.catalogItem().getSectionKey(), values);
        return decorateSection(section, registered.catalogItem());
    }

    public synchronized PluginActionResult executeAction(String routeKey, String actionId,
            Map<String, Object> payload) {
        RegisteredContributor registered = require(routeKey);
        return registered.contributor().executeAction(registered.catalogItem().getSectionKey(), actionId, payload);
    }

    public static String buildRouteKey(String pluginId, String sectionKey) {
        String normalizedSectionKey = sectionKey != null ? sectionKey.trim().toLowerCase(Locale.ROOT) : "";
        String routeSeed = "main".equals(normalizedSectionKey) || "settings".equals(normalizedSectionKey)
                || normalizedSectionKey.isBlank()
                        ? "plugin-" + pluginId
                        : "plugin-" + pluginId + "-" + sectionKey;
        return sanitizeRouteKey(routeSeed);
    }

    private static String sanitizeRouteKey(String routeSeed) {
        String normalized = routeSeed.toLowerCase(Locale.ROOT);
        StringBuilder sanitized = new StringBuilder(normalized.length());
        boolean previousDash = false;
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            boolean alphanumeric = current >= 'a' && current <= 'z' || current >= '0' && current <= '9';
            if (alphanumeric) {
                sanitized.append(current);
                previousDash = false;
                continue;
            }
            if (!previousDash && !sanitized.isEmpty()) {
                sanitized.append('-');
                previousDash = true;
            }
        }
        int length = sanitized.length();
        if (length > 0 && sanitized.charAt(length - 1) == '-') {
            sanitized.setLength(length - 1);
        }
        return sanitized.toString();
    }

    private RegisteredContributor require(String routeKey) {
        RegisteredContributor registered = contributorsByRouteKey.get(routeKey);
        if (registered == null) {
            throw new IllegalArgumentException("Unknown plugin settings section: " + routeKey);
        }
        return registered;
    }

    private PluginSettingsSection decorateSection(PluginSettingsSection section, PluginSettingsCatalogItem item) {
        section.setPluginId(item.getPluginId());
        section.setPluginName(item.getPluginName());
        section.setProvider(item.getProvider());
        section.setSectionKey(item.getSectionKey());
        section.setRouteKey(item.getRouteKey());
        if (section.getTitle() == null) {
            section.setTitle(item.getTitle());
        }
        if (section.getDescription() == null) {
            section.setDescription(item.getDescription());
        }
        return section;
    }

    private record RegisteredContributor(
            PluginSettingsContributor contributor,
            PluginSettingsCatalogItem catalogItem) {
    }
}
