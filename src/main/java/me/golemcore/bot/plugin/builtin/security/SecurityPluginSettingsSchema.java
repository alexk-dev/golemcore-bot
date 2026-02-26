package me.golemcore.bot.plugin.builtin.security;

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

import me.golemcore.bot.plugin.api.settings.PluginSettingsSchemas;
import me.golemcore.bot.plugin.api.settings.PluginSettingsSectionSchema;

import java.util.List;

/**
 * Declarative settings schema for Security Policy plugin UI.
 */
public final class SecurityPluginSettingsSchema {

    private SecurityPluginSettingsSchema() {
    }

    public static PluginSettingsSectionSchema create() {
        return new PluginSettingsSectionSchema(
                "security-policy-plugin",
                "advanced-security",
                "Security Policy Plugin",
                "Input protection and tool safety controls.",
                List.of(
                        PluginSettingsSchemas.toggle(
                                "security.sanitizeInput",
                                "Sanitize Input",
                                "Apply sanitization before processing."),
                        PluginSettingsSchemas.toggle(
                                "security.detectPromptInjection",
                                "Detect Prompt Injection",
                                "Enable prompt injection detection."),
                        PluginSettingsSchemas.toggle(
                                "security.detectCommandInjection",
                                "Detect Command Injection",
                                "Enable command injection detection."),
                        PluginSettingsSchemas.number(
                                "security.maxInputLength",
                                "Max Input Length",
                                "Maximum accepted input characters.",
                                128.0,
                                200000.0,
                                1.0,
                                null),
                        PluginSettingsSchemas.toggle(
                                "security.allowlistEnabled",
                                "Allowlist Enabled",
                                "Restrict Telegram users by allowlist."),
                        PluginSettingsSchemas.toggle(
                                "security.toolConfirmationEnabled",
                                "Tool Confirmation",
                                "Require confirmation for risky tool actions."),
                        PluginSettingsSchemas.number(
                                "security.toolConfirmationTimeoutSeconds",
                                "Confirmation Timeout (s)",
                                "Timeout for pending confirmations.",
                                5.0,
                                3600.0,
                                1.0,
                                null)));
    }
}
