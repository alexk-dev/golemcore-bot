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

import me.golemcore.bot.plugin.builtin.voice.adapter.ElevenLabsAdapter;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.plugin.api.PluginContext;
import me.golemcore.bot.plugin.api.settings.PluginSettingsSectionSchema;
import me.golemcore.bot.plugin.builtin.AbstractPlugin;
import me.golemcore.bot.port.outbound.VoicePort;
import me.golemcore.bot.plugin.builtin.voice.tool.VoiceResponseTool;

/**
 * Built-in plugin for ElevenLabs STT/TTS integration.
 */
public final class ElevenLabsVoicePlugin extends AbstractPlugin {

    public ElevenLabsVoicePlugin() {
        super(
                "elevenlabs-voice-plugin",
                "ElevenLabs Voice",
                "ElevenLabs speech-to-text and text-to-speech integration.",
                "port:voice",
                "tool:send_voice",
                "voice:elevenlabs");
    }

    @Override
    public void start(PluginContext context) {
        resetContributions();
        ElevenLabsAdapter elevenLabsAdapter = context.requireService(ElevenLabsAdapter.class);
        VoiceResponseTool voiceResponseTool = context.requireService(VoiceResponseTool.class);

        addContribution("port.voice", VoicePort.class, elevenLabsAdapter);
        addContribution("tool.send_voice", ToolComponent.class, voiceResponseTool);
        addContribution(
                "settings.schema.tool-voice",
                PluginSettingsSectionSchema.class,
                VoiceRoutingPluginSettingsSchema.create());
        addContribution(
                "settings.schema.voice-elevenlabs",
                PluginSettingsSectionSchema.class,
                ElevenLabsPluginSettingsSchema.create());
    }
}
