package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.client.dto.PromptCreateRequest;
import me.golemcore.bot.client.dto.PromptSectionDto;
import me.golemcore.bot.application.prompts.PromptManagementFacade;
import me.golemcore.bot.application.prompts.PromptSectionDraft;
import me.golemcore.bot.domain.model.PromptSection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Prompt section management endpoints.
 */
@RestController
@RequestMapping("/api/prompts")
@Slf4j
public class PromptsController {

    private final PromptManagementFacade promptManagementFacade;

    public PromptsController(PromptManagementFacade promptManagementFacade) {
        this.promptManagementFacade = promptManagementFacade;
    }

    @GetMapping
    public Mono<ResponseEntity<List<PromptSectionDto>>> listSections() {
        List<PromptSectionDto> sections = promptManagementFacade.listSections().stream()
                .map(this::toDto)
                .toList();
        return Mono.just(ResponseEntity.ok(sections));
    }

    @GetMapping("/{name}")
    public Mono<ResponseEntity<PromptSectionDto>> getSection(@PathVariable String name) {
        return Mono.just(ResponseEntity.ok(toDto(resolveSection(name))));
    }

    @PostMapping
    public Mono<ResponseEntity<PromptSectionDto>> createSection(@RequestBody PromptCreateRequest request) {
        return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(toDto(createSectionOrThrow(request))));
    }

    @PutMapping("/{name}")
    public Mono<ResponseEntity<PromptSectionDto>> updateSection(
            @PathVariable String name, @RequestBody PromptCreateRequest request) {
        return Mono.just(ResponseEntity.ok(toDto(updateSectionOrThrow(name, request))));
    }

    @PostMapping("/{name}/preview")
    public Mono<ResponseEntity<Map<String, String>>> previewSection(
            @PathVariable String name,
            @RequestBody(required = false) PromptCreateRequest request) {
        return Mono.just(ResponseEntity.ok(Map.of("rendered", previewSectionOrThrow(name, request))));
    }

    @DeleteMapping("/{name}")
    public Mono<ResponseEntity<Void>> deleteSection(@PathVariable String name) {
        deleteSectionOrThrow(name);
        return Mono.just(ResponseEntity.noContent().<Void>build());
    }

    @PostMapping("/reload")
    public Mono<ResponseEntity<Map<String, String>>> reload() {
        promptManagementFacade.reload();
        return Mono.just(ResponseEntity.ok(Map.of("status", "reloaded")));
    }

    private PromptSectionDto toDto(PromptSection section) {
        return PromptSectionDto.builder()
                .name(section.getName())
                .description(section.getDescription())
                .order(section.getOrder())
                .enabled(section.isEnabled())
                .deletable(!promptManagementFacade.isProtectedSection(section.getName()))
                .content(section.getContent())
                .build();
    }

    private PromptSection resolveSection(String name) {
        try {
            return promptManagementFacade.getSection(name);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    private PromptSection createSectionOrThrow(PromptCreateRequest request) {
        try {
            return promptManagementFacade.createSection(toDraft(request));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    private PromptSection updateSectionOrThrow(String name, PromptCreateRequest request) {
        try {
            return promptManagementFacade.updateSection(name, toDraft(request));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    private String previewSectionOrThrow(String name, PromptCreateRequest request) {
        try {
            return promptManagementFacade.previewSection(name, toDraft(request));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    private PromptSectionDraft toDraft(PromptCreateRequest request) {
        if (request == null) {
            return null;
        }
        return new PromptSectionDraft(
                request.getName(),
                request.getDescription(),
                request.getOrder(),
                request.isEnabled(),
                request.getContent());
    }

    private void deleteSectionOrThrow(String name) {
        try {
            promptManagementFacade.deleteSection(name);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }
}
