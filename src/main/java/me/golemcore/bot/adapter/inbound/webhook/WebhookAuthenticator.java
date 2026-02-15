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

import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.UserPreferencesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Authenticates inbound webhook requests using Bearer token or HMAC signature.
 *
 * <p>
 * Bearer token is read from {@link UserPreferences.WebhookConfig#getToken()}.
 * HMAC verification is used for custom hook mappings that specify
 * {@code authMode=hmac}.
 *
 * <p>
 * All comparisons use constant-time algorithms to prevent timing attacks.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookAuthenticator {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CUSTOM_HEADER = "X-Golemcore-Token";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final UserPreferencesService preferencesService;

    /**
     * Authenticates a request using the global Bearer token.
     *
     * @param headers
     *            HTTP headers containing Authorization or custom token header
     * @return {@code true} if the token matches the configured secret
     */
    public boolean authenticateBearer(HttpHeaders headers) {
        UserPreferences.WebhookConfig config = preferencesService.getPreferences().getWebhooks();
        if (config == null || config.getToken() == null || config.getToken().isBlank()) {
            log.warn("[Webhook] No token configured, rejecting request");
            return false;
        }

        String expected = config.getToken();

        // Try Authorization: Bearer <token>
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String provided = authHeader.substring(BEARER_PREFIX.length());
            return constantTimeEquals(expected, provided);
        }

        // Try custom header
        String customToken = headers.getFirst(CUSTOM_HEADER);
        if (customToken != null) {
            return constantTimeEquals(expected, customToken);
        }

        log.debug("[Webhook] No authentication token found in request headers");
        return false;
    }

    /**
     * Verifies an HMAC-SHA256 signature for a custom hook mapping.
     *
     * @param mapping
     *            the hook mapping with HMAC configuration
     * @param headers
     *            HTTP headers containing the signature
     * @param body
     *            raw request body bytes
     * @return {@code true} if the signature is valid
     */
    public boolean authenticateHmac(UserPreferences.HookMapping mapping, HttpHeaders headers, byte[] body) {
        if (mapping.getHmacSecret() == null || mapping.getHmacSecret().isBlank()) {
            log.warn("[Webhook] HMAC secret not configured for mapping: {}", mapping.getName());
            return false;
        }
        if (mapping.getHmacHeader() == null || mapping.getHmacHeader().isBlank()) {
            log.warn("[Webhook] HMAC header not configured for mapping: {}", mapping.getName());
            return false;
        }

        String signatureHeader = headers.getFirst(mapping.getHmacHeader());
        if (signatureHeader == null) {
            log.debug("[Webhook] HMAC header '{}' not present in request", mapping.getHmacHeader());
            return false;
        }

        // Strip prefix (e.g. "sha256=")
        String signature = signatureHeader;
        if (mapping.getHmacPrefix() != null && !mapping.getHmacPrefix().isEmpty()) {
            if (!signatureHeader.startsWith(mapping.getHmacPrefix())) {
                log.debug("[Webhook] HMAC header missing expected prefix: {}", mapping.getHmacPrefix());
                return false;
            }
            signature = signatureHeader.substring(mapping.getHmacPrefix().length());
        }

        String expectedSignature = computeHmacSha256(mapping.getHmacSecret(), body);
        if (expectedSignature == null) {
            return false;
        }

        return constantTimeEquals(expectedSignature, signature);
    }

    /**
     * Authenticates a request for a specific hook mapping. Dispatches to Bearer or
     * HMAC depending on the mapping's {@code authMode}.
     */
    public boolean authenticate(UserPreferences.HookMapping mapping, HttpHeaders headers, byte[] body) {
        if ("hmac".equals(mapping.getAuthMode())) {
            return authenticateHmac(mapping, headers, body);
        }
        return authenticateBearer(headers);
    }

    private String computeHmacSha256(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("[Webhook] Failed to compute HMAC: {}", e.getMessage());
            return null;
        }
    }

    private boolean constantTimeEquals(String expected, String provided) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }
}
