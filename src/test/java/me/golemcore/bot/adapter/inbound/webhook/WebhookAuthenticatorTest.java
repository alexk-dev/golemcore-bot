package me.golemcore.bot.adapter.inbound.webhook;

import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookAuthenticatorTest {

    private static final String SECRET = "test-secret-token";
    private static final String HOOK_NAME = "github";
    private static final String AUTH_HMAC = "hmac";
    private static final String HMAC_HEADER = "x-hub-signature-256";
    private static final String HMAC_SECRET = "webhook-secret";
    private static final String HMAC_PREFIX = "sha256=";

    private UserPreferencesService preferencesService;
    private WebhookAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class);
        authenticator = new WebhookAuthenticator(preferencesService);

        UserPreferences prefs = UserPreferences.builder()
                .webhooks(UserPreferences.WebhookConfig.builder()
                        .enabled(true)
                        .token(SECRET)
                        .build())
                .build();
        when(preferencesService.getPreferences()).thenReturn(prefs);
    }

    // ==================== Bearer token ====================

    @Test
    void shouldAcceptValidBearerToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET);

        assertTrue(authenticator.authenticateBearer(headers));
    }

    @Test
    void shouldRejectInvalidBearerToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer wrong-token");

        assertFalse(authenticator.authenticateBearer(headers));
    }

    @Test
    void shouldRejectMissingAuthorizationHeader() {
        HttpHeaders headers = new HttpHeaders();

        assertFalse(authenticator.authenticateBearer(headers));
    }

    @Test
    void shouldAcceptCustomTokenHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Golemcore-Token", SECRET);

        assertTrue(authenticator.authenticateBearer(headers));
    }

    @Test
    void shouldRejectInvalidCustomTokenHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Golemcore-Token", "wrong");

        assertFalse(authenticator.authenticateBearer(headers));
    }

    @Test
    void shouldRejectWhenNoTokenConfigured() {
        UserPreferences prefs = UserPreferences.builder()
                .webhooks(UserPreferences.WebhookConfig.builder()
                        .enabled(true)
                        .token(null)
                        .build())
                .build();
        when(preferencesService.getPreferences()).thenReturn(prefs);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer any");

        assertFalse(authenticator.authenticateBearer(headers));
    }

    @Test
    void shouldRejectWhenBlankTokenConfigured() {
        UserPreferences prefs = UserPreferences.builder()
                .webhooks(UserPreferences.WebhookConfig.builder()
                        .enabled(true)
                        .token("  ")
                        .build())
                .build();
        when(preferencesService.getPreferences()).thenReturn(prefs);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer test-value");

        assertFalse(authenticator.authenticateBearer(headers));
    }

    // ==================== HMAC ====================

    @Test
    void shouldAcceptValidHmacSignature() {
        UserPreferences.HookMapping mapping = buildHmacMapping();

        byte[] body = "{\"action\":\"push\"}".getBytes(StandardCharsets.UTF_8);
        String expectedSignature = computeHmac(HMAC_SECRET, body);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HMAC_HEADER, HMAC_PREFIX + expectedSignature);

        assertTrue(authenticator.authenticateHmac(mapping, headers, body));
    }

    @Test
    void shouldRejectInvalidHmacSignature() {
        UserPreferences.HookMapping mapping = buildHmacMapping();

        byte[] body = "{\"action\":\"push\"}".getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HMAC_HEADER, HMAC_PREFIX + "deadbeef");

        assertFalse(authenticator.authenticateHmac(mapping, headers, body));
    }

    @Test
    void shouldRejectMissingHmacHeader() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name(HOOK_NAME)
                .authMode(AUTH_HMAC)
                .hmacHeader(HMAC_HEADER)
                .hmacSecret(HMAC_SECRET)
                .build();

        HttpHeaders headers = new HttpHeaders();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertFalse(authenticator.authenticateHmac(mapping, headers, body));
    }

    @Test
    void shouldRejectWhenHmacPrefixMissing() {
        UserPreferences.HookMapping mapping = buildHmacMapping();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HMAC_HEADER, "no-prefix-here");
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertFalse(authenticator.authenticateHmac(mapping, headers, body));
    }

    // ==================== authenticate() dispatch ====================

    @Test
    void shouldDispatchToBearerWhenAuthModeIsBearer() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("dispatch-test")
                .authMode("bearer")
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET);

        assertTrue(authenticator.authenticate(mapping, headers, new byte[0]));
    }

    @Test
    void shouldDispatchToHmacWhenAuthModeIsHmac() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("dispatch-test")
                .authMode(AUTH_HMAC)
                .hmacHeader("x-sig")
                .hmacSecret("s3cret")
                .build();

        byte[] body = "test-body".getBytes(StandardCharsets.UTF_8);
        String sig = computeHmac("s3cret", body);

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-sig", sig);

        assertTrue(authenticator.authenticate(mapping, headers, body));
    }

    private UserPreferences.HookMapping buildHmacMapping() {
        return UserPreferences.HookMapping.builder()
                .name(HOOK_NAME)
                .authMode(AUTH_HMAC)
                .hmacHeader(HMAC_HEADER)
                .hmacSecret(HMAC_SECRET)
                .hmacPrefix(HMAC_PREFIX)
                .build();
    }

    private String computeHmac(String secret, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data);
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
