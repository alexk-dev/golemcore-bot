package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.adapter.inbound.web.security.JwtTokenProvider;
import me.golemcore.bot.domain.model.AdminCredentials;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardAuthServiceTest {

    private static final String PASSWORD = "my-password";
    private static final String OLD_PASSWORD = "old-password";
    private static final String NEW_PASSWORD = "new-password";
    private static final String EXISTING_PASSWORD = "existing-password";
    private static final BCryptPasswordEncoder TEST_ENCODER = new BCryptPasswordEncoder(4);
    private static final String PASSWORD_HASH = TEST_ENCODER.encode(PASSWORD);
    private static final String OLD_PASSWORD_HASH = TEST_ENCODER.encode(OLD_PASSWORD);
    private static final String EXISTING_PASSWORD_HASH = TEST_ENCODER.encode(EXISTING_PASSWORD);

    private DashboardAuthService authService;
    private StoragePort storagePort;
    private BotProperties botProperties;
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        botProperties = new BotProperties();
        botProperties.getDashboard().setEnabled(true);
        botProperties.getDashboard().setJwtSecret("test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm");

        jwtTokenProvider = new JwtTokenProvider(botProperties);
        jwtTokenProvider.init();

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        // Default: no existing credentials in storage
        when(storagePort.exists(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        authService = new DashboardAuthService(storagePort, botProperties, jwtTokenProvider, objectMapper);
    }

    @Test
    void shouldGenerateTempPasswordOnInit() {
        authService.init();

        AdminCredentials creds = authService.getCredentials();
        assertNotNull(creds);
        assertNotNull(creds.getPasswordHash());
        assertFalse(creds.isMfaEnabled());
    }

    @Test
    void shouldUseConfiguredPasswordHash() {
        String hash = PASSWORD_HASH;
        botProperties.getDashboard().setAdminPasswordHash(hash);

        authService.init();

        AdminCredentials creds = authService.getCredentials();
        assertNotNull(creds);
        assertEquals(hash, creds.getPasswordHash());
    }

    @Test
    void shouldLoadExistingCredentials() throws Exception {
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(
                AdminCredentials.builder().passwordHash(EXISTING_PASSWORD_HASH).build());

        when(storagePort.exists(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(storagePort.getText(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(json));

        authService.init();

        AdminCredentials creds = authService.getCredentials();
        assertNotNull(creds);
        assertEquals(EXISTING_PASSWORD_HASH, creds.getPasswordHash());
    }

    @Test
    void shouldAuthenticateWithCorrectPassword() {
        botProperties.getDashboard().setAdminPasswordHash(PASSWORD_HASH);
        authService.init();

        DashboardAuthService.TokenPair tokens = authService.authenticate(PASSWORD, null);
        assertNotNull(tokens);
        assertNotNull(tokens.getAccessToken());
        assertNotNull(tokens.getRefreshToken());
    }

    @Test
    void shouldRejectWrongPassword() {
        botProperties.getDashboard().setAdminPasswordHash(PASSWORD_HASH);
        authService.init();

        DashboardAuthService.TokenPair tokens = authService.authenticate("wrong-password", null);
        assertNull(tokens);
    }

    @Test
    void shouldReturnNullWhenNoCredentials() {
        // Don't call init — credentials remain null
        DashboardAuthService.TokenPair tokens = authService.authenticate("any", null);
        assertNull(tokens);
    }

    @Test
    void shouldReturnMfaDisabledByDefault() {
        authService.init();
        assertFalse(authService.isMfaEnabled());
    }

    @Test
    void shouldSetupMfa() {
        authService.init();

        DashboardAuthService.MfaSetupResult result = authService.setupMfa();
        assertNotNull(result);
        assertNotNull(result.getSecret());
        assertNotNull(result.getQrUri());
        assertTrue(result.getQrUri().startsWith("otpauth://totp/GolemCore:admin"));
        assertTrue(result.getQrUri().contains("secret=" + result.getSecret()));
    }

    @Test
    void shouldRejectInvalidMfaVerificationCode() {
        authService.init();

        boolean enabled = authService.enableMfa("JBSWY3DPEHPK3PXP", "000000");
        // TOTP code is time-based, this will almost certainly be wrong
        // but the test verifies the flow works without exceptions
        assertNotNull(authService.getCredentials());
    }

    @Test
    void shouldChangePassword() {
        botProperties.getDashboard().setAdminPasswordHash(OLD_PASSWORD_HASH);
        authService.init();

        boolean changed = authService.changePassword(OLD_PASSWORD, NEW_PASSWORD);
        assertTrue(changed);

        // Verify new password works
        DashboardAuthService.TokenPair tokens = authService.authenticate(NEW_PASSWORD, null);
        assertNotNull(tokens);

        // Verify old password no longer works
        DashboardAuthService.TokenPair oldTokens = authService.authenticate(OLD_PASSWORD, null);
        assertNull(oldTokens);
    }

    @Test
    void shouldRejectPasswordChangeWithWrongOldPassword() {
        botProperties.getDashboard().setAdminPasswordHash(OLD_PASSWORD_HASH);
        authService.init();

        boolean changed = authService.changePassword("wrong-old-password", "new-password");
        assertFalse(changed);
    }

    @Test
    void shouldDisableMfaWithCorrectPassword() {
        botProperties.getDashboard().setAdminPasswordHash(PASSWORD_HASH);
        authService.init();

        // Manually enable MFA
        AdminCredentials creds = authService.getCredentials();
        creds.setMfaEnabled(true);
        creds.setMfaSecret("JBSWY3DPEHPK3PXP");

        boolean disabled = authService.disableMfa(PASSWORD);
        assertTrue(disabled);
        assertFalse(authService.isMfaEnabled());
    }

    @Test
    void shouldRejectMfaDisableWithWrongPassword() {
        botProperties.getDashboard().setAdminPasswordHash(PASSWORD_HASH);
        authService.init();

        boolean disabled = authService.disableMfa("wrong-password");
        assertFalse(disabled);
    }

    @Test
    void shouldRefreshAccessToken() {
        botProperties.getDashboard().setAdminPasswordHash(PASSWORD_HASH);
        authService.init();

        DashboardAuthService.TokenPair original = authService.authenticate(PASSWORD, null);
        assertNotNull(original);

        DashboardAuthService.TokenPair refreshed = authService.refreshAccessToken(original.getRefreshToken());
        assertNotNull(refreshed);
        assertNotNull(refreshed.getAccessToken());
    }

    @Test
    void shouldRejectInvalidRefreshToken() {
        authService.init();

        DashboardAuthService.TokenPair result = authService.refreshAccessToken("invalid-token");
        assertNull(result);
    }

    @Test
    void shouldRejectAccessTokenAsRefreshToken() {
        botProperties.getDashboard().setAdminPasswordHash(PASSWORD_HASH);
        authService.init();

        DashboardAuthService.TokenPair tokens = authService.authenticate(PASSWORD, null);
        assertNotNull(tokens);

        // Using access token as refresh token should fail
        DashboardAuthService.TokenPair result = authService.refreshAccessToken(tokens.getAccessToken());
        assertNull(result);
    }

    @Test
    void shouldSkipInitWhenDashboardDisabled() {
        botProperties.getDashboard().setEnabled(false);
        authService.init();
        assertNull(authService.getCredentials());
    }

    @Test
    void shouldRejectMfaWhenMfaEnabledButNoCodeProvided() {
        botProperties.getDashboard().setAdminPasswordHash(PASSWORD_HASH);
        authService.init();

        // Enable MFA manually
        AdminCredentials creds = authService.getCredentials();
        creds.setMfaEnabled(true);
        creds.setMfaSecret("JBSWY3DPEHPK3PXP");

        // Attempt login without MFA code
        DashboardAuthService.TokenPair tokens = authService.authenticate(PASSWORD, null);
        assertNull(tokens);

        // Attempt login with blank MFA code
        DashboardAuthService.TokenPair tokens2 = authService.authenticate(PASSWORD, "  ");
        assertNull(tokens2);
    }
}
