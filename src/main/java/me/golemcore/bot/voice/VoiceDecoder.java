package me.golemcore.bot.voice;

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

import me.golemcore.bot.domain.model.AudioFormat;

/**
 * Interface for audio decoding operations.
 *
 * <p>
 * Converts encoded audio formats to raw PCM for STT processing. Typical use
 * case is converting Telegram OGG Opus voice messages to Whisper-compatible
 * 16kHz mono PCM.
 *
 * @since 1.0
 * @see JaffreeVoiceProcessor
 */
public interface VoiceDecoder {

    /**
     * Decode OGG Opus to PCM.
     */
    byte[] decode(byte[] oggOpusData);

    /**
     * Decode to Mono 16kHz PCM (for STT).
     */
    byte[] decodeToMono16k(byte[] audioData, AudioFormat format);
}
