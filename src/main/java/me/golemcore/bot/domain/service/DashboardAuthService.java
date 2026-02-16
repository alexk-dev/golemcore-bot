package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.web.security.JwtTokenProvider;
import me.golemcore.bot.domain.model.AdminCredentials;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Domain service for dashboard authentication: password, JWT tokens, and TOTP
 * MFA.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardAuthService {

    private static final String ADMIN_DIR = "preferences";
    private static final String ADMIN_FILE = "admin.json";
    private static final String ADMIN_USERNAME = "admin";
    private static final int TEMP_CREDENTIAL_LENGTH = 30;
    private static final String CREDENTIAL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StoragePort storagePort;
    private final BotProperties botProperties;
    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeVerifier codeVerifier = new DefaultCodeVerifier(
            new DefaultCodeGenerator(), new SystemTimeProvider());

    private AdminCredentials credentials;

    @PostConstruct
    void init() {
        if (!botProperties.getDashboard().isEnabled()) {
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
        if (!passwordEncoder.matches(password, credentials.getPasswordHash())) {
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

    public TokenPair refreshAccessToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken) || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            return null;
        }
        String username = jwtTokenProvider.getUsernameFromToken(refreshToken);
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
        if (!passwordEncoder.matches(password, credentials.getPasswordHash())) {
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
        if (!passwordEncoder.matches(oldPassword, credentials.getPasswordHash())) {
            return false;
        }
        credentials.setPasswordHash(passwordEncoder.encode(newPassword));
        persistCredentials();
        log.info("[Dashboard] Admin password changed");
        return true;
    }

    public AdminCredentials getCredentials() {
        return credentials;
    }

    private TokenPair generateTokens(String username) {
        String accessToken = jwtTokenProvider.generateAccessToken(username);
        String refreshToken = jwtTokenProvider.generateRefreshToken(username);
        return TokenPair.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    private void loadOrCreateCredentials() throws IOException {
        Boolean exists = storagePort.exists(ADMIN_DIR, ADMIN_FILE).join();
        if (Boolean.TRUE.equals(exists)) {
            String json = storagePort.getText(ADMIN_DIR, ADMIN_FILE).join();
            credentials = objectMapper.readValue(json, AdminCredentials.class);
            log.info("[Dashboard] Loaded admin credentials from storage");
            return;
        }

        String configHash = botProperties.getDashboard().getAdminPasswordHash();
        if (configHash != null && !configHash.isBlank()) {
            credentials = AdminCredentials.builder()
                    .passwordHash(configHash)
                    .build();
            persistCredentials();
            log.info("[Dashboard] Created admin credentials from config hash");
            return;
        }

        String tempPassword = generateTempPassword();
        credentials = AdminCredentials.builder()
                .passwordHash(passwordEncoder.encode(tempPassword))
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
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(credentials);
            storagePort.putText(ADMIN_DIR, ADMIN_FILE, json).join();
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
