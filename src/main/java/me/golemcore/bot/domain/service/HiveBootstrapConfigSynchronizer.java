package me.golemcore.bot.domain.service;

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

import jakarta.annotation.PostConstruct;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class HiveBootstrapConfigSynchronizer {

    private final BotProperties botProperties;
    private final RuntimeConfigService runtimeConfigService;

    @PostConstruct
    void init() {
        synchronize();
    }

    public boolean isManagedModeActive() {
        return hasManagedBootstrapOverrides();
    }

    public String getManagedJoinCode() {
        return normalizeOptionalString(botProperties.getHive().getJoinCode());
    }

    public void synchronize() {
        RuntimeConfig runtimeConfig = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.HiveConfig currentHiveConfig = runtimeConfig.getHive();
        RuntimeConfig.HiveConfig effectiveHiveConfig = buildEffectiveHiveConfig(currentHiveConfig);
        if (Objects.equals(currentHiveConfig, effectiveHiveConfig)) {
            return;
        }
        runtimeConfig.setHive(effectiveHiveConfig);
        runtimeConfigService.updateRuntimeConfig(runtimeConfig);
        log.info("[Hive] Synchronized runtime Hive config (managed={})",
                effectiveHiveConfig.getManagedByProperties());
    }

    private RuntimeConfig.HiveConfig buildEffectiveHiveConfig(RuntimeConfig.HiveConfig currentHiveConfig) {
        RuntimeConfig.HiveConfig baseline = currentHiveConfig != null
                ? currentHiveConfig
                : RuntimeConfig.HiveConfig.builder().build();
        boolean managed = hasManagedBootstrapOverrides();
        Boolean enabled = resolveEnabled(baseline.getEnabled());
        String serverUrl = resolveServerUrl(baseline.getServerUrl());
        String displayName = resolveString(botProperties.getHive().getDisplayName(), baseline.getDisplayName());
        String hostLabel = resolveString(botProperties.getHive().getHostLabel(), baseline.getHostLabel());
        Boolean autoConnect = resolveAutoConnect(baseline.getAutoConnect());
        return RuntimeConfig.HiveConfig.builder()
                .enabled(enabled)
                .serverUrl(serverUrl)
                .displayName(displayName)
                .hostLabel(hostLabel)
                .autoConnect(autoConnect)
                .managedByProperties(managed)
                .build();
    }

    private boolean hasManagedBootstrapOverrides() {
        return botProperties.getHive().getEnabled() != null
                || botProperties.getHive().getAutoConnectOnStartup() != null
                || normalizeOptionalString(botProperties.getHive().getJoinCode()) != null
                || normalizeOptionalString(botProperties.getHive().getDisplayName()) != null
                || normalizeOptionalString(botProperties.getHive().getHostLabel()) != null;
    }

    private Boolean resolveEnabled(Boolean currentEnabled) {
        if (botProperties.getHive().getEnabled() != null) {
            return botProperties.getHive().getEnabled();
        }
        if (getManagedJoinCode() != null) {
            return true;
        }
        return currentEnabled != null ? currentEnabled : false;
    }

    private Boolean resolveAutoConnect(Boolean currentAutoConnect) {
        if (botProperties.getHive().getAutoConnectOnStartup() != null) {
            return botProperties.getHive().getAutoConnectOnStartup();
        }
        return currentAutoConnect != null ? currentAutoConnect : false;
    }

    private String resolveServerUrl(String currentServerUrl) {
        String managedJoinCode = getManagedJoinCode();
        if (managedJoinCode == null) {
            return normalizeOptionalString(currentServerUrl);
        }
        String parsedServerUrl = HiveJoinCodeParser.tryExtractServerUrl(managedJoinCode);
        if (parsedServerUrl != null) {
            return parsedServerUrl;
        }
        log.warn("[Hive] Failed to parse server URL from managed join code");
        return normalizeOptionalString(currentServerUrl);
    }

    private String resolveString(String managedValue, String currentValue) {
        String normalizedManagedValue = normalizeOptionalString(managedValue);
        return normalizedManagedValue != null ? normalizedManagedValue : normalizeOptionalString(currentValue);
    }

    private String normalizeOptionalString(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

}
