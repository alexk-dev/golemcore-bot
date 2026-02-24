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
 * Declarative settings schema for voice routing plugin UI.
 */
public final class VoiceRoutingPluginSettingsSchema {

    private VoiceRoutingPluginSettingsSchema() {
    }

    public static PluginSettingsSectionSchema create() {
        return new PluginSettingsSectionSchema(
                "elevenlabs-voice-plugin",
                "tool-voice",
                "Voice Routing Plugin",
                "Voice routing between STT/TTS providers.",
                List.of(
                        PluginSettingsSchemas.toggle(
                                "voice.enabled",
                                "Enabled",
                                "Enable voice pipeline."),
                        PluginSettingsSchemas.select(
                                "voice.sttProvider",
                                "STT Provider",
                                "Provider for speech-to-text.",
                                List.of(
                                        PluginSettingsSchemas.option("elevenlabs", "ElevenLabs"),
                                        PluginSettingsSchemas.option("whisper", "Whisper-compatible"))),
                        PluginSettingsSchemas.select(
                                "voice.ttsProvider",
                                "TTS Provider",
                                "Provider for text-to-speech.",
                                List.of(PluginSettingsSchemas.option("elevenlabs", "ElevenLabs"))),
                        PluginSettingsSchemas.toggle(
                                "voice.telegramRespondWithVoice",
                                "Telegram Voice Replies",
                                "Send voice responses in Telegram when possible."),
                        PluginSettingsSchemas.toggle(
                                "voice.telegramTranscribeIncoming",
                                "Telegram Incoming Transcription",
                                "Transcribe incoming Telegram voice messages.")));
    }
}
