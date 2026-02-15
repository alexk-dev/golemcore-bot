package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.SkillDto;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.service.SkillService;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
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
    private final McpPort mcpPort;
    private final StoragePort storagePort;

    @GetMapping
    public Mono<ResponseEntity<List<SkillDto>>> listSkills() {
        List<SkillDto> skills = skillService.getAllSkills().stream()
                .map(this::toDto)
                .toList();
        return Mono.just(ResponseEntity.ok(skills));
    }

    @GetMapping("/{name}")
    public Mono<ResponseEntity<SkillDto>> getSkill(@PathVariable String name) {
        Optional<Skill> skill = skillService.findByName(name);
        return skill
                .map(s -> Mono.just(ResponseEntity.ok(toDetailDto(s))))
                .orElse(Mono.just(ResponseEntity.notFound().build()));
    }

    @PutMapping("/{name}")
    public Mono<ResponseEntity<SkillDto>> updateSkill(
            @PathVariable String name, @RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        String path = name + "/SKILL.md";
        return Mono.fromFuture(storagePort.putText(SKILLS_DIR, path, content))
                .then(Mono.fromRunnable(skillService::reload))
                .then(Mono.defer(() -> {
                    Optional<Skill> updated = skillService.findByName(name);
                    return updated
                            .map(s -> Mono.just(ResponseEntity.ok(toDetailDto(s))))
                            .orElse(Mono.just(ResponseEntity.notFound().build()));
                }));
    }

    @PostMapping("/{name}/reload")
    public Mono<ResponseEntity<Map<String, String>>> reloadSkill(@PathVariable String name) {
        skillService.reload();
        return Mono.just(ResponseEntity.ok(Map.of("status", "reloaded")));
    }

    @GetMapping("/{name}/mcp-status")
    public Mono<ResponseEntity<Map<String, Object>>> getMcpStatus(@PathVariable String name) {
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
        dto.setResolvedVariables(skill.getResolvedVariables());
        return dto;
    }
}
