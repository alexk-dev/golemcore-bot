package me.golemcore.bot.plugin.context;

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
import me.golemcore.bot.plugin.api.PluginContext;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Default host-side plugin context backed by Spring's application context.
 */
@Component
public class SpringPluginContext implements PluginContext {

    private final ApplicationContext applicationContext;
    private final RuntimeConfigService runtimeConfigService;
    private final Path pluginStorageRoot;

    public SpringPluginContext(ApplicationContext applicationContext, RuntimeConfigService runtimeConfigService) {
        this.applicationContext = applicationContext;
        this.runtimeConfigService = runtimeConfigService;
        this.pluginStorageRoot = Path.of(System.getProperty("user.home"), ".golemcore", "workspace", "plugins");
    }

    @Override
    public <T> T requireService(Class<T> type) {
        return applicationContext.getBean(type);
    }

    @Override
    public Map<String, Object> pluginConfig(String pluginId) {
        return Map.of();
    }

    @Override
    public String secret(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }

        String environmentKey = key.toUpperCase(Locale.ROOT).replace('.', '_');
        String envValue = System.getenv(environmentKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }

        String propertyValue = System.getProperty(environmentKey);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }

        return switch (key) {
        case "tools.brave.apiKey", "tools.braveSearchApiKey" -> runtimeConfigService.getBraveSearchApiKey();
        case "voice.elevenlabs.apiKey", "voice.apiKey" -> runtimeConfigService.getVoiceApiKey();
        case "voice.whisper.apiKey", "voice.whisperSttApiKey" -> runtimeConfigService.getWhisperSttApiKey();
        case "rag.apiKey" -> runtimeConfigService.getRagApiKey();
        case "telegram.token" -> runtimeConfigService.getTelegramToken();
        case "tools.imap.password" -> runtimeConfigService.getResolvedImapConfig().getPassword();
        case "tools.smtp.password" -> runtimeConfigService.getResolvedSmtpConfig().getPassword();
        default -> "";
        };
    }

    @Override
    public Path pluginDataDir(String pluginId) {
        return pluginStorageRoot.resolve(pluginId);
    }
}
