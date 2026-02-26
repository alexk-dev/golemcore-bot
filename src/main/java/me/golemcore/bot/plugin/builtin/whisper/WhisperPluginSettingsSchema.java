package me.golemcore.bot.plugin.builtin.whisper;

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
 * Declarative settings schema for Whisper STT plugin UI.
 */
public final class WhisperPluginSettingsSchema {

    private WhisperPluginSettingsSchema() {
    }

    public static PluginSettingsSectionSchema create() {
        return new PluginSettingsSectionSchema(
                "whisper-stt-plugin",
                "voice-whisper",
                "Whisper STT Plugin",
                "Whisper-compatible STT endpoint and credentials.",
                List.of(
                        PluginSettingsSchemas.url(
                                "voice.whisperSttUrl",
                                "Whisper STT URL",
                                "Endpoint of Whisper-compatible server.",
                                "http://localhost:5092"),
                        PluginSettingsSchemas.password(
                                "voice.whisperSttApiKey",
                                "Whisper API Key",
                                "Optional API key for Whisper endpoint.",
                                "Enter API key (optional)")));
    }
}
