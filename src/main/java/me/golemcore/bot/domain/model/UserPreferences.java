package me.golemcore.bot.domain.model;

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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User preferences for language, notifications, timezone, model tier, and
 * per-tier model overrides. Persisted to storage and loaded on session
 * initialization.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPreferences {

    @Builder.Default
    private String language = "en";

    @Builder.Default
    private boolean notificationsEnabled = true;

    @Builder.Default
    private String timezone = "UTC";

    /** User-selected model tier (null = use "balanced" default) */
    @Builder.Default
    private String modelTier = null;

    /** When true, locks the tier — ignores skill overrides and DynamicTierSystem */
    @Builder.Default
    private boolean tierForce = false;

    /**
     * Per-tier model overrides (e.g. "coding" -> {model="openai/gpt-5.2",
     * reasoning="high"})
     */
    @Builder.Default
    private Map<String, TierOverride> tierOverrides = new HashMap<>();

    /** Webhook configuration (all three endpoint types). */
    @Builder.Default
    private WebhookConfig webhooks = new WebhookConfig();

    /**
     * Per-tier model override, allowing users to assign specific models and
     * reasoning levels to each tier.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TierOverride {
        /** Full model spec, e.g. "openai/gpt-5.2" */
        private String model;
        /** Reasoning level, e.g. "high", or null for non-reasoning models */
        private String reasoning;
    }

    /**
     * Webhook configuration for inbound HTTP triggers (OpenClaw-style). Contains
     * global settings and a list of custom hook mappings.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WebhookConfig {
        /** Master switch for webhook endpoints. */
        @Builder.Default
        private boolean enabled = false;

        /** Shared secret for Bearer token authentication. */
        private Secret token;

        /** Maximum payload size in bytes. */
        @Builder.Default
        private int maxPayloadSize = 65536;

        /** Default timeout for /agent runs (seconds). */
        @Builder.Default
        private int defaultTimeoutSeconds = 300;

        /** Custom hook mappings for {@code POST /api/hooks/{name}}. */
        @Builder.Default
        private List<HookMapping> mappings = new ArrayList<>();
    }

    /**
     * A single custom hook mapping that transforms external payloads (GitHub,
     * Stripe, etc.) into wake or agent actions.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class HookMapping {
        /** Unique name used in the URL path: {@code /api/hooks/{name}}. */
        private String name;

        /** Action type: {@code "wake"} or {@code "agent"}. */
        @Builder.Default
        private String action = "wake";

        /** Authentication mode: {@code "bearer"} (default) or {@code "hmac"}. */
        @Builder.Default
        private String authMode = "bearer";

        /** Header containing HMAC signature (e.g. {@code x-hub-signature-256}). */
        private String hmacHeader;

        /** HMAC shared secret for signature verification. */
        private Secret hmacSecret;

        /** Prefix stripped from HMAC header value (e.g. {@code sha256=}). */
        private String hmacPrefix;

        /**
         * Message template with {@code {field.path}} placeholders resolved against the
         * incoming JSON body.
         */
        private String messageTemplate;

        /** Model tier override for agent action. */
        private String model;

        /** Route agent response to a messaging channel. */
        @Builder.Default
        private boolean deliver = false;

        /** Target channel type for delivery (e.g. {@code "telegram"}). */
        private String channel;

        /** Target chat ID on the delivery channel. */
        private String to;
    }
}
