package me.golemcore.bot.domain.model;

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
 * Supported audio formats for voice message processing. Each format includes
 * file extension, MIME type, and sample rate information.
 */
public enum AudioFormat {
    OGG_OPUS("ogg", "audio/ogg"), MP3("mp3", "audio/mpeg"), WAV("wav", "audio/wav"), PCM_16K("pcm",
            "audio/pcm"), PCM_44K("pcm", "audio/pcm");

    private final String extension;
    private final String mimeType;

    AudioFormat(String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    public String getExtension() {
        return extension;
    }

    public String getMimeType() {
        return mimeType;
    }

    public int getSampleRate() {
        return switch (this) {
        case PCM_16K -> 16000;
        case PCM_44K -> 44100;
        default -> 48000;
        };
    }
}
