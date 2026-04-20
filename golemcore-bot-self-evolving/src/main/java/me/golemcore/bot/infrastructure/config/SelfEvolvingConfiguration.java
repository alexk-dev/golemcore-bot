package me.golemcore.bot.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.adapter.outbound.embedding.OllamaProcessAdapter;
import me.golemcore.bot.adapter.outbound.embedding.OllamaRuntimeProbeAdapter;
import me.golemcore.bot.application.selfevolving.tactic.TacticEmbeddingProbeService;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.selfevolving.SelfEvolvingTacticSearchStatusProjectionService;
import me.golemcore.bot.domain.selfevolving.tactic.ManagedLocalOllamaSupervisor;
import me.golemcore.bot.infrastructure.lifecycle.ManagedLocalOllamaLifecycleBridge;
import me.golemcore.bot.port.outbound.EmbeddingClientResolverPort;
import me.golemcore.bot.port.outbound.OllamaProcessPort;
import me.golemcore.bot.port.outbound.OllamaRuntimeApiPort;
import me.golemcore.bot.port.outbound.OllamaRuntimeProbePort;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class SelfEvolvingConfiguration {

    @Bean
    public static OllamaProcessPort ollamaProcessPort() {
        return new OllamaProcessAdapter();
    }

    @Bean
    public static OllamaRuntimeApiPort ollamaRuntimeApiPort(OkHttpClient okHttpClient,
            ObjectMapper objectMapper) {
        return new OllamaRuntimeProbeAdapter(okHttpClient, objectMapper);
    }

    @Bean
    public static ManagedLocalOllamaSupervisor managedLocalOllamaSupervisor(Clock clock,
            SelfEvolvingRuntimeConfigPort runtimeConfigPort,
            OllamaRuntimeProbePort runtimeProbePort,
            OllamaProcessPort processPort) {
        RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig = runtimeConfigPort.getSelfEvolvingConfig();
        RuntimeConfig.SelfEvolvingTacticsConfig tacticsConfig = selfEvolvingConfig.getTactics();
        if (tacticsConfig == null) {
            tacticsConfig = new RuntimeConfig.SelfEvolvingTacticsConfig();
        }
        RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig = tacticsConfig.getSearch();
        if (searchConfig == null) {
            searchConfig = new RuntimeConfig.SelfEvolvingTacticSearchConfig();
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddingsConfig = searchConfig.getEmbeddings();
        if (embeddingsConfig == null) {
            embeddingsConfig = new RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig();
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig = embeddingsConfig.getLocal();
        if (localConfig == null) {
            localConfig = new RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig();
        }
        String endpoint = resolveOllamaBaseUrl(embeddingsConfig.getBaseUrl());
        String selectedModel = trimToNull(embeddingsConfig.getModel());
        return new ManagedLocalOllamaSupervisor(
                clock,
                runtimeProbePort,
                processPort,
                endpoint,
                null,
                selectedModel,
                Duration.ofSeconds(5),
                Duration.ofMillis(localConfig.getInitialRestartBackoffMs()),
                localConfig.getMinimumRuntimeVersion());
    }

    @Bean
    public static ManagedLocalOllamaLifecycleBridge managedLocalOllamaLifecycleBridge(
            SelfEvolvingRuntimeConfigPort runtimeConfigPort,
            ManagedLocalOllamaSupervisor managedLocalOllamaSupervisor) {
        return new ManagedLocalOllamaLifecycleBridge(runtimeConfigPort, managedLocalOllamaSupervisor);
    }

    @Bean
    public static SelfEvolvingTacticSearchStatusProjectionService selfEvolvingTacticSearchStatusProjectionService(
            SelfEvolvingRuntimeConfigPort runtimeConfigPort,
            ManagedLocalOllamaSupervisor managedLocalOllamaSupervisor,
            Clock clock) {
        return new SelfEvolvingTacticSearchStatusProjectionService(
                runtimeConfigPort,
                managedLocalOllamaSupervisor,
                clock);
    }

    @Bean
    public static TacticEmbeddingProbeService tacticEmbeddingProbeService(
            EmbeddingClientResolverPort embeddingClientResolverPort,
            SelfEvolvingRuntimeConfigPort runtimeConfigPort) {
        return new TacticEmbeddingProbeService(embeddingClientResolverPort, runtimeConfigPort);
    }

    private static String resolveOllamaBaseUrl(String configuredBaseUrl) {
        String baseUrl = trimToNull(configuredBaseUrl);
        return baseUrl != null ? baseUrl : "http://127.0.0.1:11434";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
