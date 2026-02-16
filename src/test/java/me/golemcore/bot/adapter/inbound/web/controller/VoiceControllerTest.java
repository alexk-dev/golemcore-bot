package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.port.outbound.VoicePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VoiceControllerTest {

    private VoicePort voicePort;
    private VoiceController controller;

    @BeforeEach
    void setUp() {
        voicePort = mock(VoicePort.class);
        controller = new VoiceController(voicePort);
    }

    @Test
    void shouldReturn503WhenVoiceUnavailable() {
        when(voicePort.isAvailable()).thenReturn(false);

        StepVerifier.create(controller.transcribe(file(MediaType.valueOf("audio/webm"), "audio")))
                .expectErrorSatisfies(err -> {
                    ResponseStatusException ex = (ResponseStatusException) err;
                    assertEquals(503, ex.getStatusCode().value());
                })
                .verify();
    }

    @Test
    void shouldTranscribeWhenAvailable() {
        when(voicePort.isAvailable()).thenReturn(true);
        when(voicePort.transcribe(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(AudioFormat.OGG_OPUS)))
                .thenReturn(CompletableFuture.completedFuture(new VoicePort.TranscriptionResult(
                        "hello world",
                        "en",
                        0.95f,
                        Duration.ofSeconds(1),
                        List.of())));

        StepVerifier.create(controller.transcribe(file(MediaType.valueOf("audio/webm"), "audio")))
                .assertNext(response -> {
                    assertEquals(200, response.getStatusCode().value());
                    assertEquals("hello world", response.getBody().text());
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectEmptyAudio() {
        when(voicePort.isAvailable()).thenReturn(true);

        StepVerifier.create(controller.transcribe(file(MediaType.valueOf("audio/webm"), "")))
                .expectErrorSatisfies(err -> {
                    ResponseStatusException ex = (ResponseStatusException) err;
                    assertEquals(400, ex.getStatusCode().value());
                })
                .verify();
    }

    private static FilePart file(MediaType mediaType, String content) {
        FilePart filePart = mock(FilePart.class);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(mediaType);
        when(filePart.headers()).thenReturn(headers);
        DataBuffer db = new DefaultDataBufferFactory().wrap(content.getBytes(StandardCharsets.UTF_8));
        when(filePart.content()).thenReturn(Flux.just(db));
        return filePart;
    }
}
