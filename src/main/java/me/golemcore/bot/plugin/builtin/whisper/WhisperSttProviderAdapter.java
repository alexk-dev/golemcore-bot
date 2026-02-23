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
import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.port.outbound.VoicePort;

final class WhisperSttProviderAdapter implements SttProvider {

    private final WhisperCompatibleSttAdapter delegate;

    WhisperSttProviderAdapter(WhisperCompatibleSttAdapter delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getProviderId() {
        return "whisper";
    }

    @Override
    public VoicePort.TranscriptionResult transcribe(byte[] audioData, AudioFormat format) {
        return delegate.transcribe(audioData, format);
    }

    @Override
    public boolean isAvailable() {
        return delegate.isHealthy();
    }
}
