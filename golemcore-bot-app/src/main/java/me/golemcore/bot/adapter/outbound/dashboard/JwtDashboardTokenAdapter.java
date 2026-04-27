package me.golemcore.bot.adapter.outbound.dashboard;

import me.golemcore.bot.port.outbound.DashboardTokenPort;
import me.golemcore.bot.infrastructure.security.JwtTokenProvider;
import org.springframework.stereotype.Component;

@Component
public class JwtDashboardTokenAdapter implements DashboardTokenPort {

    private final JwtTokenProvider jwtTokenProvider;

    public JwtDashboardTokenAdapter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public String generateAccessToken(String username) {
        return jwtTokenProvider.generateAccessToken(username);
    }

    @Override
    public String generateRefreshToken(String username) {
        return jwtTokenProvider.generateRefreshToken(username);
    }

    @Override
    public boolean validateToken(String token) {
        return jwtTokenProvider.validateToken(token);
    }

    @Override
    public boolean isRefreshToken(String token) {
        return jwtTokenProvider.isRefreshToken(token);
    }

    @Override
    public String getUsernameFromToken(String token) {
        return jwtTokenProvider.getUsernameFromToken(token);
    }
}
