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

/**
 * Interface for audio encoding operations.
 *
 * <p>
 * Converts raw PCM audio to compressed formats suitable for transmission.
 * Primary use case is encoding TTS-generated PCM to OGG Opus for Telegram voice
 * messages.
 *
 * @since 1.0
 * @see JaffreeVoiceProcessor
 */
public interface VoiceEncoder {

    /**
     * Encode PCM to OGG Opus (for Telegram).
     */
    byte[] encodeToOggOpus(byte[] pcmData, int sampleRate);

    /**
     * Encode to MP3.
     */
    byte[] encodeToMp3(byte[] pcmData, int sampleRate, int bitrate);
}
