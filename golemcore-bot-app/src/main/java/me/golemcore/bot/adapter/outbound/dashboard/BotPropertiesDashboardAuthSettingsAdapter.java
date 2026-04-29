package me.golemcore.bot.adapter.outbound.dashboard;

import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.DashboardAuthSettingsPort;
import org.springframework.stereotype.Component;

@Component
public class BotPropertiesDashboardAuthSettingsAdapter implements DashboardAuthSettingsPort {

    private final BotProperties botProperties;

    public BotPropertiesDashboardAuthSettingsAdapter(BotProperties botProperties) {
        this.botProperties = botProperties;
    }

    @Override
    public boolean isDashboardEnabled() {
        return botProperties.getDashboard().isEnabled();
    }

    @Override
    public String getConfiguredAdminPassword() {
        return botProperties.getDashboard().getAdminPassword();
    }
}
