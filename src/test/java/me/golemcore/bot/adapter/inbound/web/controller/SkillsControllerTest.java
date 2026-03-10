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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
                .sourceDirectory("https://github.com/alexk-dev/golemcore-skills")
                .items(List.of(SkillMarketplaceItem.builder()
                        .id("code-reviewer")
                        .name("code-reviewer")
                        .description("Review code")
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
                    assertEquals("code-reviewer", body.getItems().getFirst().getId());
                })
                .verifyComplete();
    }

    @Test
    void shouldInstallSkillFromMarketplace() {
        SkillInstallResult result = new SkillInstallResult(
                "installed",
                "Skill 'code-reviewer' installed from marketplace.",
                SkillMarketplaceItem.builder().id("code-reviewer").name("code-reviewer").installed(true).build());
        when(skillMarketplaceService.install("code-reviewer")).thenReturn(result);

        StepVerifier.create(controller.installSkill(new SkillInstallRequest("code-reviewer")))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SkillInstallResult body = response.getBody();
                    assertNotNull(body);
                    assertEquals("installed", body.getStatus());
                    assertEquals("code-reviewer", body.getSkill().getId());
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
                .build();
        when(skillService.findByName("test-skill")).thenReturn(Optional.of(skill));

        StepVerifier.create(controller.getSkill("test-skill"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SkillDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals("test-skill", body.getName());
                    assertEquals("# Test Skill\nContent here", body.getContent());
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
    void shouldReloadSkill() {
        StepVerifier.create(controller.reloadSkill("test"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("reloaded", response.getBody().get("status"));
                })
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
