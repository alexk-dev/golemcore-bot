package me.golemcore.bot.plugin.runtime;

import me.golemcore.plugin.api.extension.spi.PluginActionResult;
import me.golemcore.plugin.api.extension.spi.PluginSettingsCatalogItem;
import me.golemcore.plugin.api.extension.spi.PluginSettingsSection;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import me.golemcore.bot.port.outbound.TelemetryRollupPort;

import java.util.List;
import java.util.Map;

/**
 * Generic settings API for plugin-contributed sections and actions.
 */
@RestController
@RequestMapping("/api/plugins/settings")
public class PluginSettingsController {

    private final PluginSettingsRegistry pluginSettingsRegistry;
    private final TelemetryRollupPort telemetryRollupStore;

    public PluginSettingsController(
            PluginSettingsRegistry pluginSettingsRegistry,
            TelemetryRollupPort telemetryRollupStore) {
        this.pluginSettingsRegistry = pluginSettingsRegistry;
        this.telemetryRollupStore = telemetryRollupStore;
    }

    @GetMapping("/catalog")
    public Mono<ResponseEntity<List<PluginSettingsCatalogItem>>> listCatalogItems() {
        return Mono.just(ResponseEntity.ok(pluginSettingsRegistry.listCatalogItems()));
    }

    @GetMapping("/sections/{routeKey}")
    public Mono<ResponseEntity<PluginSettingsSection>> getSection(@PathVariable String routeKey) {
        return Mono.just(ResponseEntity.ok(pluginSettingsRegistry.getSection(routeKey)));
    }

    @PutMapping("/sections/{routeKey}")
    public Mono<ResponseEntity<PluginSettingsSection>> saveSection(
            @PathVariable String routeKey,
            @RequestBody Map<String, Object> values) {
        PluginSettingsSection section = pluginSettingsRegistry.saveSection(routeKey, values);
        telemetryRollupStore.recordPluginSettingsSave(routeKey);
        return Mono.just(ResponseEntity.ok(section));
    }

    @PostMapping("/sections/{routeKey}/actions/{actionId}")
    public Mono<ResponseEntity<PluginActionResult>> executeAction(
            @PathVariable String routeKey,
            @PathVariable String actionId,
            @RequestBody(required = false) Map<String, Object> payload) {
        PluginActionResult result = pluginSettingsRegistry.executeAction(
                routeKey,
                actionId,
                payload != null ? payload : Map.of());
        telemetryRollupStore.recordPluginAction(routeKey, actionId);
        return Mono.just(ResponseEntity.ok(result));
    }
}
