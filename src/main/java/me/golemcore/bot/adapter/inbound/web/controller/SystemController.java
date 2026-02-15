package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.SystemHealthResponse;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.lang.management.ManagementFactory;
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
        config.put("voiceEnabled", botProperties.getVoice().isEnabled());
        config.put("ragEnabled", botProperties.getRag().isEnabled());
        config.put("planEnabled", botProperties.getPlan().isEnabled());
        config.put("autoModeEnabled", botProperties.getAuto().isEnabled());
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
}
