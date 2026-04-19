package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.FileContentResponse;
import me.golemcore.bot.adapter.inbound.web.dto.FileCreateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.FileRenameRequest;
import me.golemcore.bot.adapter.inbound.web.dto.FileRenameResponse;
import me.golemcore.bot.adapter.inbound.web.dto.FileSaveRequest;
import me.golemcore.bot.adapter.inbound.web.dto.FileTreeNodeDto;
import me.golemcore.bot.adapter.inbound.web.dto.InlineEditRequest;
import me.golemcore.bot.adapter.inbound.web.dto.InlineEditResponse;
import me.golemcore.bot.application.inlineedit.WebInlineEditService;
import me.golemcore.bot.domain.model.DashboardFileContent;
import me.golemcore.bot.domain.model.DashboardFileNode;
import me.golemcore.bot.domain.model.ToolArtifactDownload;
import me.golemcore.bot.domain.service.DashboardFileService;
import me.golemcore.bot.domain.service.ToolArtifactService;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FilesController {

    private static final String CLIENT_INSTANCE_HEADER = "X-Golem-Client-Instance-Id";

    private final DashboardFileService dashboardFileService;
    private final ToolArtifactService toolArtifactService;
    private final WebInlineEditService webInlineEditService;

    public Mono<ResponseEntity<List<FileTreeNodeDto>>> getTree(String path) {
        try {
            List<DashboardFileNode> tree = dashboardFileService.getTree(path);
            List<FileTreeNodeDto> payload = tree.stream().map(this::toTreeDto).toList();
            return Mono.just(ResponseEntity.ok(payload));
        } catch (IllegalArgumentException _) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
    }

    @GetMapping("/tree")
    public Mono<ResponseEntity<List<FileTreeNodeDto>>> getTree(
            @RequestParam(required = false, defaultValue = "") String path,
            @RequestParam(required = false, defaultValue = "2147483647") int depth,
            @RequestParam(required = false, defaultValue = "true") boolean includeIgnored) {
        try {
            List<DashboardFileNode> tree = dashboardFileService.getTree(path, depth, includeIgnored);
            List<FileTreeNodeDto> payload = tree.stream().map(this::toTreeDto).toList();
            return Mono.just(ResponseEntity.ok(payload));
        } catch (IllegalArgumentException _) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
    }

    @GetMapping("/content")
    public Mono<ResponseEntity<FileContentResponse>> getContent(@RequestParam String path) {
        try {
            DashboardFileContent content = dashboardFileService.getContent(path);
            return Mono.just(ResponseEntity.ok(toContentDto(content)));
        } catch (IllegalArgumentException _) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
    }

    @GetMapping("/download")
    public Mono<ResponseEntity<byte[]>> download(@RequestParam String path) {
        try {
            ToolArtifactDownload download = resolveDownload(path);
            return Mono.just(buildDownloadResponse(download));
        } catch (IllegalArgumentException _) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
    }

    @PostMapping("/upload")
    public Mono<ResponseEntity<FileContentResponse>> upload(
            @RequestParam(required = false, defaultValue = "") String path,
            @RequestPart("file") FilePart file) {
        return collectFilePart(file)
                .map(data -> dashboardFileService.uploadFile(path, file.filename(), data, null))
                .map(content -> ResponseEntity.ok(toContentDto(content)))
                .onErrorReturn(IllegalArgumentException.class, ResponseEntity.badRequest().build());
    }

    @PostMapping("/content")
    public Mono<ResponseEntity<FileContentResponse>> createContent(@RequestBody FileCreateRequest request) {
        try {
            DashboardFileContent created = dashboardFileService.createContent(request.getPath(), request.getContent());
            return Mono.just(ResponseEntity.ok(toContentDto(created)));
        } catch (IllegalArgumentException _) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
    }

    @PutMapping("/content")
    public Mono<ResponseEntity<FileContentResponse>> saveContent(@RequestBody FileSaveRequest request) {
        try {
            DashboardFileContent saved = dashboardFileService.saveContent(request.getPath(), request.getContent());
            return Mono.just(ResponseEntity.ok(toContentDto(saved)));
        } catch (IllegalArgumentException _) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
    }

    @PostMapping("/rename")
    public Mono<ResponseEntity<FileRenameResponse>> renamePath(@RequestBody FileRenameRequest request) {
        try {
            dashboardFileService.renamePath(request.getSourcePath(), request.getTargetPath());
            FileRenameResponse payload = FileRenameResponse.builder()
                    .sourcePath(request.getSourcePath())
                    .targetPath(request.getTargetPath())
                    .build();
            return Mono.just(ResponseEntity.ok(payload));
        } catch (IllegalArgumentException _) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
    }

    @PostMapping("/inline-edit")
    public Mono<ResponseEntity<InlineEditResponse>> inlineEdit(
            @RequestBody InlineEditRequest request,
            @RequestHeader(name = CLIENT_INSTANCE_HEADER, required = false) String clientInstanceId) {
        try {
            WebInlineEditService.InlineEditResult result = webInlineEditService.createInlineEdit(
                    request.getPath(),
                    request.getContent(),
                    request.getSelectionFrom() != null ? request.getSelectionFrom() : -1,
                    request.getSelectionTo() != null ? request.getSelectionTo() : -1,
                    request.getSelectedText(),
                    request.getInstruction(),
                    clientInstanceId);
            InlineEditResponse payload = InlineEditResponse.builder()
                    .path(result.path())
                    .replacement(result.replacement())
                    .build();
            return Mono.just(ResponseEntity.ok(payload));
        } catch (IllegalArgumentException _) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
    }

    @DeleteMapping
    public Mono<ResponseEntity<Void>> deletePath(@RequestParam String path) {
        try {
            dashboardFileService.deletePath(path);
            return Mono.just(ResponseEntity.noContent().build());
        } catch (IllegalArgumentException _) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
    }

    private ToolArtifactDownload resolveDownload(String path) {
        if (path != null && path.startsWith(".golemcore/tool-artifacts/")) {
            return toolArtifactService.getDownload(path);
        }
        return dashboardFileService.getDownload(path);
    }

    private ResponseEntity<byte[]> buildDownloadResponse(ToolArtifactDownload download) {
        MediaType mediaType = parseMediaType(download.getMimeType());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.getFilename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(download.getSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(download.getData());
    }

    private Mono<byte[]> collectFilePart(FilePart file) {
        return file.content().collectList().map(buffers -> {
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                for (DataBuffer buffer : buffers) {
                    byte[] chunk = new byte[buffer.readableByteCount()];
                    buffer.read(chunk);
                    output.write(chunk);
                    DataBufferUtils.release(buffer);
                }
                return output.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read upload", e);
            }
        });
    }

    private FileTreeNodeDto toTreeDto(DashboardFileNode node) {
        return FileTreeNodeDto.builder()
                .path(node.getPath())
                .name(node.getName())
                .type(node.getType())
                .size(node.getSize())
                .mimeType(node.getMimeType())
                .updatedAt(node.getUpdatedAt())
                .binary(node.isBinary())
                .image(node.isImage())
                .editable(node.isEditable())
                .hasChildren(node.isHasChildren())
                .children(node.getChildren().stream().map(this::toTreeDto).toList())
                .build();
    }

    private FileContentResponse toContentDto(DashboardFileContent content) {
        return FileContentResponse.builder()
                .path(content.getPath())
                .content(content.getContent())
                .size(content.getSize())
                .updatedAt(content.getUpdatedAt())
                .mimeType(content.getMimeType())
                .binary(content.isBinary())
                .image(content.isImage())
                .editable(content.isEditable())
                .downloadUrl(content.getDownloadUrl())
                .build();
    }

    private MediaType parseMediaType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
