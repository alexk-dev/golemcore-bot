package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.web.dto.PromptCreateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.PromptSectionDto;
import me.golemcore.bot.domain.model.PromptSection;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.StoragePort;
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

import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Prompt section management endpoints.
 */
@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
@Slf4j
public class PromptsController {

    private static final String PROMPTS_DIR = "prompts";
    private static final Pattern VALID_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private final PromptSectionService promptSectionService;
    private final UserPreferencesService preferencesService;
    private final StoragePort storagePort;

    @GetMapping
    public Mono<ResponseEntity<List<PromptSectionDto>>> listSections() {
        List<PromptSectionDto> sections = promptSectionService.getAllSections().stream()
                .map(this::toDto)
                .toList();
        return Mono.just(ResponseEntity.ok(sections));
    }

    @GetMapping("/{name}")
    public Mono<ResponseEntity<PromptSectionDto>> getSection(@PathVariable String name) {
        Optional<PromptSection> section = promptSectionService.getSection(name);
        if (section.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Prompt section '" + name + "' not found");
        }
        return Mono.just(ResponseEntity.ok(toDto(section.get())));
    }

    @PostMapping
    public Mono<ResponseEntity<PromptSectionDto>> createSection(@RequestBody PromptCreateRequest request) {
        String normalizedName = requireValidName(request.getName());
        if (promptSectionService.getSection(normalizedName).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Prompt section '" + normalizedName + "' already exists");
        }
        String filename = filenameFor(normalizedName);
        String fileContent = buildFileContent(request);
        return Mono.fromFuture(storagePort.putText(PROMPTS_DIR, filename, fileContent))
                .then(Mono.fromRunnable(promptSectionService::reload))
                .then(Mono.defer(() -> {
                    Optional<PromptSection> created = promptSectionService.getSection(normalizedName);
                    if (created.isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "Prompt section '" + normalizedName + "' not found after create");
                    }
                    return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(toDto(created.get())));
                }));
    }

    @PutMapping("/{name}")
    public Mono<ResponseEntity<PromptSectionDto>> updateSection(
            @PathVariable String name, @RequestBody PromptCreateRequest request) {
        String normalizedName = requireValidName(name);
        if (promptSectionService.getSection(normalizedName).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Prompt section '" + normalizedName + "' not found");
        }
        String filename = filenameFor(normalizedName);
        String fileContent = buildFileContent(request);
        return Mono.fromFuture(storagePort.putText(PROMPTS_DIR, filename, fileContent))
                .then(Mono.fromRunnable(promptSectionService::reload))
                .then(Mono.defer(() -> {
                    Optional<PromptSection> updated = promptSectionService.getSection(normalizedName);
                    if (updated.isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "Prompt section '" + normalizedName + "' not found after update");
                    }
                    return Mono.just(ResponseEntity.ok(toDto(updated.get())));
                }));
    }

    @PostMapping("/{name}/preview")
    public Mono<ResponseEntity<Map<String, String>>> previewSection(
            @PathVariable String name,
            @RequestBody(required = false) PromptCreateRequest request) {
        String normalizedName = requireValidName(name);
        Optional<PromptSection> section = promptSectionService.getSection(normalizedName);
        if (section.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Prompt section '" + normalizedName + "' not found");
        }
        Map<String, String> vars = promptSectionService.buildTemplateVariables(
                preferencesService.getPreferences());
        String rendered = promptSectionService.renderSection(resolvePreviewSection(section.get(), request), vars);
        return Mono.just(ResponseEntity.ok(Map.of("rendered", rendered)));
    }

    @DeleteMapping("/{name}")
    public Mono<ResponseEntity<Void>> deleteSection(@PathVariable String name) {
        String normalizedName = requireValidName(name);
        if (promptSectionService.getSection(normalizedName).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Prompt section '" + normalizedName + "' not found");
        }
        if (promptSectionService.isProtectedSection(normalizedName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Prompt section '" + normalizedName + "' cannot be deleted");
        }
        return Mono.fromFuture(storagePort.deleteObject(PROMPTS_DIR, filenameFor(normalizedName)))
                .then(Mono.fromRunnable(promptSectionService::reload))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @PostMapping("/reload")
    public Mono<ResponseEntity<Map<String, String>>> reload() {
        promptSectionService.reload();
        return Mono.just(ResponseEntity.ok(Map.of("status", "reloaded")));
    }

    private PromptSectionDto toDto(PromptSection section) {
        return PromptSectionDto.builder()
                .name(section.getName())
                .description(section.getDescription())
                .order(section.getOrder())
                .enabled(section.isEnabled())
                .deletable(!promptSectionService.isProtectedSection(section.getName()))
                .content(section.getContent())
                .build();
    }

    private PromptSection resolvePreviewSection(PromptSection section, PromptCreateRequest request) {
        if (request == null || request.getContent() == null) {
            return section;
        }
        return PromptSection.builder()
                .name(section.getName())
                .description(request.getDescription())
                .order(request.getOrder())
                .enabled(request.isEnabled())
                .content(request.getContent())
                .build();
    }

    private String buildFileContent(PromptCreateRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        if (request.getDescription() != null) {
            sb.append("description: ").append(request.getDescription()).append("\n");
        }
        sb.append("order: ").append(request.getOrder()).append("\n");
        sb.append("enabled: ").append(request.isEnabled()).append("\n");
        sb.append("---\n");
        sb.append(request.getContent() != null ? request.getContent() : "");
        return sb.toString();
    }

    private String requireValidName(String name) {
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Prompt name is required and must match [a-z0-9][a-z0-9-]*");
        }
        String normalizedName = name.trim().toLowerCase(Locale.ROOT);
        if (!VALID_NAME.matcher(normalizedName).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Prompt name is required and must match [a-z0-9][a-z0-9-]*");
        }
        return normalizedName;
    }

    private String filenameFor(String normalizedName) {
        return normalizedName.toUpperCase(Locale.ROOT) + ".md";
    }
}
