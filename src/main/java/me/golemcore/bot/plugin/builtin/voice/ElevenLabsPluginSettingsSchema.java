package me.golemcore.bot.plugin.builtin.voice;

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
 * Declarative settings schema for ElevenLabs provider UI.
 */
public final class ElevenLabsPluginSettingsSchema {

    private ElevenLabsPluginSettingsSchema() {
    }

    public static PluginSettingsSectionSchema create() {
        return new PluginSettingsSectionSchema(
                "elevenlabs-voice-plugin",
                "voice-elevenlabs",
                "ElevenLabs Voice Plugin",
                "ElevenLabs credentials and model tuning.",
                List.of(
                        PluginSettingsSchemas.password(
                                "voice.apiKey",
                                "API Key",
                                "ElevenLabs API key.",
                                "Enter API key"),
                        PluginSettingsSchemas.text(
                                "voice.voiceId",
                                "Voice ID",
                                "ElevenLabs voice identifier.",
                                "voice-id"),
                        PluginSettingsSchemas.text(
                                "voice.ttsModelId",
                                "TTS Model",
                                "Text-to-speech model identifier.",
                                "eleven_multilingual_v2"),
                        PluginSettingsSchemas.text(
                                "voice.sttModelId",
                                "STT Model",
                                "Speech-to-text model identifier.",
                                "scribe_v1"),
                        PluginSettingsSchemas.number(
                                "voice.speed",
                                "Voice Speed",
                                "Speech synthesis speed.",
                                0.5,
                                2.0,
                                0.1,
                                null)));
    }
}
