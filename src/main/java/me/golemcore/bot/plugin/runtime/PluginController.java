package me.golemcore.bot.plugin.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Runtime management endpoints for external plugins.
 */
@RestController
@RequestMapping("/api/plugins")
@RequiredArgsConstructor
public class PluginController {

    private final PluginManager pluginManager;
    private final PluginMarketplaceService pluginMarketplaceService;
    private final SttProviderRegistry sttProviderRegistry;
    private final TtsProviderRegistry ttsProviderRegistry;

    @GetMapping
    public Mono<ResponseEntity<List<PluginRuntimeInfo>>> listPlugins() {
        return Mono.just(ResponseEntity.ok(pluginManager.listPlugins()));
    }

    @GetMapping("/marketplace")
    public Mono<ResponseEntity<PluginMarketplaceCatalog>> getMarketplace() {
        return Mono.just(ResponseEntity.ok(pluginMarketplaceService.getCatalog()));
    }

    @PostMapping("/marketplace/install")
    public Mono<ResponseEntity<PluginInstallResult>> installPlugin(@RequestBody PluginInstallRequest request) {
        return Mono.just(ResponseEntity.ok(
                pluginMarketplaceService.install(request.getPluginId(), request.getVersion())));
    }

    @PostMapping("/reload")
    public Mono<ResponseEntity<Map<String, String>>> reloadAll() {
        pluginManager.reloadAll();
        return Mono.just(ResponseEntity.ok(Map.of("status", "reloaded")));
    }

    @PostMapping("/{pluginId}/reload")
    public Mono<ResponseEntity<Map<String, Object>>> reloadPlugin(@PathVariable String pluginId) {
        boolean loaded = pluginManager.reloadPlugin(pluginId);
        return Mono.just(ResponseEntity.ok(Map.of(
                "status", loaded ? "reloaded" : "missing",
                "pluginId", pluginId)));
    }

    @GetMapping("/voice/providers")
    public Mono<ResponseEntity<Map<String, Object>>> listVoiceProviders() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "stt", sttProviderRegistry.listProviderIds(),
                "tts", ttsProviderRegistry.listProviderIds())));
    }
}
