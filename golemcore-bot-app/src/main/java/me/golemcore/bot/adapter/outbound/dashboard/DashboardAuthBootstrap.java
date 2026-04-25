package me.golemcore.bot.adapter.outbound.dashboard;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.service.DashboardAuthService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DashboardAuthBootstrap {

    private final DashboardAuthService dashboardAuthService;

    @PostConstruct
    void initializeDashboardAuth() {
        dashboardAuthService.init();
    }
}
