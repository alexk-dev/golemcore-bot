package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.LogsPageResponse;
import me.golemcore.bot.adapter.inbound.web.dto.SystemHealthResponse;
import me.golemcore.bot.adapter.inbound.web.logstream.DashboardLogService;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.domain.service.LocalEmbeddingBootstrapService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.RagPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
public class SystemController {

    private final ChannelRegistry channelRegistry;
    private final BotProperties botProperties;
    private final RuntimeConfigService runtimeConfigService;
    private final StoragePort storagePort;
    private final RagPort ragPort;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final ObjectProvider<GitProperties> gitPropertiesProvider;
    private final DashboardLogService dashboardLogService;
    private final LocalEmbeddingBootstrapService localEmbeddingBootstrapService;

    public SystemController(ChannelRegistry channelRegistry,
            BotProperties botProperties,
            RuntimeConfigService runtimeConfigService,
            StoragePort storagePort,
            RagPort ragPort,
            ObjectProvider<BuildProperties> buildPropertiesProvider,
            ObjectProvider<GitProperties> gitPropertiesProvider,
            DashboardLogService dashboardLogService,
            LocalEmbeddingBootstrapService localEmbeddingBootstrapService) {
        this.channelRegistry = channelRegistry;
        this.botProperties = botProperties;
        this.runtimeConfigService = runtimeConfigService;
        this.storagePort = storagePort;
        this.ragPort = ragPort;
        this.buildPropertiesProvider = buildPropertiesProvider;
        this.gitPropertiesProvider = gitPropertiesProvider;
        this.dashboardLogService = dashboardLogService;
        this.localEmbeddingBootstrapService = localEmbeddingBootstrapService;
    }

    @GetMapping("/health")
    public Mono<ResponseEntity<SystemHealthResponse>> health() {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

        Map<String, SystemHealthResponse.ChannelStatus> channels = new LinkedHashMap<>();
        for (ChannelPort port : channelRegistry.getAll()) {
            channels.put(port.getChannelType(), SystemHealthResponse.ChannelStatus.builder()
                    .type(port.getChannelType())
                    .running(port.isRunning())
                    .enabled(true)
                    .build());
        }

        BuildProperties buildProps = buildPropertiesProvider.getIfAvailable();
        GitProperties gitProps = gitPropertiesProvider.getIfAvailable();

        SystemHealthResponse response = SystemHealthResponse.builder()
                .status("UP")
                .version(buildProps != null ? buildProps.getVersion() : "dev")
                .gitCommit(gitProps != null ? gitProps.getShortCommitId() : null)
                .buildTime(buildProps != null && buildProps.getTime() != null ? buildProps.getTime().toString() : null)
                .uptimeMs(uptimeMs)
                .channels(channels)
                .selfEvolvingEmbeddings(buildSelfEvolvingEmbeddingsStatus())
                .build();
        return Mono.just(ResponseEntity.ok(response));
    }

    @GetMapping("/config")
    public Mono<ResponseEntity<Map<String, Object>>> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("llmProvider", botProperties.getLlm().getProvider());
        config.put("maxIterations", runtimeConfigService.getTurnMaxLlmCalls());
        config.put("voiceEnabled", runtimeConfigService.isVoiceEnabled());
        config.put("ragEnabled", ragPort.isAvailable());
        config.put("planEnabled", runtimeConfigService.isPlanEnabled());
        config.put("autoModeEnabled", runtimeConfigService.isAutoModeEnabled());
        config.put("dashboardEnabled", botProperties.getDashboard().isEnabled());
        return Mono.just(ResponseEntity.ok(config));
    }

    @GetMapping("/channels")
    public Mono<ResponseEntity<List<Map<String, Object>>>> getChannels() {
        List<Map<String, Object>> result = channelRegistry.getAll().stream()
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

    @GetMapping("/logs")
    public Mono<ResponseEntity<LogsPageResponse>> getLogs(
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(required = false) Integer limit) {
        DashboardLogService.LogsSlice slice = dashboardLogService.getLogsPage(beforeSeq, limit);
        LogsPageResponse response = LogsPageResponse.builder()
                .items(slice.items())
                .oldestSeq(slice.oldestSeq())
                .newestSeq(slice.newestSeq())
                .hasMore(slice.hasMore())
                .build();
        return Mono.just(ResponseEntity.ok(response));
    }

    private int countFiles(String directory) {
        try {
            return storagePort.listObjects(directory, "").join().size();
        } catch (RuntimeException e) { // NOSONAR - diagnostics endpoint should degrade gracefully
            return -1;
        }
    }

    private SystemHealthResponse.SelfEvolvingEmbeddingsStatus buildSelfEvolvingEmbeddingsStatus() {
        if (localEmbeddingBootstrapService == null) {
            return null;
        }
        try {
            TacticSearchStatus status = localEmbeddingBootstrapService.probeStatus();
            if (status == null) {
                return null;
            }
            return SystemHealthResponse.SelfEvolvingEmbeddingsStatus.builder()
                    .mode(status.getMode())
                    .reason(status.getReason())
                    .degraded(Boolean.TRUE.equals(status.getDegraded()))
                    .runtimeState(status.getRuntimeState())
                    .owned(Boolean.TRUE.equals(status.getOwned()))
                    .runtimeInstalled(Boolean.TRUE.equals(status.getRuntimeInstalled()))
                    .runtimeHealthy(Boolean.TRUE.equals(status.getRuntimeHealthy()))
                    .runtimeVersion(status.getRuntimeVersion())
                    .model(status.getModel())
                    .modelAvailable(Boolean.TRUE.equals(status.getModelAvailable()))
                    .restartAttempts(status.getRestartAttempts())
                    .nextRetryTime(status.getNextRetryTime())
                    .build();
        } catch (RuntimeException exception) {
            return SystemHealthResponse.SelfEvolvingEmbeddingsStatus.builder()
                    .mode("bm25")
                    .reason(exception.getMessage())
                    .degraded(true)
                    .build();
        }
    }
}
