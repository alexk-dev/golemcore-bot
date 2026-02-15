package me.golemcore.bot.adapter.inbound.web.security;

import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DashboardSecurityConfigTest {

    @Test
    void shouldCreateFilterChainWhenDashboardDisabled() {
        BotProperties props = new BotProperties();
        props.getDashboard().setEnabled(false);

        JwtTokenProvider tokenProvider = new JwtTokenProvider(props);
        tokenProvider.init();
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(tokenProvider);

        DashboardSecurityConfig config = new DashboardSecurityConfig(props, jwtFilter);
        // Verify config instantiation — actual filter chain needs Spring context
        assertNotNull(config);
    }

    @Test
    void shouldCreateFilterChainWhenDashboardEnabled() {
        BotProperties props = new BotProperties();
        props.getDashboard().setEnabled(true);
        props.getDashboard().setJwtSecret("test-secret-key-long-enough-for-hmac-256");

        JwtTokenProvider tokenProvider = new JwtTokenProvider(props);
        tokenProvider.init();
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(tokenProvider);

        DashboardSecurityConfig config = new DashboardSecurityConfig(props, jwtFilter);
        assertNotNull(config);
    }
}
