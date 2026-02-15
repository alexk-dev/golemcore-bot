package me.golemcore.bot.adapter.inbound.web.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * JWT token creation and validation utility.
 */
@Component
@Slf4j
public class JwtTokenProvider {

    private static final int MIN_SECRET_BYTES = 32;

    private final BotProperties botProperties;
    private SecretKey signingKey;

    public JwtTokenProvider(BotProperties botProperties) {
        this.botProperties = botProperties;
    }

    @PostConstruct
    void init() {
        String secret = botProperties.getDashboard().getJwtSecret();
        if (secret == null || secret.isBlank()) {
            byte[] randomBytes = new byte[64];
            new SecureRandom().nextBytes(randomBytes);
            secret = Base64.getEncoder().encodeToString(randomBytes);
            log.warn(
                    "[Dashboard] No JWT secret configured — generated ephemeral secret (tokens won't survive restart)");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            byte[] padded = new byte[MIN_SECRET_BYTES];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, MIN_SECRET_BYTES));
            keyBytes = padded;
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(String username) {
        Duration expiration = Duration.ofMinutes(botProperties.getDashboard().getJwtExpirationMinutes());
        return buildToken(username, "access", expiration);
    }

    public String generateRefreshToken(String username) {
        Duration expiration = Duration.ofDays(botProperties.getDashboard().getRefreshExpirationDays());
        return buildToken(username, "refresh", expiration);
    }

    public String generateMfaPendingToken(String username) {
        return buildToken(username, "mfa-pending", Duration.ofSeconds(60));
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("[Dashboard] Invalid JWT: {}", e.getMessage());
            return false;
        }
    }

    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    public String getTokenType(String token) {
        return parseClaims(token).get("type", String.class);
    }

    public boolean isAccessToken(String token) {
        return "access".equals(getTokenType(token));
    }

    public boolean isRefreshToken(String token) {
        return "refresh".equals(getTokenType(token));
    }

    private String buildToken(String username, String type, Duration expiration) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("type", type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(signingKey)
                .compact();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
