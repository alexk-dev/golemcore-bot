package me.golemcore.bot.port.outbound;

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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Port for voice processing including speech-to-text (STT) and text-to-speech
 * (TTS).
 */
public interface VoicePort {

    /**
     * Transcribe audio to text (Speech-to-Text).
     */
    CompletableFuture<TranscriptionResult> transcribe(byte[] audioData, AudioFormat format);

    /**
     * Synthesize text to audio (Text-to-Speech). Returns MP3 bytes.
     */
    CompletableFuture<byte[]> synthesize(String text, VoiceConfig config);

    /**
     * Check if the voice service is available.
     */
    boolean isAvailable();

    /**
     * Result of speech-to-text transcription including text, metadata, and word-level timestamps.
     */
    record TranscriptionResult(
            String text,
            String language,
            float confidence,
            Duration duration,
            List<WordTimestamp> words
    ) {}

    /**
     * Word-level timestamp for generating subtitles or precise audio alignment.
     */
    record WordTimestamp(
            String word,
            Duration start,
            Duration end
    ) {}

    /**
     * Configuration for text-to-speech synthesis including voice, language, and audio parameters.
     */
    record VoiceConfig(
            String voiceId,
            String modelId,
            float speed,
            AudioFormat outputFormat
    ) {
        /**
         * Returns default voice configuration with standard settings.
         */
        public static VoiceConfig defaultConfig() {
            return new VoiceConfig(null, null, 1.0f, AudioFormat.MP3);
        }
    }
}
