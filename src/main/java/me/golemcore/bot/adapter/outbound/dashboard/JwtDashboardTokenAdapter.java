package me.golemcore.bot.adapter.outbound.dashboard;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.security.JwtTokenProvider;
import me.golemcore.bot.port.outbound.DashboardTokenPort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtDashboardTokenAdapter implements DashboardTokenPort {

    private final JwtTokenProvider jwtTokenProvider;

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
