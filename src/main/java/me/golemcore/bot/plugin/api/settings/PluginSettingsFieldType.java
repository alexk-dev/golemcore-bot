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

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Supported declarative field types for plugin settings UI.
 */
public enum PluginSettingsFieldType {
    SWITCH("switch"), TEXT("text"), PASSWORD("password"), NUMBER("number"), SELECT("select"), URL("url");

    private final String code;

    PluginSettingsFieldType(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }
}
