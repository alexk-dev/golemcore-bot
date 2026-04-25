package me.golemcore.bot.infrastructure.telemetry;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.plugin.runtime.PluginManager;
import me.golemcore.bot.plugin.runtime.PluginRuntimeInfo;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class TelemetryHeartbeatScheduler {

    private static final long HEARTBEAT_INTERVAL_MINUTES = 60;

    private final TelemetryEventPublisher telemetryEventPublisher;
    private final RuntimeConfigService runtimeConfigService;
    private final ModelConfigService modelConfigService;
    private final PluginManager pluginManager;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "telemetry-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public TelemetryHeartbeatScheduler(TelemetryEventPublisher telemetryEventPublisher,
            RuntimeConfigService runtimeConfigService,
            ModelConfigService modelConfigService,
            PluginManager pluginManager) {
        this.telemetryEventPublisher = telemetryEventPublisher;
        this.runtimeConfigService = runtimeConfigService;
        this.modelConfigService = modelConfigService;
        this.pluginManager = pluginManager;
    }

    @PostConstruct
    void start() {
        executor.scheduleAtFixedRate(this::sendHeartbeatSafely, 5, HEARTBEAT_INTERVAL_MINUTES * 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    public void sendHeartbeatNow() {
        sendSystemHeartbeat();
        sendCapabilities();
    }

    private void sendSystemHeartbeat() {
        List<String> pluginIds = pluginManager.listPlugins().stream()
                .map(PluginRuntimeInfo::getId)
                .toList();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("feature_area", "system");
        params.put("os_name", System.getProperty("os.name"));
        params.put("os_arch", System.getProperty("os.arch"));
        params.put("java_major", Runtime.version().feature());
        params.put("plugin_count", pluginIds.size());
        params.put("model_count", modelConfigService.getAllModels().size());

        telemetryEventPublisher.publishEvent("heartbeat", params);
    }

    private void sendCapabilities() {
        sendCapability("feature", "telegram", runtimeConfigService.isTelegramEnabled());
        sendCapability("feature", "voice", runtimeConfigService.isVoiceEnabled());
        sendCapability("feature", "selfevolving", runtimeConfigService.isSelfEvolvingEnabled());
        sendCapability("feature", "telemetry", runtimeConfigService.isTelemetryEnabled());

        for (PluginRuntimeInfo plugin : pluginManager.listPlugins()) {
            sendCapability("plugin", plugin.getId(), true);
        }

        for (String modelId : modelConfigService.getAllModels().keySet()) {
            sendCapability("model", modelId, true);
        }

        List<String> tiers = List.of();
        if (runtimeConfigService.getRuntimeConfig().getModelRouter() != null
                && runtimeConfigService.getRuntimeConfig().getModelRouter().getTiers() != null) {
            tiers = List.copyOf(runtimeConfigService.getRuntimeConfig().getModelRouter().getTiers().keySet());
        }
        for (String tier : tiers) {
            sendCapability("tier", tier, true);
        }
    }

    private void sendCapability(String type, String name, boolean enabled) {
        if (!enabled) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("feature_area", "config");
        params.put("capability_type", type);
        params.put("capability_name", name);
        telemetryEventPublisher.publishEvent("capability", params);
    }

    private void sendHeartbeatSafely() {
        try {
            sendHeartbeatNow();
        } catch (Exception exception) {
            log.warn("Telemetry heartbeat failed: {}", exception.getMessage()); // NOSONAR
        }
    }
}
