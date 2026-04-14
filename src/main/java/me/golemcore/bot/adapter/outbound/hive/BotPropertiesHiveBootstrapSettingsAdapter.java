package me.golemcore.bot.adapter.outbound.hive;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.HiveBootstrapSettingsPort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BotPropertiesHiveBootstrapSettingsAdapter implements HiveBootstrapSettingsPort {

    private final BotProperties botProperties;

    @Override
    public Boolean enabled() {
        return botProperties.getHive().getEnabled();
    }

    @Override
    public Boolean autoConnectOnStartup() {
        return botProperties.getHive().getAutoConnectOnStartup();
    }

    @Override
    public String joinCode() {
        return botProperties.getHive().getJoinCode();
    }

    @Override
    public String displayName() {
        return botProperties.getHive().getDisplayName();
    }

    @Override
    public String hostLabel() {
        return botProperties.getHive().getHostLabel();
    }

    @Override
    public String dashboardBaseUrl() {
        return firstConfigured(
                botProperties.getHive().getDashboardPublicUrl(),
                botProperties.getHive().getDashboardBaseUrl());
    }

    @Override
    public Boolean ssoEnabled() {
        return botProperties.getHive().getSsoEnabled();
    }

    private String firstConfigured(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }
}
