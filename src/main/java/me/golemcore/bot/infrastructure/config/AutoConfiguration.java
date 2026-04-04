package me.golemcore.bot.infrastructure.config;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.service.LegacyPluginConfigurationMigrationService;
import me.golemcore.bot.domain.service.ManagedLocalOllamaSupervisor;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SelfEvolvingTacticSearchStatusProjectionService;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.infrastructure.lifecycle.ManagedLocalOllamaLifecycleBridge;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.plugin.runtime.PluginManager;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.OllamaProcessPort;
import me.golemcore.bot.port.outbound.OllamaRuntimeProbePort;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Duration;
import java.time.Clock;

/**
 * Spring auto-configuration that initializes and starts the bot on application
 * startup.
 *
 * <p>
 * This configuration:
 * <ul>
 * <li>Logs startup information (model, provider, storage location)</li>
 * <li>Auto-starts all enabled input channels (Telegram, etc.)</li>
 * <li>Performs initialization via {@code @PostConstruct}</li>
 * </ul>
 *
 * <p>
 * Channels are discovered via dependency injection and started if their
 * corresponding {@code bot.channels.<type>.enabled} property is true.
 *
 * @since 1.0
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class AutoConfiguration {

    private final BotProperties properties;
    private final ChannelRegistry channelRegistry;
    private final RuntimeConfigService runtimeConfigService;
    private final LegacyPluginConfigurationMigrationService legacyPluginConfigurationMigrationService;
    private final PluginManager pluginManager;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final ObjectProvider<GitProperties> gitPropertiesProvider;

    @Bean
    public static Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public static WebClient webClient() {
        return WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(256 * 1024))
                .build();
    }

    @Bean
    public static ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    @Bean
    public OllamaProcessPort ollamaProcessPort() {
        return new me.golemcore.bot.adapter.outbound.embedding.OllamaProcessAdapter();
    }

    @Bean
    public OllamaRuntimeProbePort ollamaRuntimeProbePort(okhttp3.OkHttpClient okHttpClient, ObjectMapper objectMapper) {
        return new me.golemcore.bot.adapter.outbound.embedding.OllamaRuntimeProbeAdapter(okHttpClient, objectMapper);
    }

    @Bean
    public ManagedLocalOllamaSupervisor managedLocalOllamaSupervisor(Clock clock,
            OllamaRuntimeProbePort runtimeProbePort,
            OllamaProcessPort processPort) {
        RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig = runtimeConfigService.getSelfEvolvingConfig();
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
    public ManagedLocalOllamaLifecycleBridge managedLocalOllamaLifecycleBridge(
            RuntimeConfigService runtimeConfigService,
            ManagedLocalOllamaSupervisor managedLocalOllamaSupervisor) {
        return new ManagedLocalOllamaLifecycleBridge(runtimeConfigService, managedLocalOllamaSupervisor);
    }

    @Bean
    public SelfEvolvingTacticSearchStatusProjectionService selfEvolvingTacticSearchStatusProjectionService(
            ManagedLocalOllamaSupervisor managedLocalOllamaSupervisor,
            Clock clock) {
        return new SelfEvolvingTacticSearchStatusProjectionService(
                runtimeConfigService,
                managedLocalOllamaSupervisor,
                clock);
    }

    @PostConstruct
    public void init() {
        BuildProperties buildProps = buildPropertiesProvider.getIfAvailable();
        GitProperties gitProps = gitPropertiesProvider.getIfAvailable();
        String version = buildProps != null ? buildProps.getVersion() : "dev";
        String commitAbbrev = gitProps != null ? gitProps.getShortCommitId() : "unknown";
        log.info("GolemCore Bot v{} ({}) starting...", version, commitAbbrev);
        log.info("Balanced Model: {}", runtimeConfigService.getBalancedModel());
        log.info("LLM Provider: {}", properties.getLlm().getProvider());
        log.info("Storage Path: {}", properties.getStorage().getLocal().getBasePath());

        legacyPluginConfigurationMigrationService.migrateIfNeeded();
        pluginManager.reloadAll();

        // Auto-start enabled channels
        for (ChannelPort channel : channelRegistry.getAll()) {
            String channelType = channel.getChannelType();

            boolean enabled = "telegram".equals(channelType)
                    ? runtimeConfigService.isTelegramEnabled()
                    : isChannelEnabled(channelType);

            if (enabled) {
                log.info("Starting channel: {}", channelType);
                channel.start();
            }
        }

        log.info("Java AI Bot started successfully");
    }

    private boolean isChannelEnabled(String channelType) {
        BotProperties.ChannelProperties channelProps = properties.getChannels().get(channelType);
        return channelProps != null && channelProps.isEnabled();
    }

    private String resolveOllamaBaseUrl(String configuredBaseUrl) {
        String baseUrl = trimToNull(configuredBaseUrl);
        return baseUrl != null ? baseUrl : "http://127.0.0.1:11434";
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
