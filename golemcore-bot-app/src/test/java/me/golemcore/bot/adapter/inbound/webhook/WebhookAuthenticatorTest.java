/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

package me.golemcore.bot.adapter.inbound.webhook;

import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookAuthenticatorTest {

    private static final String SECRET_TOKEN = "test-secret-token-123";
    private static final String HMAC_SECRET = "my-hmac-secret";
    private static final String HMAC_HEADER = "x-hub-signature-256";
    private static final String HMAC_PREFIX = "sha256=";
    private static final String BEARER = "Bearer ";
    private static final String TEST_BODY = "test-body";

    private UserPreferencesService preferencesService;
    private WebhookAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class);
        authenticator = new WebhookAuthenticator(preferencesService);
    }

    // --- Bearer token tests ---

    @Test
    void shouldAuthenticateBearerWithAuthorizationHeader() {
        configureToken(SECRET_TOKEN);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, BEARER + SECRET_TOKEN);

        assertTrue(authenticator.authenticateBearer(headers));
    }

    @Test
    void shouldAuthenticateBearerWithCustomHeader() {
        configureToken(SECRET_TOKEN);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Golemcore-Token", SECRET_TOKEN);

        assertTrue(authenticator.authenticateBearer(headers));
    }

    @Test
    void shouldRejectBearerWithWrongToken() {
        configureToken(SECRET_TOKEN);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer wrong-token");

        assertFalse(authenticator.authenticateBearer(headers));
    }

    @Test
    void shouldRejectBearerWithWrongCustomHeader() {
        configureToken(SECRET_TOKEN);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Golemcore-Token", "wrong-token");

        assertFalse(authenticator.authenticateBearer(headers));
    }

    @Test
    void shouldRejectBearerWhenNoHeaderPresent() {
        configureToken(SECRET_TOKEN);
        HttpHeaders headers = new HttpHeaders();

        assertFalse(authenticator.authenticateBearer(headers));
    }

    @Test
    void shouldRejectBearerWhenNoTokenConfigured() {
        configureToken(null);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer some-token");

        assertFalse(authenticator.authenticateBearer(headers));
    }

    @Test
    void shouldRejectBearerWhenTokenIsBlank() {
        configureToken("   ");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer some-token");

        assertFalse(authenticator.authenticateBearer(headers));
    }

    @Test
    void shouldRejectBearerWhenWebhookConfigIsNull() {
        UserPreferences prefs = UserPreferences.builder().webhooks(null).build();
        when(preferencesService.getPreferences()).thenReturn(prefs);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer some-token");

        assertFalse(authenticator.authenticateBearer(headers));
    }

    @Test
    void shouldPreferAuthorizationHeaderOverCustomHeader() {
        configureToken(SECRET_TOKEN);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, BEARER + SECRET_TOKEN);
        headers.set("X-Golemcore-Token", "wrong-token");

        assertTrue(authenticator.authenticateBearer(headers));
    }

    // --- HMAC tests ---

    @Test
    void shouldAuthenticateHmacWithValidSignature() {
        UserPreferences.HookMapping mapping = createHmacMapping(HMAC_SECRET, HMAC_HEADER, HMAC_PREFIX);
        byte[] body = "{\"action\":\"push\"}".getBytes(StandardCharsets.UTF_8);
        String signature = computeHmac(HMAC_SECRET, body);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HMAC_HEADER, HMAC_PREFIX + signature);

        assertTrue(authenticator.authenticateHmac(mapping, headers, body));
    }

    @Test
    void shouldAuthenticateHmacWithoutPrefix() {
        UserPreferences.HookMapping mapping = createHmacMapping(HMAC_SECRET, HMAC_HEADER, null);
        byte[] body = TEST_BODY.getBytes(StandardCharsets.UTF_8);
        String signature = computeHmac(HMAC_SECRET, body);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HMAC_HEADER, signature);

        assertTrue(authenticator.authenticateHmac(mapping, headers, body));
    }

    @Test
    void shouldRejectHmacWithInvalidSignature() {
        UserPreferences.HookMapping mapping = createHmacMapping(HMAC_SECRET, HMAC_HEADER, HMAC_PREFIX);
        byte[] body = TEST_BODY.getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HMAC_HEADER, HMAC_PREFIX + "deadbeef");

        assertFalse(authenticator.authenticateHmac(mapping, headers, body));
    }

    @Test
    void shouldRejectHmacWhenHeaderMissing() {
        UserPreferences.HookMapping mapping = createHmacMapping(HMAC_SECRET, HMAC_HEADER, HMAC_PREFIX);
        byte[] body = TEST_BODY.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();

        assertFalse(authenticator.authenticateHmac(mapping, headers, body));
    }

    @Test
    void shouldRejectHmacWhenSecretNotConfigured() {
        UserPreferences.HookMapping mapping = createHmacMapping(null, HMAC_HEADER, HMAC_PREFIX);
        byte[] body = TEST_BODY.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HMAC_HEADER, HMAC_PREFIX + "abc");

        assertFalse(authenticator.authenticateHmac(mapping, headers, body));
    }

    @Test
    void shouldRejectHmacWhenSecretIsBlank() {
        UserPreferences.HookMapping mapping = createHmacMapping("  ", HMAC_HEADER, HMAC_PREFIX);
        byte[] body = TEST_BODY.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HMAC_HEADER, HMAC_PREFIX + "abc");

        assertFalse(authenticator.authenticateHmac(mapping, headers, body));
    }

    @Test
    void shouldRejectHmacWhenHeaderNameNotConfigured() {
        UserPreferences.HookMapping mapping = createHmacMapping(HMAC_SECRET, null, HMAC_PREFIX);
        byte[] body = TEST_BODY.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();

        assertFalse(authenticator.authenticateHmac(mapping, headers, body));
    }

    @Test
    void shouldRejectHmacWhenHeaderNameIsBlank() {
        UserPreferences.HookMapping mapping = createHmacMapping(HMAC_SECRET, "  ", HMAC_PREFIX);
        byte[] body = TEST_BODY.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();

        assertFalse(authenticator.authenticateHmac(mapping, headers, body));
    }

    @Test
    void shouldRejectHmacWhenPrefixDoesNotMatch() {
        UserPreferences.HookMapping mapping = createHmacMapping(HMAC_SECRET, HMAC_HEADER, "sha512=");
        byte[] body = TEST_BODY.getBytes(StandardCharsets.UTF_8);
        String signature = computeHmac(HMAC_SECRET, body);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HMAC_HEADER, "sha256=" + signature);

        assertFalse(authenticator.authenticateHmac(mapping, headers, body));
    }

    // --- authenticate() dispatch tests ---

    @Test
    void shouldDispatchToHmacWhenAuthModeIsHmac() {
        UserPreferences.HookMapping mapping = createHmacMapping(HMAC_SECRET, HMAC_HEADER, HMAC_PREFIX);
        mapping.setAuthMode("hmac");
        byte[] body = "test".getBytes(StandardCharsets.UTF_8);
        String signature = computeHmac(HMAC_SECRET, body);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HMAC_HEADER, HMAC_PREFIX + signature);

        assertTrue(authenticator.authenticate(mapping, headers, body));
    }

    @Test
    void shouldDispatchToBearerWhenAuthModeIsBearer() {
        configureToken(SECRET_TOKEN);
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("test-hook")
                .authMode("bearer")
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, BEARER + SECRET_TOKEN);

        assertTrue(authenticator.authenticate(mapping, headers, new byte[0]));
    }

    @Test
    void shouldDefaultToBearerWhenAuthModeIsNull() {
        configureToken(SECRET_TOKEN);
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("test-hook")
                .authMode(null)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, BEARER + SECRET_TOKEN);

        assertTrue(authenticator.authenticate(mapping, headers, new byte[0]));
    }

    // --- Helpers ---

    private void configureToken(String token) {
        UserPreferences.WebhookConfig config = UserPreferences.WebhookConfig.builder()
                .token(Secret.of(token))
                .build();
        UserPreferences prefs = UserPreferences.builder().webhooks(config).build();
        when(preferencesService.getPreferences()).thenReturn(prefs);
    }

    private UserPreferences.HookMapping createHmacMapping(String secret, String header, String prefix) {
        return UserPreferences.HookMapping.builder()
                .name("github")
                .authMode("hmac")
                .hmacSecret(Secret.of(secret))
                .hmacHeader(header)
                .hmacPrefix(prefix)
                .build();
    }

    private String computeHmac(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
