package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.SkillDto;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillInstallRequest;
import me.golemcore.bot.domain.model.SkillInstallResult;
import me.golemcore.bot.domain.model.SkillMarketplaceCatalog;
import me.golemcore.bot.domain.service.SkillMarketplaceService;
import me.golemcore.bot.domain.service.SkillService;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Skills management endpoints.
 */
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillsController {

    private static final String SKILLS_DIR = "skills";

    private final SkillService skillService;
    private final SkillMarketplaceService skillMarketplaceService;
    private final McpPort mcpPort;
    private final StoragePort storagePort;

    @GetMapping
    public Mono<ResponseEntity<List<SkillDto>>> listSkills() {
        List<SkillDto> skills = skillService.getAllSkills().stream()
                .map(this::toDto)
                .toList();
        return Mono.just(ResponseEntity.ok(skills));
    }

    @GetMapping("/marketplace")
    public Mono<ResponseEntity<SkillMarketplaceCatalog>> getMarketplace() {
        return Mono.just(ResponseEntity.ok(skillMarketplaceService.getCatalog()));
    }

    @PostMapping("/marketplace/install")
    public Mono<ResponseEntity<SkillInstallResult>> installSkill(@RequestBody SkillInstallRequest request) {
        return Mono.just(ResponseEntity.ok(skillMarketplaceService.install(request.getSkillId())));
    }

    @GetMapping("/detail")
    public Mono<ResponseEntity<SkillDto>> getSkillByQuery(@RequestParam String name) {
        return getSkillResponse(name);
    }

    @GetMapping("/{name}")
    public Mono<ResponseEntity<SkillDto>> getSkill(@PathVariable String name) {
        return getSkillResponse(name);
    }

    private Mono<ResponseEntity<SkillDto>> getSkillResponse(String name) {
        Optional<Skill> skill = skillService.findByName(name);
        if (skill.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill '" + name + "' not found");
        }
        return Mono.just(ResponseEntity.ok(toDetailDto(skill.get())));
    }

    @PostMapping
    public Mono<ResponseEntity<SkillDto>> createSkill(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String content = body.get("content");
        if (name == null || name.isBlank() || !isValidMetadataName(name.trim()) || name.contains("/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Skill name is required and must match [a-z0-9][a-z0-9-]*");
        }
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill content is required");
        }
        if (skillService.findByName(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill '" + name + "' already exists");
        }
        String path = name + "/SKILL.md";
        return Mono.fromFuture(storagePort.putText(SKILLS_DIR, path, content))
                .then(Mono.fromRunnable(skillService::reload))
                .then(Mono.defer(() -> {
                    Optional<Skill> created = skillService.findByName(name);
                    return created
                            .map(s -> Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(toDetailDto(s))))
                            .orElse(Mono.just(ResponseEntity.notFound().build()));
                }));
    }

    @PutMapping("/detail")
    public Mono<ResponseEntity<SkillDto>> updateSkillByQuery(
            @RequestParam String name, @RequestBody Map<String, Object> body) {
        return updateSkillInternal(name, body);
    }

    @PutMapping("/{name}")
    public Mono<ResponseEntity<SkillDto>> updateSkill(
            @PathVariable String name, @RequestBody Map<String, Object> body) {
        return updateSkillInternal(name, body);
    }

    private Mono<ResponseEntity<SkillDto>> updateSkillInternal(String name, Map<String, Object> body) {
        String content = extractContent(body);
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill content is required");
        }
        Skill skill = skillService.findByName(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill '" + name + "' not found"));
        Map<String, Object> metadata = extractMetadata(body)
                .orElseGet(() -> copyMetadata(skill.getMetadata()));
        validateMetadata(metadata);
        String path = skillMarketplaceService.resolveManagedSkillStoragePath(skill);
        String document = skillService.renderSkillDocument(metadata, content);
        return Mono.fromFuture(storagePort.putText(SKILLS_DIR, path, document))
                .then(Mono.fromRunnable(skillService::reload))
                .then(Mono.defer(() -> {
                    Optional<Skill> updated = skillService.findByLocation(path);
                    return updated
                            .map(s -> Mono.just(ResponseEntity.ok(toDetailDto(s))))
                            .orElse(Mono.just(ResponseEntity.notFound().build()));
                }));
    }

    @PostMapping("/{name}/reload")
    public Mono<ResponseEntity<Map<String, String>>> reloadSkill(@PathVariable String name) {
        return reloadSkillInternal(name);
    }

    @PostMapping("/detail/reload")
    public Mono<ResponseEntity<Map<String, String>>> reloadSkillByQuery(@RequestParam String name) {
        return reloadSkillInternal(name);
    }

    private Mono<ResponseEntity<Map<String, String>>> reloadSkillInternal(String name) {
        skillService.findByName(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill '" + name + "' not found"));
        skillService.reload();
        return Mono.just(ResponseEntity.ok(Map.of("status", "reloaded")));
    }

    @DeleteMapping("/detail")
    public Mono<ResponseEntity<Void>> deleteSkillByQuery(@RequestParam String name) {
        return deleteSkillInternal(name);
    }

    @DeleteMapping("/{name}")
    public Mono<ResponseEntity<Void>> deleteSkill(@PathVariable String name) {
        return deleteSkillInternal(name);
    }

    private Mono<ResponseEntity<Void>> deleteSkillInternal(String name) {
        Skill skill = skillService.findByName(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill '" + name + "' not found"));
        return Mono.fromRunnable(() -> skillMarketplaceService.deleteManagedSkill(skill))
                .then(Mono.fromRunnable(skillService::reload))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @GetMapping("/detail/mcp-status")
    public Mono<ResponseEntity<Map<String, Object>>> getMcpStatusByQuery(@RequestParam String name) {
        return getMcpStatusInternal(name);
    }

    @GetMapping("/{name}/mcp-status")
    public Mono<ResponseEntity<Map<String, Object>>> getMcpStatus(@PathVariable String name) {
        return getMcpStatusInternal(name);
    }

    private Mono<ResponseEntity<Map<String, Object>>> getMcpStatusInternal(String name) {
        Optional<Skill> skill = skillService.findByName(name);
        if (skill.isEmpty() || !skill.get().hasMcp()) {
            return Mono.just(ResponseEntity.ok(Map.of("hasMcp", false)));
        }
        List<String> tools = mcpPort.getToolNames(name);
        boolean running = !tools.isEmpty();
        return Mono.just(ResponseEntity.ok(Map.of(
                "hasMcp", true,
                "running", running,
                "tools", tools)));
    }

    private SkillDto toDto(Skill skill) {
        return SkillDto.builder()
                .name(skill.getName())
                .description(skill.getDescription())
                .available(skill.isAvailable())
                .hasMcp(skill.hasMcp())
                .modelTier(skill.getModelTier())
                .build();
    }

    private SkillDto toDetailDto(Skill skill) {
        SkillDto dto = toDto(skill);
        dto.setContent(skill.getContent());
        dto.setMetadata(copyMetadata(skill.getMetadata()));
        dto.setRequirements(toRequirementsMap(skill));
        dto.setResolvedVariables(skill.getResolvedVariables());
        return dto;
    }

    private String extractContent(Map<String, Object> body) {
        Object content = body.get("content");
        if (content == null) {
            return null;
        }
        if (!(content instanceof String stringContent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill content must be a string");
        }
        return stringContent;
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> extractMetadata(Map<String, Object> body) {
        if (!body.containsKey("metadata")) {
            return Optional.empty();
        }
        Object metadata = body.get("metadata");
        if (metadata == null) {
            return Optional.of(new LinkedHashMap<>());
        }
        if (!(metadata instanceof Map<?, ?> rawMetadata)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill metadata must be an object");
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMetadata.entrySet()) {
            copied.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Optional.of(copied);
    }

    private void validateMetadata(Map<String, Object> metadata) {
        Object name = metadata.get("name");
        if (name == null) {
            return;
        }
        String normalizedName = name.toString().trim();
        if (!isValidMetadataName(normalizedName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Skill metadata name must match [a-z0-9][a-z0-9-]*(/[a-z0-9][a-z0-9-]*)*");
        }
        metadata.put("name", normalizedName);
    }

    private boolean isValidMetadataName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        int segmentStart = 0;
        for (int index = 0; index <= value.length(); index++) {
            boolean atSeparator = index < value.length() && value.charAt(index) == '/';
            boolean atEnd = index == value.length();
            if (!atSeparator && !atEnd) {
                continue;
            }
            if (!isValidMetadataSegment(value, segmentStart, index)) {
                return false;
            }
            segmentStart = index + 1;
        }
        return true;
    }

    private boolean isValidMetadataSegment(String value, int startInclusive, int endExclusive) {
        if (startInclusive >= endExclusive) {
            return false;
        }
        char first = value.charAt(startInclusive);
        if (!isLowercaseLetterOrDigit(first)) {
            return false;
        }
        for (int index = startInclusive + 1; index < endExclusive; index++) {
            char current = value.charAt(index);
            if (!isLowercaseLetterOrDigit(current) && current != '-') {
                return false;
            }
        }
        return true;
    }

    private boolean isLowercaseLetterOrDigit(char value) {
        return (value >= 'a' && value <= 'z') || (value >= '0' && value <= '9');
    }

    private Map<String, Object> copyMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(metadata);
    }

    private Map<String, Object> toRequirementsMap(Skill skill) {
        if (skill.getRequirements() == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> requirements = new LinkedHashMap<>();
        if (skill.getRequirements().getEnvVars() != null && !skill.getRequirements().getEnvVars().isEmpty()) {
            requirements.put("env", skill.getRequirements().getEnvVars());
        }
        if (skill.getRequirements().getBinaries() != null && !skill.getRequirements().getBinaries().isEmpty()) {
            requirements.put("binary", skill.getRequirements().getBinaries());
        }
        if (skill.getRequirements().getSkills() != null && !skill.getRequirements().getSkills().isEmpty()) {
            requirements.put("skills", skill.getRequirements().getSkills());
        }
        return requirements;
    }
}
