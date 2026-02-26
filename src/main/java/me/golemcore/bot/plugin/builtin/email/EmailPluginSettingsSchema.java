package me.golemcore.bot.plugin.builtin.email;

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
 * Declarative settings schema for Email plugin UI.
 */
public final class EmailPluginSettingsSchema {

    private EmailPluginSettingsSchema() {
    }

    public static PluginSettingsSectionSchema create() {
        return new PluginSettingsSectionSchema(
                "email-plugin",
                "tool-email",
                "Email Plugin",
                "IMAP and SMTP settings for email operations.",
                List.of(
                        PluginSettingsSchemas.toggle(
                                "tools.imap.enabled",
                                "IMAP Enabled",
                                "Enable IMAP read operations."),
                        PluginSettingsSchemas.text(
                                "tools.imap.host",
                                "IMAP Host",
                                "IMAP server hostname.",
                                "imap.example.com"),
                        PluginSettingsSchemas.number(
                                "tools.imap.port",
                                "IMAP Port",
                                "IMAP server port.",
                                1.0,
                                65535.0,
                                1.0,
                                null),
                        PluginSettingsSchemas.text(
                                "tools.imap.username",
                                "IMAP Username",
                                "IMAP account login.",
                                "user@example.com"),
                        PluginSettingsSchemas.password(
                                "tools.imap.password",
                                "IMAP Password",
                                "IMAP account password or app password.",
                                "Enter IMAP password"),
                        PluginSettingsSchemas.select(
                                "tools.imap.security",
                                "IMAP Security",
                                "IMAP transport security mode.",
                                List.of(
                                        PluginSettingsSchemas.option("none", "None"),
                                        PluginSettingsSchemas.option("ssl", "SSL/TLS"),
                                        PluginSettingsSchemas.option("starttls", "STARTTLS"))),
                        PluginSettingsSchemas.toggle(
                                "tools.smtp.enabled",
                                "SMTP Enabled",
                                "Enable SMTP send operations."),
                        PluginSettingsSchemas.text(
                                "tools.smtp.host",
                                "SMTP Host",
                                "SMTP server hostname.",
                                "smtp.example.com"),
                        PluginSettingsSchemas.number(
                                "tools.smtp.port",
                                "SMTP Port",
                                "SMTP server port.",
                                1.0,
                                65535.0,
                                1.0,
                                null),
                        PluginSettingsSchemas.text(
                                "tools.smtp.username",
                                "SMTP Username",
                                "SMTP account login.",
                                "user@example.com"),
                        PluginSettingsSchemas.password(
                                "tools.smtp.password",
                                "SMTP Password",
                                "SMTP account password or app password.",
                                "Enter SMTP password"),
                        PluginSettingsSchemas.select(
                                "tools.smtp.security",
                                "SMTP Security",
                                "SMTP transport security mode.",
                                List.of(
                                        PluginSettingsSchemas.option("none", "None"),
                                        PluginSettingsSchemas.option("ssl", "SSL/TLS"),
                                        PluginSettingsSchemas.option("starttls", "STARTTLS")))));
    }
}
