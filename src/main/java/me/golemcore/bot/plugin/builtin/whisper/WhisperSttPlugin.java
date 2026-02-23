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

import me.golemcore.bot.adapter.outbound.voice.WhisperCompatibleSttAdapter;
import me.golemcore.bot.plugin.api.PluginContext;
import me.golemcore.bot.plugin.builtin.AbstractPlugin;

/**
 * Built-in plugin for Whisper-compatible STT providers.
 */
public final class WhisperSttPlugin extends AbstractPlugin {

    public WhisperSttPlugin() {
        super(
                "whisper-stt-plugin",
                "Whisper STT",
                "Whisper-compatible speech-to-text provider.",
                "stt:whisper");
    }

    @Override
    public void start(PluginContext context) {
        resetContributions();
        WhisperCompatibleSttAdapter sttAdapter = context.requireService(WhisperCompatibleSttAdapter.class);
        WhisperSttProviderAdapter providerAdapter = new WhisperSttProviderAdapter(sttAdapter);
        addContribution("stt.whisper", SttProvider.class, providerAdapter);
    }
}
