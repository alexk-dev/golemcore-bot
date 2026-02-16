package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.SystemHealthResponse;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * System health and status endpoints.
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final List<ChannelPort> channelPorts;
    private final BotProperties botProperties;
    private final RuntimeConfigService runtimeConfigService;
    private final StoragePort storagePort;

    @GetMapping("/health")
    public Mono<ResponseEntity<SystemHealthResponse>> health() {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

        Map<String, SystemHealthResponse.ChannelStatus> channels = new LinkedHashMap<>();
        for (ChannelPort port : channelPorts) {
            channels.put(port.getChannelType(), SystemHealthResponse.ChannelStatus.builder()
                    .type(port.getChannelType())
                    .running(port.isRunning())
                    .enabled(true)
                    .build());
        }

        SystemHealthResponse response = SystemHealthResponse.builder()
                .status("UP")
                .uptimeMs(uptimeMs)
                .channels(channels)
                .build();
        return Mono.just(ResponseEntity.ok(response));
    }

    @GetMapping("/config")
    public Mono<ResponseEntity<Map<String, Object>>> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("llmProvider", botProperties.getLlm().getProvider());
        config.put("maxIterations", botProperties.getAgent().getMaxIterations());
        config.put("memoryEnabled", botProperties.getMemory().isEnabled());
        config.put("skillsEnabled", botProperties.getSkills().isEnabled());
        config.put("voiceEnabled", runtimeConfigService.isVoiceEnabled());
        config.put("ragEnabled", botProperties.getRag().isEnabled());
        config.put("planEnabled", botProperties.getPlan().isEnabled());
        config.put("autoModeEnabled", runtimeConfigService.isAutoModeEnabled());
        config.put("dashboardEnabled", botProperties.getDashboard().isEnabled());
        return Mono.just(ResponseEntity.ok(config));
    }

    @GetMapping("/channels")
    public Mono<ResponseEntity<List<Map<String, Object>>>> getChannels() {
        List<Map<String, Object>> result = channelPorts.stream()
                .map(port -> {
                    Map<String, Object> ch = new LinkedHashMap<>();
                    ch.put("type", port.getChannelType());
                    ch.put("running", port.isRunning());
                    return ch;
                })
                .toList();
        return Mono.just(ResponseEntity.ok(result));
    }

    @GetMapping("/diagnostics")
    public Mono<ResponseEntity<Map<String, Object>>> getDiagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();

        String configuredStoragePath = botProperties.getStorage().getLocal().getBasePath();
        Path resolvedStoragePath = Paths
                .get(configuredStoragePath.replace("${user.home}", System.getProperty("user.home")))
                .toAbsolutePath().normalize();

        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("configuredBasePath", configuredStoragePath);
        storage.put("resolvedBasePath", resolvedStoragePath.toString());
        storage.put("sessionsFiles", countFiles("sessions"));
        storage.put("usageFiles", countFiles("usage"));
        diagnostics.put("storage", storage);

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("STORAGE_PATH", System.getenv("STORAGE_PATH"));
        env.put("TOOLS_WORKSPACE", System.getenv("TOOLS_WORKSPACE"));
        env.put("SPRING_PROFILES_ACTIVE", System.getenv("SPRING_PROFILES_ACTIVE"));
        diagnostics.put("environment", env);

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("userDir", System.getProperty("user.dir"));
        runtime.put("userHome", System.getProperty("user.home"));
        diagnostics.put("runtime", runtime);

        return Mono.just(ResponseEntity.ok(diagnostics));
    }

    private int countFiles(String directory) {
        try {
            return storagePort.listObjects(directory, "").join().size();
        } catch (RuntimeException e) { // NOSONAR - diagnostics endpoint should degrade gracefully
            return -1;
        }
    }
}
