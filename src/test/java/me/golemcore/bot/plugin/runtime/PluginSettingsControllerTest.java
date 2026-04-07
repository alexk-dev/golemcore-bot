package me.golemcore.bot.plugin.runtime;

import me.golemcore.plugin.api.extension.spi.PluginActionResult;
import me.golemcore.plugin.api.extension.spi.PluginSettingsCatalogItem;
import me.golemcore.plugin.api.extension.spi.PluginSettingsSection;
import me.golemcore.bot.telemetry.TelemetryRollupStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginSettingsControllerTest {

    private PluginSettingsRegistry registry;
    private TelemetryRollupStore telemetryRollupStore;
    private PluginSettingsController controller;

    @BeforeEach
    void setUp() {
        registry = mock(PluginSettingsRegistry.class);
        telemetryRollupStore = mock(TelemetryRollupStore.class);
        controller = new PluginSettingsController(registry, telemetryRollupStore);
    }

    @Test
    void shouldListCatalogItems() {
        when(registry.listCatalogItems()).thenReturn(List.of(PluginSettingsCatalogItem.builder()
                .routeKey("plugin-golemcore-browser")
                .title("Browser")
                .build()));

        StepVerifier.create(controller.listCatalogItems())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals(1, response.getBody().size());
                    assertEquals("plugin-golemcore-browser", response.getBody().getFirst().getRouteKey());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnSectionFromRegistry() {
        when(registry.getSection("plugin-golemcore-browser")).thenReturn(PluginSettingsSection.builder()
                .routeKey("plugin-golemcore-browser")
                .build());

        StepVerifier.create(controller.getSection("plugin-golemcore-browser"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("plugin-golemcore-browser", response.getBody().getRouteKey());
                })
                .verifyComplete();
    }

    @Test
    void shouldSaveSectionValues() {
        when(registry.saveSection("plugin-golemcore-browser", Map.of("enabled", true)))
                .thenReturn(PluginSettingsSection.builder()
                        .routeKey("plugin-golemcore-browser")
                        .values(Map.of("enabled", true))
                        .build());

        StepVerifier.create(controller.saveSection("plugin-golemcore-browser", Map.of("enabled", true)))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(Boolean.TRUE, response.getBody().getValues().get("enabled"));
                })
                .verifyComplete();

        verify(telemetryRollupStore).recordPluginSettingsSave("plugin-golemcore-browser");
    }

    @Test
    void shouldNormalizeNullActionPayloadToEmptyMap() {
        when(registry.executeAction("plugin-golemcore-browser", "reload", Map.of()))
                .thenReturn(PluginActionResult.builder().status("ok").message("reloaded").build());

        StepVerifier.create(controller.executeAction("plugin-golemcore-browser", "reload", null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("ok", response.getBody().getStatus());
                })
                .verifyComplete();

        verify(registry).executeAction("plugin-golemcore-browser", "reload", Map.of());
        verify(telemetryRollupStore).recordPluginAction("plugin-golemcore-browser", "reload");
    }
}
