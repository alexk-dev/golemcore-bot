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
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.PipeInput;
import com.github.kokorin.jaffree.ffmpeg.PipeOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Audio transcoding processor using Jaffree (FFmpeg Java wrapper).
 *
 * <p>
 * Implements both {@link VoiceDecoder} and {@link VoiceEncoder} interfaces for
 * audio format conversion. Uses FFmpeg via pipes for in-memory processing
 * without temporary files.
 *
 * <p>
 * Decoding capabilities:
 * <ul>
 * <li>Convert any format (OGG Opus, MP3, WAV, etc.) to 16kHz mono PCM</li>
 * <li>Suitable for Whisper STT input preparation</li>
 * </ul>
 *
 * <p>
 * Encoding capabilities:
 * <ul>
 * <li>Convert PCM to OGG Opus for Telegram voice messages</li>
 * <li>Configurable bitrate (default 64kbps)</li>
 * </ul>
 *
 * <p>
 * Requires FFmpeg binary in system PATH. Always available as a Spring bean.
 *
 * @since 1.0
 */
@Component
@Slf4j
public class JaffreeVoiceProcessor implements VoiceDecoder, VoiceEncoder {

    @Override
    public byte[] decode(byte[] oggOpusData) {
        return decodeToMono16k(oggOpusData, AudioFormat.OGG_OPUS);
    }

    @Override
    public byte[] decodeToMono16k(byte[] audioData, AudioFormat format) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(audioData);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            FFmpeg.atPath()
                    .addInput(PipeInput.pumpFrom(input))
                    .addArguments("-f", "s16le")
                    .addArguments("-ar", "16000")
                    .addArguments("-ac", "1")
                    .addOutput(PipeOutput.pumpTo(output))
                    .execute();

            return output.toByteArray();
        } catch (Exception e) {
            log.error("Failed to decode audio", e);
            throw new RuntimeException("Audio decoding failed", e);
        }
    }

    @Override
    public byte[] encodeToOggOpus(byte[] pcmData, int sampleRate) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(pcmData);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            FFmpeg.atPath()
                    .addArguments("-f", "s16le")
                    .addArguments("-ar", String.valueOf(sampleRate))
                    .addArguments("-ac", "1")
                    .addInput(PipeInput.pumpFrom(input))
                    .addArguments("-c:a", "libopus")
                    .addArguments("-b:a", "64k")
                    .addArguments("-f", "ogg")
                    .addOutput(PipeOutput.pumpTo(output))
                    .execute();

            return output.toByteArray();
        } catch (Exception e) {
            log.error("Failed to encode to OGG Opus", e);
            throw new RuntimeException("Audio encoding failed", e);
        }
    }

    @Override
    public byte[] encodeToMp3(byte[] pcmData, int sampleRate, int bitrate) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(pcmData);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            FFmpeg.atPath()
                    .addArguments("-f", "s16le")
                    .addArguments("-ar", String.valueOf(sampleRate))
                    .addArguments("-ac", "1")
                    .addInput(PipeInput.pumpFrom(input))
                    .addArguments("-c:a", "libmp3lame")
                    .addArguments("-b:a", bitrate + "k")
                    .addArguments("-f", "mp3")
                    .addOutput(PipeOutput.pumpTo(output))
                    .execute();

            return output.toByteArray();
        } catch (Exception e) {
            log.error("Failed to encode to MP3", e);
            throw new RuntimeException("Audio encoding failed", e);
        }
    }
}
