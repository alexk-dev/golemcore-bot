package me.golemcore.bot.plugin.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;
import me.golemcore.bot.port.outbound.TelemetryRollupPort;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginControllerTest {

    private PluginManager pluginManager;
    private PluginMarketplaceService pluginMarketplaceService;
    private SttProviderRegistry sttProviderRegistry;
    private TtsProviderRegistry ttsProviderRegistry;
    private TelemetryRollupPort telemetryRollupStore;
    private PluginController controller;

    @BeforeEach
    void setUp() {
        pluginManager = mock(PluginManager.class);
        pluginMarketplaceService = mock(PluginMarketplaceService.class);
        sttProviderRegistry = mock(SttProviderRegistry.class);
        ttsProviderRegistry = mock(TtsProviderRegistry.class);
        telemetryRollupStore = mock(TelemetryRollupPort.class);
        controller = new PluginController(pluginManager, pluginMarketplaceService, sttProviderRegistry,
                ttsProviderRegistry, telemetryRollupStore);
    }

    @Test
    void shouldListPluginsFromManager() {
        when(pluginManager.listPlugins()).thenReturn(List.of(PluginRuntimeInfo.builder()
                .id("golemcore/browser")
                .version("1.0.0")
                .loaded(true)
                .build()));

        StepVerifier.create(controller.listPlugins())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals(1, response.getBody().size());
                    assertEquals("golemcore/browser", response.getBody().getFirst().getId());
                })
                .verifyComplete();
    }

    @Test
    void shouldInstallPluginFromRequest() {
        PluginInstallResult result = new PluginInstallResult("installed", null, null);
        when(pluginMarketplaceService.install("golemcore/browser", "1.0.0")).thenReturn(result);

        StepVerifier.create(controller.installPlugin(new PluginInstallRequest("golemcore/browser", "1.0.0")))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("installed", response.getBody().getStatus());
                })
                .verifyComplete();

        verify(telemetryRollupStore).recordPluginInstall("golemcore/browser");
    }

    @Test
    void shouldUninstallPluginFromRequest() {
        PluginUninstallResult result = new PluginUninstallResult("uninstalled", "Browser uninstalled.");
        when(pluginMarketplaceService.uninstall("golemcore/browser")).thenReturn(result);

        StepVerifier.create(controller.uninstallPlugin(new PluginUninstallRequest("golemcore/browser")))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("uninstalled", response.getBody().getStatus());
                })
                .verifyComplete();

        verify(telemetryRollupStore).recordPluginUninstall("golemcore/browser");
    }

    @Test
    void shouldReturnMarketplaceCatalog() {
        when(pluginMarketplaceService.getCatalog()).thenReturn(PluginMarketplaceCatalog.builder()
                .available(true)
                .message("ready")
                .sourceDirectory("plugins/marketplace")
                .build());

        StepVerifier.create(controller.getMarketplace())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertTrue(response.getBody().isAvailable());
                    assertEquals("plugins/marketplace", response.getBody().getSourceDirectory());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnReloadStatusForSinglePlugin() {
        when(pluginManager.reloadPlugin("golemcore/browser")).thenReturn(false);

        StepVerifier.create(controller.reloadPlugin("golemcore/browser"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("missing", response.getBody().get("status"));
                    assertEquals("golemcore/browser", response.getBody().get("pluginId"));
                })
                .verifyComplete();
    }

    @Test
    void shouldReloadAllPlugins() {
        StepVerifier.create(controller.reloadAll())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("reloaded", response.getBody().get("status"));
                })
                .verifyComplete();

        verify(pluginManager).reloadAll();
    }

    @Test
    void shouldListVoiceProviders() {
        when(sttProviderRegistry.listProviderIds()).thenReturn(Map.of("golemcore/whisper", "golemcore/whisper"));
        when(ttsProviderRegistry.listProviderIds()).thenReturn(Map.of("golemcore/elevenlabs", "golemcore/elevenlabs"));

        StepVerifier.create(controller.listVoiceProviders())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(Map.of("golemcore/whisper", "golemcore/whisper"), response.getBody().get("stt"));
                    assertEquals(Map.of("golemcore/elevenlabs", "golemcore/elevenlabs"),
                            response.getBody().get("tts"));
                })
                .verifyComplete();
    }
}
