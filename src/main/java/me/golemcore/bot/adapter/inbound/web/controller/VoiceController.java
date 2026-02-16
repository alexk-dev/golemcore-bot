package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.port.outbound.VoicePort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
public class VoiceController {

    private static final int MAX_AUDIO_BYTES = 25 * 1024 * 1024;

    private final VoicePort voicePort;

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<TranscriptionResponse>> transcribe(
            @RequestPart("file") FilePart filePart) {

        if (!voicePort.isAvailable()) {
            return Mono.error(new ResponseStatusException(SERVICE_UNAVAILABLE, "Voice service is not configured"));
        }

        return filePart.content()
                .reduce(new ByteAccumulator(), (acc, dataBuffer) -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                    acc.append(bytes);
                    if (acc.size() > MAX_AUDIO_BYTES) {
                        throw new ResponseStatusException(BAD_REQUEST, "Audio file is too large");
                    }
                    return acc;
                })
                .flatMap(acc -> {
                    byte[] audio = acc.toByteArray();
                    if (audio.length == 0) {
                        return Mono.error(new ResponseStatusException(BAD_REQUEST, "Empty audio file"));
                    }
                    AudioFormat format = detectFormat(filePart.headers().getContentType());
                    return Mono.fromFuture(voicePort.transcribe(audio, format));
                })
                .map(result -> ResponseEntity.ok(new TranscriptionResponse(
                        result.text(),
                        result.language(),
                        result.confidence())));
    }

    private AudioFormat detectFormat(MediaType mediaType) {
        if (mediaType == null) {
            return AudioFormat.OGG_OPUS;
        }
        String mt = mediaType.toString().toLowerCase();
        if (mt.contains("mpeg") || mt.contains("mp3")) {
            return AudioFormat.MP3;
        }
        if (mt.contains("wav")) {
            return AudioFormat.WAV;
        }
        // webm/ogg/opus are mapped to OGG_OPUS for current voice adapter
        return AudioFormat.OGG_OPUS;
    }

    public record TranscriptionResponse(
            String text,
            String language,
            float confidence) {
    }

    private static final class ByteAccumulator {
        private java.util.List<byte[]> chunks = new java.util.ArrayList<>();
        private int size = 0;

        void append(byte[] b) {
            chunks.add(b);
            size += b.length;
        }

        int size() {
            return size;
        }

        byte[] toByteArray() {
            byte[] out = new byte[size];
            int offset = 0;
            for (byte[] c : chunks) {
                System.arraycopy(c, 0, out, offset, c.length);
                offset += c.length;
            }
            return out;
        }
    }
}
