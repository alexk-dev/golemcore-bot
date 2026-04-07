package me.golemcore.bot.infrastructure.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.plugin.runtime.PluginManager;
import me.golemcore.bot.plugin.runtime.PluginRuntimeInfo;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryHeartbeatSchedulerTest {

    private RuntimeConfigService runtimeConfigService;
    private ModelConfigService modelConfigService;
    private PluginManager pluginManager;
    private TelemetryEventPublisher publisher;
    private TelemetryHeartbeatScheduler heartbeatScheduler;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        modelConfigService = mock(ModelConfigService.class);
        pluginManager = mock(PluginManager.class);
        publisher = mock(TelemetryEventPublisher.class);

        StoragePort storagePort = mock(StoragePort.class);
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);
        when(storagePort.exists(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(false));
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));

        Clock clock = Clock.fixed(Instant.parse("2026-04-06T10:00:00Z"), ZoneOffset.UTC);
        TelemetryRollupStore store = new TelemetryRollupStore(runtimeConfigService, storagePort, new ObjectMapper(),
                clock);

        RuntimeConfig runtimeConfig = new RuntimeConfig();
        RuntimeConfig.ModelRouterConfig routerConfig = RuntimeConfig.ModelRouterConfig.builder()
                .tiers(new LinkedHashMap<>(Map.of(
                        "balanced", RuntimeConfig.TierBinding.builder().build(),
                        "smart", RuntimeConfig.TierBinding.builder().build())))
                .build();
        runtimeConfig.setModelRouter(routerConfig);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.isTelegramEnabled()).thenReturn(true);
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(false);
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);

        ModelConfigService.ModelSettings modelSettings = new ModelConfigService.ModelSettings();
        when(modelConfigService.getAllModels()).thenReturn(
                new LinkedHashMap<>(Map.of("gpt-4o", modelSettings, "claude-sonnet", modelSettings)));

        PluginRuntimeInfo plugin = PluginRuntimeInfo.builder().id("weather").name("Weather").build();
        when(pluginManager.listPlugins()).thenReturn(List.of(plugin));

        heartbeatScheduler = new TelemetryHeartbeatScheduler(store, publisher, runtimeConfigService,
                modelConfigService, pluginManager);
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldPublishHeartbeatWithFlatProperties() {
        heartbeatScheduler.sendHeartbeatNow();

        ArgumentCaptor<Map<String, Object>> propertiesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishAnonymousEvent(eq("instance_heartbeat"), anyString(), propertiesCaptor.capture());

        Map<String, Object> properties = propertiesCaptor.getValue();
        assertNotNull(properties.get("os_name"));
        assertNotNull(properties.get("java_version"));
        assertEquals(List.of("weather"), properties.get("enabled_plugins"));
        assertEquals(true, properties.get("feature_telegram_enabled"));
        assertEquals(false, properties.get("feature_voice_enabled"));
        assertEquals(true, properties.get("feature_selfevolving_enabled"));
        assertEquals(true, properties.get("feature_telemetry_enabled"));

        List<String> modelIds = (List<String>) properties.get("model_ids");
        assertEquals(2, modelIds.size());

        List<String> tiers = (List<String>) properties.get("tiers");
        assertEquals(2, tiers.size());
    }
}
