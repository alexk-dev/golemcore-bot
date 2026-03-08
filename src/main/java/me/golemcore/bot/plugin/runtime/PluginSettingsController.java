package me.golemcore.bot.plugin.runtime;

import lombok.RequiredArgsConstructor;
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

import java.util.List;
import java.util.Map;

/**
 * Generic settings API for plugin-contributed sections and actions.
 */
@RestController
@RequestMapping("/api/plugins/settings")
@RequiredArgsConstructor
public class PluginSettingsController {

    private final PluginSettingsRegistry pluginSettingsRegistry;

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
        return Mono.just(ResponseEntity.ok(pluginSettingsRegistry.saveSection(routeKey, values)));
    }

    @PostMapping("/sections/{routeKey}/actions/{actionId}")
    public Mono<ResponseEntity<PluginActionResult>> executeAction(
            @PathVariable String routeKey,
            @PathVariable String actionId,
            @RequestBody(required = false) Map<String, Object> payload) {
        return Mono.just(ResponseEntity.ok(pluginSettingsRegistry.executeAction(
                routeKey,
                actionId,
                payload != null ? payload : Map.of())));
    }
}
