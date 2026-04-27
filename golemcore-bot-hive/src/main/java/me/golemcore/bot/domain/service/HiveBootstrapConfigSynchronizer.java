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
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.port.outbound.HiveBootstrapSettingsPort;
import me.golemcore.bot.port.outbound.HiveBootstrapSettingsPort.HiveBootstrapSettings;
import me.golemcore.bot.port.outbound.RuntimeConfigAdminPort;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class HiveBootstrapConfigSynchronizer {

    private final HiveBootstrapSettingsPort hiveBootstrapSettingsPort;
    private final RuntimeConfigAdminPort runtimeConfigAdminPort;

    public HiveBootstrapConfigSynchronizer(
            HiveBootstrapSettingsPort hiveBootstrapSettingsPort,
            RuntimeConfigAdminPort runtimeConfigAdminPort) {
        this.hiveBootstrapSettingsPort = hiveBootstrapSettingsPort;
        this.runtimeConfigAdminPort = runtimeConfigAdminPort;
    }

    @PostConstruct
    void init() {
        synchronize();
    }

    public boolean isManagedModeActive() {
        return hasManagedBootstrapOverrides();
    }

    public String getManagedJoinCode() {
        return normalizeOptionalString(settings().joinCode());
    }

    public void synchronize() {
        RuntimeConfig runtimeConfig = runtimeConfigAdminPort.getRuntimeConfig();
        RuntimeConfig.HiveConfig currentHiveConfig = runtimeConfig.getHive();
        RuntimeConfig.HiveConfig effectiveHiveConfig = buildEffectiveHiveConfig(currentHiveConfig);
        if (Objects.equals(currentHiveConfig, effectiveHiveConfig)) {
            return;
        }
        runtimeConfig.setHive(effectiveHiveConfig);
        runtimeConfigAdminPort.updateRuntimeConfig(runtimeConfig);
        log.info("[Hive] Synchronized runtime Hive config (managed={})",
                effectiveHiveConfig.getManagedByProperties());
    }

    private RuntimeConfig.HiveConfig buildEffectiveHiveConfig(RuntimeConfig.HiveConfig currentHiveConfig) {
        RuntimeConfig.HiveConfig baseline = currentHiveConfig != null
                ? currentHiveConfig
                : RuntimeConfig.HiveConfig.builder().build();
        boolean managed = hasManagedBootstrapOverrides();
        HiveBootstrapSettings settings = settings();
        Boolean enabled = resolveEnabled(baseline.getEnabled());
        String serverUrl = resolveServerUrl(baseline.getServerUrl());
        String displayName = resolveString(settings.displayName(), baseline.getDisplayName());
        String hostLabel = resolveString(settings.hostLabel(), baseline.getHostLabel());
        String dashboardBaseUrl = resolveString(settings.dashboardBaseUrl(), baseline.getDashboardBaseUrl());
        Boolean ssoEnabled = resolveSsoEnabled(baseline.getSsoEnabled());
        Boolean autoConnect = resolveAutoConnect(baseline.getAutoConnect());
        return RuntimeConfig.HiveConfig.builder()
                .enabled(enabled)
                .serverUrl(serverUrl)
                .displayName(displayName)
                .hostLabel(hostLabel)
                .dashboardBaseUrl(dashboardBaseUrl)
                .ssoEnabled(ssoEnabled)
                .autoConnect(autoConnect)
                .managedByProperties(managed)
                .sdlc(baseline.getSdlc())
                .build();
    }

    private boolean hasManagedBootstrapOverrides() {
        HiveBootstrapSettings settings = settings();
        return settings.enabled() != null
                || settings.autoConnectOnStartup() != null
                || normalizeOptionalString(settings.joinCode()) != null
                || normalizeOptionalString(settings.displayName()) != null
                || normalizeOptionalString(settings.hostLabel()) != null
                || normalizeOptionalString(settings.dashboardBaseUrl()) != null
                || settings.ssoEnabled() != null;
    }

    private Boolean resolveEnabled(Boolean currentEnabled) {
        HiveBootstrapSettings settings = settings();
        if (settings.enabled() != null) {
            return settings.enabled();
        }
        if (getManagedJoinCode() != null) {
            return true;
        }
        return currentEnabled != null ? currentEnabled : false;
    }

    private Boolean resolveSsoEnabled(Boolean currentSsoEnabled) {
        HiveBootstrapSettings settings = settings();
        if (settings.ssoEnabled() != null) {
            return settings.ssoEnabled();
        }
        return currentSsoEnabled != null ? currentSsoEnabled : true;
    }

    private Boolean resolveAutoConnect(Boolean currentAutoConnect) {
        HiveBootstrapSettings settings = settings();
        if (settings.autoConnectOnStartup() != null) {
            return settings.autoConnectOnStartup();
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

    private HiveBootstrapSettings settings() {
        HiveBootstrapSettings settings = hiveBootstrapSettingsPort.hiveBootstrapSettings();
        return settings != null ? settings : HiveBootstrapSettings.empty();
    }

    private String normalizeOptionalString(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
