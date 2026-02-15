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

import java.util.HashMap;
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
}
