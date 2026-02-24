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

import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.inbound.ChannelPort;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.adapter.outbound.browser.PlaywrightDriverBundleService;

import java.time.Clock;
import java.nio.file.Path;
import java.util.List;

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
    private final List<ChannelPort> channelPorts;
    private final RuntimeConfigService runtimeConfigService;
    private final PlaywrightDriverBundleService playwrightDriverBundleService;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final ObjectProvider<GitProperties> gitPropertiesProvider;

    @Bean
    public static Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public static ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
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
        preparePlaywrightDriver();

        // Auto-start enabled channels
        for (ChannelPort channel : channelPorts) {
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

    private void preparePlaywrightDriver() {
        if (!runtimeConfigService.isBrowserEnabled()) {
            log.info("Browser tool disabled, skipping Playwright driver preparation");
            return;
        }

        try {
            Path driverDir = playwrightDriverBundleService.ensureDriverReady();
            log.info("Playwright driver ready at {}", driverDir);
        } catch (RuntimeException e) {
            log.warn("Playwright driver preparation failed: {}", e.getMessage());
        }
    }

    private boolean isChannelEnabled(String channelType) {
        BotProperties.ChannelProperties channelProps = properties.getChannels().get(channelType);
        return channelProps != null && channelProps.isEnabled();
    }
}
