package me.golemcore.bot.adapter.inbound.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.port.outbound.StoragePort;
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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UploadsControllerTest {

    private StoragePort storage;
    private UploadsController controller;

    @BeforeEach
    void setUp() {
        storage = mock(StoragePort.class);
        controller = new UploadsController(storage, new ObjectMapper());

        when(storage.putObject(anyString(), anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storage.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void shouldRejectMoreThanFiveFiles() {
        Flux<FilePart> files = Flux.just(
                file("1.png", MediaType.IMAGE_PNG, "a"),
                file("2.png", MediaType.IMAGE_PNG, "a"),
                file("3.png", MediaType.IMAGE_PNG, "a"),
                file("4.png", MediaType.IMAGE_PNG, "a"),
                file("5.png", MediaType.IMAGE_PNG, "a"),
                file("6.png", MediaType.IMAGE_PNG, "a"));

        StepVerifier.create(controller.uploadImages(files))
                .expectErrorSatisfies(err -> {
                    assertNotNull(err);
                    var ex = (ResponseStatusException) err;
                    assertEquals(400, ex.getStatusCode().value());
                })
                .verify();
    }

    @Test
    void shouldRejectUnsupportedMimeType() {
        StepVerifier.create(controller.uploadImages(Flux.just(file("a.txt", MediaType.TEXT_PLAIN, "hello"))))
                .expectErrorSatisfies(err -> {
                    var ex = (ResponseStatusException) err;
                    assertEquals(400, ex.getStatusCode().value());
                })
                .verify();
    }

    @Test
    void shouldUploadValidImage() {
        StepVerifier.create(controller.uploadImages(Flux.just(file("img.png", MediaType.IMAGE_PNG, "png-bytes"))))
                .assertNext(response -> {
                    assertEquals(200, response.getStatusCode().value());
                    assertNotNull(response.getBody());
                    assertEquals(1, response.getBody().size());
                    assertEquals("image/png", response.getBody().get(0).mimeType());
                })
                .verifyComplete();
    }

    private static FilePart file(String filename, MediaType mediaType, String content) {
        FilePart filePart = mock(FilePart.class);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(mediaType);
        when(filePart.filename()).thenReturn(filename);
        when(filePart.headers()).thenReturn(headers);
        DataBuffer db = new DefaultDataBufferFactory().wrap(content.getBytes(StandardCharsets.UTF_8));
        when(filePart.content()).thenReturn(Flux.just(db));
        return filePart;
    }
}
