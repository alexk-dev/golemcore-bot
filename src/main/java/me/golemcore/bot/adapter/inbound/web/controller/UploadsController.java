package me.golemcore.bot.adapter.inbound.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/uploads")
@RequiredArgsConstructor
@Slf4j
public class UploadsController {

    private static final String DIR = "uploads";
    private static final long MAX_IMAGE_BYTES = 10L * 1024L * 1024L;

    private final StoragePort storage;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<List<ImageUploadResponse>>> uploadImages(@RequestPart("files") Flux<FilePart> files) {
        return files.concatMap(this::storeImage)
                .collectList()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/images/{id}")
    public Mono<ResponseEntity<ByteArrayResource>> getImage(@PathVariable String id) {
        String metaPath = id + ".json";
        String binPath = id + ".bin";

        Mono<String> metaMono = Mono.fromFuture(storage.getText(DIR, metaPath))
                .flatMap(meta -> meta == null
                        ? Mono.error(new ResponseStatusException(NOT_FOUND, "metadata not found"))
                        : Mono.just(meta));

        Mono<byte[]> bytesMono = Mono.fromFuture(storage.getObject(DIR, binPath))
                .flatMap(bytes -> bytes == null
                        ? Mono.error(new ResponseStatusException(NOT_FOUND, "file not found"))
                        : Mono.just(bytes));

        return Mono.zip(metaMono, bytesMono)
                .map(tuple -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> meta = objectMapper.readValue(tuple.getT1(), Map.class);
                        String mime = String.valueOf(
                                meta.getOrDefault("mimeType", MediaType.APPLICATION_OCTET_STREAM_VALUE));
                        MediaType mt = MediaType.parseMediaType(mime);
                        return ResponseEntity.ok()
                                .contentType(mt)
                                .body(new ByteArrayResource(tuple.getT2()));
                    } catch (Exception e) { // NOSONAR
                        throw new ResponseStatusException(NOT_FOUND, "invalid metadata");
                    }
                });
    }

    private Mono<ImageUploadResponse> storeImage(FilePart filePart) {
        String mimeType = filePart.headers().getContentType() != null
                ? filePart.headers().getContentType().toString()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;

        if (!isAllowedImageMime(mimeType)) {
            return Mono.error(new ResponseStatusException(BAD_REQUEST, "Unsupported image type: " + mimeType));
        }

        return filePart.content()
                .reduce(new ByteCollector(), (acc, dataBuffer) -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    acc.append(bytes);
                    return acc;
                })
                .flatMap(acc -> {
                    byte[] content = acc.toByteArray();
                    if (content.length == 0) {
                        return Mono.error(new ResponseStatusException(BAD_REQUEST, "Empty file"));
                    }
                    if (content.length > MAX_IMAGE_BYTES) {
                        return Mono.error(new ResponseStatusException(BAD_REQUEST, "Image too large"));
                    }

                    String id = UUID.randomUUID().toString();
                    String binPath = id + ".bin";
                    String metaPath = id + ".json";

                    Map<String, Object> meta = Map.of(
                            "id", id,
                            "filename", filePart.filename(),
                            "mimeType", mimeType,
                            "size", content.length,
                            "uploadedAt", Instant.now().toString());

                    String url = "/api/uploads/images/" + id;

                    return Mono.fromFuture(storage.putObject(DIR, binPath, content))
                            .then(Mono.fromCallable(() -> objectMapper.writeValueAsString(meta)))
                            .flatMap(metaJson -> Mono.fromFuture(storage.putText(DIR, metaPath, metaJson)))
                            .thenReturn(new ImageUploadResponse(id, url, mimeType, content.length, null, null));
                });
    }

    private boolean isAllowedImageMime(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        return mimeType.equalsIgnoreCase(MediaType.IMAGE_PNG_VALUE)
                || mimeType.equalsIgnoreCase(MediaType.IMAGE_JPEG_VALUE)
                || mimeType.equalsIgnoreCase(MediaType.IMAGE_GIF_VALUE)
                || mimeType.equalsIgnoreCase("image/webp");
    }

    private static final class ByteCollector {
        private final List<byte[]> chunks = new ArrayList<>();
        private int total = 0;

        void append(byte[] chunk) {
            chunks.add(chunk);
            total += chunk.length;
        }

        byte[] toByteArray() {
            byte[] out = new byte[total];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, out, offset, chunk.length);
                offset += chunk.length;
            }
            return out;
        }
    }

    public record ImageUploadResponse(
            String id,
            String url,
            String mimeType,
            int size,
            Integer width,
            Integer height) {
    }
}
