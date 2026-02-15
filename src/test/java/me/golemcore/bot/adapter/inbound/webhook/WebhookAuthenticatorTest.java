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
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer test");

        assertFalse(authenticator.authenticateBearer(headers));
    }

    // ==================== HMAC ====================

    @Test
    void shouldAcceptValidHmacSignature() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("github")
                .authMode("hmac")
                .hmacHeader("x-hub-signature-256")
                .hmacSecret("webhook-secret")
                .hmacPrefix("sha256=")
                .build();

        byte[] body = "{\"action\":\"push\"}".getBytes(StandardCharsets.UTF_8);

        // Pre-compute expected HMAC
        String expectedSignature = computeHmac("webhook-secret", body);

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-hub-signature-256", "sha256=" + expectedSignature);

        assertTrue(authenticator.authenticateHmac(mapping, headers, body));
    }

    @Test
    void shouldRejectInvalidHmacSignature() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("github")
                .authMode("hmac")
                .hmacHeader("x-hub-signature-256")
                .hmacSecret("webhook-secret")
                .hmacPrefix("sha256=")
                .build();

        byte[] body = "{\"action\":\"push\"}".getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-hub-signature-256", "sha256=deadbeef");

        assertFalse(authenticator.authenticateHmac(mapping, headers, body));
    }

    @Test
    void shouldRejectMissingHmacHeader() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("github")
                .authMode("hmac")
                .hmacHeader("x-hub-signature-256")
                .hmacSecret("webhook-secret")
                .build();

        HttpHeaders headers = new HttpHeaders();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertFalse(authenticator.authenticateHmac(mapping, headers, body));
    }

    @Test
    void shouldRejectWhenHmacPrefixMissing() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("github")
                .authMode("hmac")
                .hmacHeader("x-hub-signature-256")
                .hmacSecret("webhook-secret")
                .hmacPrefix("sha256=")
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-hub-signature-256", "no-prefix-here");
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertFalse(authenticator.authenticateHmac(mapping, headers, body));
    }

    // ==================== authenticate() dispatch ====================

    @Test
    void shouldDispatchToBearerWhenAuthModeIsBearer() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("test")
                .authMode("bearer")
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET);

        assertTrue(authenticator.authenticate(mapping, headers, new byte[0]));
    }

    @Test
    void shouldDispatchToHmacWhenAuthModeIsHmac() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("test")
                .authMode("hmac")
                .hmacHeader("x-sig")
                .hmacSecret("s3cret")
                .build();

        byte[] body = "test".getBytes(StandardCharsets.UTF_8);
        String sig = computeHmac("s3cret", body);

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-sig", sig);

        assertTrue(authenticator.authenticate(mapping, headers, body));
    }

    // Helper: compute HMAC-SHA256 hex
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
