package me.golemcore.bot.adapter.inbound.web.security;

import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        BotProperties props = new BotProperties();
        props.getDashboard().setJwtSecret("test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm");
        props.getDashboard().setJwtExpirationMinutes(30);
        props.getDashboard().setRefreshExpirationDays(7);
        provider = new JwtTokenProvider(props);
        provider.init();
    }

    @Test
    void shouldGenerateAccessToken() {
        String token = provider.generateAccessToken("admin");
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void shouldGenerateRefreshToken() {
        String token = provider.generateRefreshToken("admin");
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void shouldGenerateMfaPendingToken() {
        String token = provider.generateMfaPendingToken("admin");
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void shouldValidateValidToken() {
        String token = provider.generateAccessToken("admin");
        assertTrue(provider.validateToken(token));
    }

    @Test
    void shouldRejectInvalidToken() {
        assertFalse(provider.validateToken("invalid-token-string"));
    }

    @Test
    void shouldRejectNullToken() {
        assertFalse(provider.validateToken(null));
    }

    @Test
    void shouldRejectEmptyToken() {
        assertFalse(provider.validateToken(""));
    }

    @Test
    void shouldExtractUsernameFromToken() {
        String token = provider.generateAccessToken("admin");
        assertEquals("admin", provider.getUsernameFromToken(token));
    }

    @Test
    void shouldIdentifyAccessToken() {
        String token = provider.generateAccessToken("admin");
        assertTrue(provider.isAccessToken(token));
        assertFalse(provider.isRefreshToken(token));
    }

    @Test
    void shouldIdentifyRefreshToken() {
        String token = provider.generateRefreshToken("admin");
        assertTrue(provider.isRefreshToken(token));
        assertFalse(provider.isAccessToken(token));
    }

    @Test
    void shouldGetTokenType() {
        String accessToken = provider.generateAccessToken("admin");
        assertEquals("access", provider.getTokenType(accessToken));

        String refreshToken = provider.generateRefreshToken("admin");
        assertEquals("refresh", provider.getTokenType(refreshToken));

        String mfaToken = provider.generateMfaPendingToken("admin");
        assertEquals("mfa-pending", provider.getTokenType(mfaToken));
    }

    @Test
    void shouldInitWithEphemeralSecretWhenNoneConfigured() {
        BotProperties props = new BotProperties();
        props.getDashboard().setJwtSecret("");
        JwtTokenProvider ephemeralProvider = new JwtTokenProvider(props);
        ephemeralProvider.init();

        String token = ephemeralProvider.generateAccessToken("admin");
        assertNotNull(token);
        assertTrue(ephemeralProvider.validateToken(token));
    }

    @Test
    void shouldInitWithShortSecret() {
        BotProperties props = new BotProperties();
        props.getDashboard().setJwtSecret("short");
        JwtTokenProvider shortProvider = new JwtTokenProvider(props);
        shortProvider.init();

        String token = shortProvider.generateAccessToken("admin");
        assertNotNull(token);
        assertTrue(shortProvider.validateToken(token));
    }

    @Test
    void shouldNotValidateTokenFromDifferentKey() {
        BotProperties otherProps = new BotProperties();
        otherProps.getDashboard().setJwtSecret("completely-different-secret-key-for-hmac-sha256");
        JwtTokenProvider otherProvider = new JwtTokenProvider(otherProps);
        otherProvider.init();

        String token = provider.generateAccessToken("admin");
        assertFalse(otherProvider.validateToken(token));
    }
}
