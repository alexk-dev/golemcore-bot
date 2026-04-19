package me.golemcore.bot.domain.service;

import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AdminCredentials;
import me.golemcore.bot.domain.model.hive.HiveSsoTokenResponse;
import me.golemcore.bot.port.outbound.DashboardCredentialsPort;
import me.golemcore.bot.port.outbound.DashboardAuthSettingsPort;
import me.golemcore.bot.port.outbound.DashboardTokenPort;
import me.golemcore.bot.port.outbound.PasswordHashPort;
import org.springframework.stereotype.Service;

/**
 * Domain service for dashboard authentication: password, JWT tokens, and TOTP
 * MFA.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardAuthService {

    private static final String ADMIN_USERNAME = "admin";
    private static final int TEMP_CREDENTIAL_LENGTH = 30;
    private static final String CREDENTIAL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final DashboardCredentialsPort dashboardCredentialsPort;
    private final DashboardAuthSettingsPort settingsPort;
    private final DashboardTokenPort dashboardTokenPort;
    private final PasswordHashPort passwordHashPort;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeVerifier codeVerifier = new DefaultCodeVerifier(
            new DefaultCodeGenerator(), new SystemTimeProvider());

    private AdminCredentials credentials;

    public void init() {
        if (!settingsPort.isDashboardEnabled()) {
            return;
        }
        try {
            loadOrCreateCredentials();
        } catch (IOException e) { // NOSONAR — startup best-effort
            log.error("[Dashboard] Failed to initialize admin credentials", e);
        }
    }

    /**
     * Authenticate with password (and optionally TOTP code if MFA enabled). Returns
     * JWT tokens on success, null on failure.
     */
    public TokenPair authenticate(String password, String mfaCode) {
        if (credentials == null) {
            return null;
        }
        if (!passwordHashPort.matches(password, credentials.getPasswordHash())) {
            return null;
        }
        if (credentials.isMfaEnabled()) {
            if (mfaCode == null || mfaCode.isBlank()) {
                return null;
            }
            if (!codeVerifier.isValidCode(credentials.getMfaSecret(), mfaCode)) {
                return null;
            }
        }
        return generateTokens(ADMIN_USERNAME);
    }

    public boolean isMfaEnabled() {
        return credentials != null && credentials.isMfaEnabled();
    }

    public TokenPair authenticateHiveSso(HiveSsoTokenResponse tokenResponse) {
        if (credentials == null || tokenResponse == null) {
            return null;
        }
        if (tokenResponse.roles() == null
                || tokenResponse.roles().stream().noneMatch(role -> "ADMIN".equals(role) || "OPERATOR".equals(role))) {
            return null;
        }
        log.info("[Dashboard] Hive SSO login accepted for operator {}", tokenResponse.username());
        return generateTokens(ADMIN_USERNAME);
    }

    public TokenPair refreshAccessToken(String refreshToken) {
        if (!dashboardTokenPort.validateToken(refreshToken) || !dashboardTokenPort.isRefreshToken(refreshToken)) {
            return null;
        }
        String username = dashboardTokenPort.getUsernameFromToken(refreshToken);
        return generateTokens(username);
    }

    public MfaSetupResult setupMfa() {
        String secret = secretGenerator.generate();
        String otpauthUri = String.format("otpauth://totp/GolemCore:%s?secret=%s&issuer=GolemCore",
                ADMIN_USERNAME, secret);
        return MfaSetupResult.builder()
                .secret(secret)
                .qrUri(otpauthUri)
                .build();
    }

    public boolean enableMfa(String secret, String verificationCode) {
        if (!codeVerifier.isValidCode(secret, verificationCode)) {
            return false;
        }
        credentials.setMfaEnabled(true);
        credentials.setMfaSecret(secret);
        credentials.setMfaVerifiedAt(Instant.now());
        persistCredentials();
        log.info("[Dashboard] MFA enabled for admin");
        return true;
    }

    public boolean disableMfa(String password) {
        if (!passwordHashPort.matches(password, credentials.getPasswordHash())) {
            return false;
        }
        credentials.setMfaEnabled(false);
        credentials.setMfaSecret(null);
        credentials.setMfaVerifiedAt(null);
        persistCredentials();
        log.info("[Dashboard] MFA disabled for admin");
        return true;
    }

    public boolean changePassword(String oldPassword, String newPassword) {
        if (!passwordHashPort.matches(oldPassword, credentials.getPasswordHash())) {
            return false;
        }
        credentials.setPasswordHash(passwordHashPort.encode(newPassword));
        persistCredentials();
        log.info("[Dashboard] Admin password changed");
        return true;
    }

    public AdminCredentials getCredentials() {
        return credentials;
    }

    private TokenPair generateTokens(String username) {
        String accessToken = dashboardTokenPort.generateAccessToken(username);
        String refreshToken = dashboardTokenPort.generateRefreshToken(username);
        return TokenPair.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    private void loadOrCreateCredentials() throws IOException {
        if (dashboardCredentialsPort.load().isPresent()) {
            credentials = dashboardCredentialsPort.load().orElseThrow();
            log.info("[Dashboard] Loaded admin credentials from storage");
            return;
        }

        String configPassword = settingsPort.getConfiguredAdminPassword();
        if (configPassword != null && !configPassword.isBlank()) {
            credentials = AdminCredentials.builder()
                    .passwordHash(passwordHashPort.encode(configPassword))
                    .build();
            persistCredentials();
            log.info("[Dashboard] Created admin credentials from config password");
            return;
        }

        String tempPassword = generateTempPassword();
        credentials = AdminCredentials.builder()
                .passwordHash(passwordHashPort.encode(tempPassword))
                .build();
        persistCredentials();

        log.info("\n"
                + "╔══════════════════════════════════════════════════════════════╗\n"
                + "║  DASHBOARD TEMPORARY PASSWORD (change after first login!)   ║\n"
                + "║  Password: {}   ║\n"
                + "╚══════════════════════════════════════════════════════════════╝",
                tempPassword);
    }

    private void persistCredentials() {
        try {
            dashboardCredentialsPort.save(credentials);
        } catch (Exception e) { // NOSONAR
            log.error("[Dashboard] Failed to persist admin credentials", e);
        }
    }

    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder(TEMP_CREDENTIAL_LENGTH);
        for (int i = 0; i < TEMP_CREDENTIAL_LENGTH; i++) {
            sb.append(CREDENTIAL_ALPHABET.charAt(SECURE_RANDOM.nextInt(CREDENTIAL_ALPHABET.length())));
        }
        return sb.toString();
    }

    @Data
    @Builder
    public static class TokenPair {
        private String accessToken;
        private String refreshToken;
    }

    @Data
    @Builder
    public static class MfaSetupResult {
        private String secret;
        private String qrUri;
    }
}
