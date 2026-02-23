package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.FileContentResponse;
import me.golemcore.bot.adapter.inbound.web.dto.FileCreateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.FileRenameRequest;
import me.golemcore.bot.adapter.inbound.web.dto.FileRenameResponse;
import me.golemcore.bot.adapter.inbound.web.dto.FileSaveRequest;
import me.golemcore.bot.adapter.inbound.web.dto.FileTreeNodeDto;
import me.golemcore.bot.domain.model.DashboardFileContent;
import me.golemcore.bot.domain.model.DashboardFileNode;
import me.golemcore.bot.domain.service.DashboardFileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FilesController {

    private final DashboardFileService dashboardFileService;

    @GetMapping("/tree")
    public Mono<ResponseEntity<List<FileTreeNodeDto>>> getTree(
            @RequestParam(required = false, defaultValue = "") String path) {
        try {
            List<DashboardFileNode> tree = dashboardFileService.getTree(path);
            List<FileTreeNodeDto> payload = tree.stream().map(this::toTreeDto).toList();
            return Mono.just(ResponseEntity.ok(payload));
        } catch (IllegalArgumentException e) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
    }

    @GetMapping("/content")
    public Mono<ResponseEntity<FileContentResponse>> getContent(@RequestParam String path) {
        try {
            DashboardFileContent content = dashboardFileService.getContent(path);
            return Mono.just(ResponseEntity.ok(toContentDto(content)));
        } catch (IllegalArgumentException e) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
    }

    @PostMapping("/content")
    public Mono<ResponseEntity<FileContentResponse>> createContent(@RequestBody FileCreateRequest request) {
        try {
            DashboardFileContent created = dashboardFileService.createContent(request.getPath(), request.getContent());
            return Mono.just(ResponseEntity.ok(toContentDto(created)));
        } catch (IllegalArgumentException e) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
    }

    @PutMapping("/content")
    public Mono<ResponseEntity<FileContentResponse>> saveContent(@RequestBody FileSaveRequest request) {
        try {
            DashboardFileContent saved = dashboardFileService.saveContent(request.getPath(), request.getContent());
            return Mono.just(ResponseEntity.ok(toContentDto(saved)));
        } catch (IllegalArgumentException e) {
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
        } catch (IllegalArgumentException e) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
    }

    @DeleteMapping
    public Mono<ResponseEntity<Void>> deletePath(@RequestParam String path) {
        try {
            dashboardFileService.deletePath(path);
            return Mono.just(ResponseEntity.noContent().build());
        } catch (IllegalArgumentException e) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
    }

    private FileTreeNodeDto toTreeDto(DashboardFileNode node) {
        return FileTreeNodeDto.builder()
                .path(node.getPath())
                .name(node.getName())
                .type(node.getType())
                .size(node.getSize())
                .children(node.getChildren().stream().map(this::toTreeDto).toList())
                .build();
    }

    private FileContentResponse toContentDto(DashboardFileContent content) {
        return FileContentResponse.builder()
                .path(content.getPath())
                .content(content.getContent())
                .size(content.getSize())
                .updatedAt(content.getUpdatedAt())
                .build();
    }
}
