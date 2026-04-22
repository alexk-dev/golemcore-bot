package me.golemcore.bot.infrastructure.config;

import java.util.List;
import me.golemcore.bot.domain.context.layer.HiveLayer;
import me.golemcore.bot.domain.service.HiveSessionStateStore;
import me.golemcore.bot.domain.service.HiveSsoService;
import me.golemcore.bot.port.outbound.DashboardPublicPathPort;
import me.golemcore.bot.port.outbound.HiveGatewayPort;
import me.golemcore.bot.port.outbound.RuntimeConfigQueryPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class HiveLayerConfiguration {

    @Bean
    HiveSsoService hiveSsoService(
            RuntimeConfigQueryPort runtimeConfigPort,
            HiveSessionStateStore hiveSessionStateStore,
            HiveGatewayPort hiveGatewayPort) {
        return new HiveSsoService(runtimeConfigPort, hiveSessionStateStore, hiveGatewayPort);
    }

    @Bean
    HiveLayer hiveLayer() {
        return new HiveLayer();
    }

    @Bean
    DashboardPublicPathPort hiveSsoDashboardPublicPathPort() {
        return () -> List.of("/api/auth/hive/sso-status", "/api/auth/hive/exchange");
    }
}
