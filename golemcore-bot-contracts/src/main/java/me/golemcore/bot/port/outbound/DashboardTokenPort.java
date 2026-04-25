package me.golemcore.bot.port.outbound;

public interface DashboardTokenPort {

    String generateAccessToken(String username);

    String generateRefreshToken(String username);

    boolean validateToken(String token);

    boolean isRefreshToken(String token);

    String getUsernameFromToken(String token);
}
