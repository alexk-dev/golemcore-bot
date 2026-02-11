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

/**
 * User preferences for language, notifications, timezone, and model tier.
 * Persisted to storage and loaded on session initialization.
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
}
