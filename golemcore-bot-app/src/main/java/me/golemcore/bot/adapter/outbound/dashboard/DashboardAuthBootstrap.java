package me.golemcore.bot.adapter.outbound.dashboard;

import jakarta.annotation.PostConstruct;
import me.golemcore.bot.domain.service.DashboardAuthService;
import org.springframework.stereotype.Component;

@Component
public class DashboardAuthBootstrap {

    private final DashboardAuthService dashboardAuthService;

    public DashboardAuthBootstrap(DashboardAuthService dashboardAuthService) {
        this.dashboardAuthService = dashboardAuthService;
    }

    @PostConstruct
    void initializeDashboardAuth() {
        dashboardAuthService.init();
    }
}
