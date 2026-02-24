package me.golemcore.bot.plugin.builtin.telegram;

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
 * Declarative settings schema for Telegram plugin UI.
 */
public final class TelegramPluginSettingsSchema {

    private TelegramPluginSettingsSchema() {
    }

    public static PluginSettingsSectionSchema create() {
        return new PluginSettingsSectionSchema(
                "telegram-api-plugin",
                "telegram",
                "Telegram API Plugin",
                "Telegram channel settings managed via plugin runtime.",
                List.of(
                        PluginSettingsSchemas.toggle(
                                "telegram.enabled",
                                "Enabled",
                                "Enable Telegram channel integration."),
                        PluginSettingsSchemas.password(
                                "telegram.token",
                                "Bot Token",
                                "Telegram bot API token.",
                                "Enter bot token"),
                        PluginSettingsSchemas.select(
                                "telegram.authMode",
                                "Auth Mode",
                                "User admission mode for Telegram interactions.",
                                List.of(PluginSettingsSchemas.option("invite_only", "Invite only")))));
    }
}
