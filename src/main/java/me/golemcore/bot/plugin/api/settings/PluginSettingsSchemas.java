package me.golemcore.bot.plugin.api.settings;

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

import java.util.List;

/**
 * Factory helpers for declarative plugin settings schema entities.
 */
public final class PluginSettingsSchemas {

    private PluginSettingsSchemas() {
    }

    public static PluginSettingsFieldOption option(String value, String label) {
        return new PluginSettingsFieldOption(value, label);
    }

    public static PluginSettingsFieldSchema toggle(String key, String label, String help) {
        return field(key, label, help, PluginSettingsFieldType.SWITCH, null, null, null, null, List.of());
    }

    public static PluginSettingsFieldSchema text(String key, String label, String help, String placeholder) {
        return field(key, label, help, PluginSettingsFieldType.TEXT, placeholder, null, null, null, List.of());
    }

    public static PluginSettingsFieldSchema password(String key, String label, String help, String placeholder) {
        return field(key, label, help, PluginSettingsFieldType.PASSWORD, placeholder, null, null, null, List.of());
    }

    public static PluginSettingsFieldSchema url(String key, String label, String help, String placeholder) {
        return field(key, label, help, PluginSettingsFieldType.URL, placeholder, null, null, null, List.of());
    }

    public static PluginSettingsFieldSchema number(
            String key,
            String label,
            String help,
            Double min,
            Double max,
            Double step,
            String placeholder) {
        return field(key, label, help, PluginSettingsFieldType.NUMBER, placeholder, min, max, step, List.of());
    }

    public static PluginSettingsFieldSchema select(
            String key,
            String label,
            String help,
            List<PluginSettingsFieldOption> options) {
        return field(key, label, help, PluginSettingsFieldType.SELECT, null, null, null, null, options);
    }

    public static PluginSettingsFieldSchema field(
            String key,
            String label,
            String help,
            PluginSettingsFieldType type,
            String placeholder,
            Double min,
            Double max,
            Double step,
            List<PluginSettingsFieldOption> options) {
        return new PluginSettingsFieldSchema(key, label, help, type, placeholder, min, max, step, options);
    }
}
