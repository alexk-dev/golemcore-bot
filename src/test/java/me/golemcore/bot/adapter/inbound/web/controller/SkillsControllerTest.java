package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.SkillDto;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillInstallRequest;
import me.golemcore.bot.domain.model.SkillInstallResult;
import me.golemcore.bot.domain.model.SkillMarketplaceCatalog;
import me.golemcore.bot.domain.model.SkillMarketplaceItem;
import me.golemcore.bot.domain.service.SkillMarketplaceService;
import me.golemcore.bot.domain.service.SkillService;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillsControllerTest {

    private SkillService skillService;
    private SkillMarketplaceService skillMarketplaceService;
    private McpPort mcpPort;
    private StoragePort storagePort;
    private SkillsController controller;

    @BeforeEach
    void setUp() {
        skillService = mock(SkillService.class);
        skillMarketplaceService = mock(SkillMarketplaceService.class);
        mcpPort = mock(McpPort.class);
        storagePort = mock(StoragePort.class);
        controller = new SkillsController(skillService, skillMarketplaceService, mcpPort, storagePort);
    }

    @Test
    void shouldListSkills() {
        Skill skill = Skill.builder()
                .name("test-skill")
                .description("A test skill")
                .available(true)
                .modelTier("standard")
                .build();
        when(skillService.getAllSkills()).thenReturn(List.of(skill));

        StepVerifier.create(controller.listSkills())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<SkillDto> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(1, body.size());
                    assertEquals("test-skill", body.get(0).getName());
                })
                .verifyComplete();
    }

    @Test
    void shouldGetMarketplaceCatalog() {
        SkillMarketplaceCatalog catalog = SkillMarketplaceCatalog.builder()
                .available(true)
                .sourceType("repository")
                .sourceDirectory("https://github.com/alexk-dev/golemcore-skills")
                .items(List.of(SkillMarketplaceItem.builder()
                        .id("golemcore/code-reviewer")
                        .artifactId("code-reviewer")
                        .artifactType("skill")
                        .maintainer("golemcore")
                        .name("Code Reviewer")
                        .description("Review code")
                        .skillCount(1)
                        .skillRefs(List.of("golemcore/code-reviewer"))
                        .installed(false)
                        .build()))
                .build();
        when(skillMarketplaceService.getCatalog()).thenReturn(catalog);

        StepVerifier.create(controller.getMarketplace())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SkillMarketplaceCatalog body = response.getBody();
                    assertNotNull(body);
                    assertTrue(body.isAvailable());
                    assertEquals(1, body.getItems().size());
                    assertEquals("golemcore/code-reviewer", body.getItems().getFirst().getId());
                })
                .verifyComplete();
    }

    @Test
    void shouldInstallSkillFromMarketplace() {
        SkillInstallResult result = new SkillInstallResult(
                "installed",
                "Skill artifact 'golemcore/code-reviewer' installed from marketplace.",
                SkillMarketplaceItem.builder()
                        .id("golemcore/code-reviewer")
                        .artifactId("code-reviewer")
                        .artifactType("skill")
                        .skillCount(1)
                        .skillRefs(List.of("golemcore/code-reviewer"))
                        .name("Code Reviewer")
                        .installed(true)
                        .build());
        when(skillMarketplaceService.install("golemcore/code-reviewer")).thenReturn(result);

        StepVerifier.create(controller.installSkill(new SkillInstallRequest("golemcore/code-reviewer")))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SkillInstallResult body = response.getBody();
                    assertNotNull(body);
                    assertEquals("installed", body.getStatus());
                    assertEquals("golemcore/code-reviewer", body.getSkill().getId());
                })
                .verifyComplete();
    }

    @Test
    void shouldGetSkillByName() {
        Skill skill = Skill.builder()
                .name("test-skill")
                .description("A test skill")
                .content("# Test Skill\nContent here")
                .available(true)
                .metadata(Map.of(
                        "name", "test-skill",
                        "description", "A test skill",
                        "model_tier", "coding"))
                .build();
        when(skillService.findByName("test-skill")).thenReturn(Optional.of(skill));

        StepVerifier.create(controller.getSkill("test-skill"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SkillDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals("test-skill", body.getName());
                    assertEquals("# Test Skill\nContent here", body.getContent());
                    assertEquals("coding", body.getMetadata().get("model_tier"));
                })
                .verifyComplete();
    }

    @Test
    void shouldGetSkillByQueryForNamespacedSkill() {
        Skill skill = Skill.builder()
                .name("golemcore/devops-pack/deploy-review")
                .description("Deploy review")
                .content("# Deploy review\nContent")
                .available(true)
                .build();
        when(skillService.findByName("golemcore/devops-pack/deploy-review")).thenReturn(Optional.of(skill));

        StepVerifier.create(controller.getSkillByQuery("golemcore/devops-pack/deploy-review"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SkillDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals("golemcore/devops-pack/deploy-review", body.getName());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturn404ForMissingSkill() {
        when(skillService.findByName("unknown")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSkill("unknown"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Skill 'unknown' not found", ex.getReason());
    }

    @Test
    void shouldRejectUpdateWithNoContent() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSkill("test", Map.of()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Skill content is required", ex.getReason());
    }

    @Test
    void shouldRejectUpdateWithInvalidMetadataName() {
        when(skillService.findByName("test")).thenReturn(Optional.of(Skill.builder().name("test").build()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSkill("test", Map.of(
                        "content", "body",
                        "metadata", Map.of("name", "BadName"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Skill metadata name must match [a-z0-9][a-z0-9-]*(/[a-z0-9][a-z0-9-]*)*", ex.getReason());
    }

    @Test
    void shouldRejectCreateWithUppercaseName() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.createSkill(Map.of("name", "MySkill", "content", "body")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Skill name is required and must match [a-z0-9][a-z0-9-]*", ex.getReason());
    }

    @Test
    void shouldUpdateNamespacedSkillByQueryUsingStoredLocation() {
        Skill skill = Skill.builder()
                .name("golemcore/devops-pack/deploy-review")
                .location(java.nio.file.Path.of("marketplace/golemcore/devops-pack/skills/deploy-review/SKILL.md"))
                .metadata(Map.of(
                        "name", "golemcore/devops-pack/deploy-review",
                        "description", "Deploy review",
                        "model_tier", "coding"))
                .content("old")
                .build();
        Skill renamedSkill = Skill.builder()
                .name("golemcore/devops-pack/deploy-review-v2")
                .location(java.nio.file.Path.of("marketplace/golemcore/devops-pack/skills/deploy-review/SKILL.md"))
                .metadata(Map.of(
                        "name", "golemcore/devops-pack/deploy-review-v2",
                        "description", "Deploy review v2",
                        "model_tier", "smart"))
                .content("new")
                .build();
        when(skillService.findByName("golemcore/devops-pack/deploy-review"))
                .thenReturn(Optional.of(skill));
        when(skillService.findByLocation("marketplace/golemcore/devops-pack/skills/deploy-review/SKILL.md"))
                .thenReturn(Optional.of(renamedSkill));
        when(skillMarketplaceService.resolveManagedSkillStoragePath(skill))
                .thenReturn("marketplace/golemcore/devops-pack/skills/deploy-review/SKILL.md");
        when(skillService.renderSkillDocument(anyMap(), eq("new"))).thenReturn("""
                ---
                name: golemcore/devops-pack/deploy-review-v2
                description: Deploy review v2
                model_tier: smart
                ---
                new""");
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        StepVerifier
                .create(controller.updateSkillByQuery("golemcore/devops-pack/deploy-review", Map.of(
                        "content", "new",
                        "metadata", Map.of(
                                "name", "golemcore/devops-pack/deploy-review-v2",
                                "description", "Deploy review v2",
                                "model_tier", "smart"))))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("golemcore/devops-pack/deploy-review-v2", response.getBody().getName());
                })
                .verifyComplete();

        ArgumentCaptor<String> documentCaptor = ArgumentCaptor.forClass(String.class);
        verify(storagePort).putText(eq("skills"), eq("marketplace/golemcore/devops-pack/skills/deploy-review/SKILL.md"),
                documentCaptor.capture());
        assertTrue(documentCaptor.getValue().contains("name: golemcore/devops-pack/deploy-review-v2"));
        assertTrue(documentCaptor.getValue().contains("model_tier: smart"));
        assertTrue(documentCaptor.getValue().contains("\nnew"));
    }

    @Test
    void shouldPreserveExistingMetadataWhenUpdatePayloadOmitsMetadata() {
        Skill skill = Skill.builder()
                .name("test-skill")
                .location(java.nio.file.Path.of("test-skill/SKILL.md"))
                .metadata(Map.of(
                        "description", "Keep me",
                        "model_tier", "coding",
                        "next_skill", "follow-up"))
                .content("old body")
                .build();
        when(skillService.findByName("test-skill")).thenReturn(Optional.of(skill));
        when(skillService.findByLocation("test-skill/SKILL.md")).thenReturn(Optional.of(skill));
        when(skillMarketplaceService.resolveManagedSkillStoragePath(skill)).thenReturn("test-skill/SKILL.md");
        when(skillService.renderSkillDocument(anyMap(), eq("updated body"))).thenReturn("""
                ---
                description: Keep me
                model_tier: coding
                next_skill: follow-up
                ---
                updated body""");
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        StepVerifier.create(controller.updateSkillByQuery("test-skill", Map.of("content", "updated body")))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<String> documentCaptor = ArgumentCaptor.forClass(String.class);
        verify(storagePort).putText(eq("skills"), eq("test-skill/SKILL.md"), documentCaptor.capture());
        assertTrue(documentCaptor.getValue().contains("description: Keep me"));
        assertTrue(documentCaptor.getValue().contains("model_tier: coding"));
        assertTrue(documentCaptor.getValue().contains("next_skill: follow-up"));
        assertTrue(documentCaptor.getValue().contains("\nupdated body"));
    }

    @Test
    void shouldDeleteNamespacedSkillByQueryUsingStoredLocation() {
        Skill skill = Skill.builder()
                .name("golemcore/devops-pack/deploy-review")
                .location(java.nio.file.Path.of("marketplace/golemcore/devops-pack/skills/deploy-review/SKILL.md"))
                .build();
        when(skillService.findByName("golemcore/devops-pack/deploy-review")).thenReturn(Optional.of(skill));

        StepVerifier.create(controller.deleteSkillByQuery("golemcore/devops-pack/deploy-review"))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode()))
                .verifyComplete();

        verify(skillMarketplaceService).deleteManagedSkill(skill);
    }

    @Test
    void shouldReloadSkill() {
        when(skillService.findByName("test")).thenReturn(Optional.of(Skill.builder().name("test").build()));

        StepVerifier.create(controller.reloadSkill("test"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("reloaded", response.getBody().get("status"));
                })
                .verifyComplete();
    }

    @Test
    void shouldReloadNamespacedSkillByQuery() {
        when(skillService.findByName("golemcore/devops-pack/deploy-review"))
                .thenReturn(Optional.of(Skill.builder().name("golemcore/devops-pack/deploy-review").build()));

        StepVerifier.create(controller.reloadSkillByQuery("golemcore/devops-pack/deploy-review"))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldReturnMcpStatusForNonMcpSkill() {
        Skill skill = Skill.builder().name("test").build();
        when(skillService.findByName("test")).thenReturn(Optional.of(skill));

        StepVerifier.create(controller.getMcpStatus("test"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(false, response.getBody().get("hasMcp"));
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnMcpStatusForMissingSkill() {
        when(skillService.findByName("unknown")).thenReturn(Optional.empty());

        StepVerifier.create(controller.getMcpStatus("unknown"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(false, response.getBody().get("hasMcp"));
                })
                .verifyComplete();
    }

}
