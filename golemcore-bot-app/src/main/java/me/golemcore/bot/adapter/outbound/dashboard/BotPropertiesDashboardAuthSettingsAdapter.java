package me.golemcore.bot.adapter.outbound.dashboard;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.DashboardAuthSettingsPort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BotPropertiesDashboardAuthSettingsAdapter implements DashboardAuthSettingsPort {

    private final BotProperties botProperties;

    @Override
    public boolean isDashboardEnabled() {
        return botProperties.getDashboard().isEnabled();
    }

    @Override
    public String getConfiguredAdminPassword() {
        return botProperties.getDashboard().getAdminPassword();
    }
}
