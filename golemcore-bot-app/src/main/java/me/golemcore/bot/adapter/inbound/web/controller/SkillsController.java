package me.golemcore.bot.adapter.inbound.web.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.NoSuchElementException;
import me.golemcore.bot.client.dto.SkillDto;
import me.golemcore.bot.application.skills.SkillManagementFacade;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillInstallRequest;
import me.golemcore.bot.domain.model.SkillInstallResult;
import me.golemcore.bot.domain.model.SkillMarketplaceCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/skills")
public class SkillsController {

    private final SkillManagementFacade skillManagementFacade;

    public SkillsController(SkillManagementFacade skillManagementFacade) {
        this.skillManagementFacade = skillManagementFacade;
    }

    @GetMapping
    public Mono<ResponseEntity<List<SkillDto>>> listSkills() {
        List<SkillDto> skills = skillManagementFacade.listSkills().stream()
                .map(this::toDto)
                .toList();
        return Mono.just(ResponseEntity.ok(skills));
    }

    @GetMapping("/marketplace")
    public Mono<ResponseEntity<SkillMarketplaceCatalog>> getMarketplace() {
        return Mono.just(ResponseEntity.ok(skillManagementFacade.getMarketplace()));
    }

    @PostMapping("/marketplace/install")
    public Mono<ResponseEntity<SkillInstallResult>> installSkill(@RequestBody SkillInstallRequest request) {
        return Mono.just(ResponseEntity.ok(skillManagementFacade.installSkill(request)));
    }

    @GetMapping("/detail")
    public Mono<ResponseEntity<SkillDto>> getSkillByQuery(@RequestParam String name) {
        return getSkillResponse(name);
    }

    @GetMapping("/{name}")
    public Mono<ResponseEntity<SkillDto>> getSkill(@PathVariable String name) {
        return getSkillResponse(name);
    }

    @PostMapping
    public Mono<ResponseEntity<SkillDto>> createSkill(@RequestBody Map<String, String> body) {
        java.util.concurrent.CompletableFuture<Skill> createFuture;
        try {
            createFuture = skillManagementFacade.createSkill(body.get("name"), body.get("content"));
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        return Mono.fromFuture(createFuture)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(toDetailDto(created)))
                .onErrorMap(this::translateSkillMutationError);
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

    @PostMapping("/{name}/reload")
    public Mono<ResponseEntity<Map<String, String>>> reloadSkill(@PathVariable String name) {
        return reloadSkillInternal(name);
    }

    @PostMapping("/detail/reload")
    public Mono<ResponseEntity<Map<String, String>>> reloadSkillByQuery(@RequestParam String name) {
        return reloadSkillInternal(name);
    }

    @DeleteMapping("/detail")
    public Mono<ResponseEntity<Void>> deleteSkillByQuery(@RequestParam String name) {
        return deleteSkillInternal(name);
    }

    @DeleteMapping("/{name}")
    public Mono<ResponseEntity<Void>> deleteSkill(@PathVariable String name) {
        return deleteSkillInternal(name);
    }

    @GetMapping("/detail/mcp-status")
    public Mono<ResponseEntity<Map<String, Object>>> getMcpStatusByQuery(@RequestParam String name) {
        return getMcpStatusInternal(name);
    }

    @GetMapping("/{name}/mcp-status")
    public Mono<ResponseEntity<Map<String, Object>>> getMcpStatus(@PathVariable String name) {
        return getMcpStatusInternal(name);
    }

    private Mono<ResponseEntity<SkillDto>> getSkillResponse(String name) {
        try {
            return Mono.just(ResponseEntity.ok(toDetailDto(skillManagementFacade.getSkill(name))));
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    private Mono<ResponseEntity<SkillDto>> updateSkillInternal(String name, Map<String, Object> body) {
        java.util.concurrent.CompletableFuture<Skill> updateFuture;
        try {
            updateFuture = skillManagementFacade.updateSkill(name, body);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        return Mono.fromFuture(updateFuture)
                .map(updated -> ResponseEntity.ok(toDetailDto(updated)))
                .onErrorMap(this::translateSkillMutationError);
    }

    private Mono<ResponseEntity<Map<String, String>>> reloadSkillInternal(String name) {
        try {
            skillManagementFacade.reloadSkill(name);
            return Mono.just(ResponseEntity.ok(Map.of("status", "reloaded")));
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    private Mono<ResponseEntity<Void>> deleteSkillInternal(String name) {
        try {
            skillManagementFacade.deleteSkill(name);
            return Mono.just(ResponseEntity.noContent().<Void>build());
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    private Mono<ResponseEntity<Map<String, Object>>> getMcpStatusInternal(String name) {
        SkillManagementFacade.McpStatusView status = skillManagementFacade.getMcpStatus(name);
        if (!status.hasMcp()) {
            return Mono.just(ResponseEntity.ok(Map.of("hasMcp", false)));
        }
        return Mono.just(ResponseEntity.ok(Map.of(
                "hasMcp", true,
                "running", status.running(),
                "tools", status.tools())));
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

    private Throwable translateSkillMutationError(Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        if (cause instanceof NoSuchElementException exception) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
        if (cause instanceof IllegalArgumentException exception) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        if (cause instanceof IllegalStateException exception) {
            return new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
        return throwable;
    }
}
