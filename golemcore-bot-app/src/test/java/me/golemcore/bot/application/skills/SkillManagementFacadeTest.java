package me.golemcore.bot.application.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.skills.SkillDocumentService;
import me.golemcore.bot.domain.skills.SkillService;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SkillManagementFacadeTest {

    private SkillService skillService;
    private SkillMarketplaceService skillMarketplaceService;
    private StoragePort storagePort;
    private SkillManagementFacade facade;

    @BeforeEach
    void setUp() {
        skillService = mock(SkillService.class);
        skillMarketplaceService = mock(SkillMarketplaceService.class);
        storagePort = mock(StoragePort.class);
        facade = new SkillManagementFacade(
                skillService,
                new SkillDocumentService(),
                skillMarketplaceService,
                mock(McpPort.class),
                storagePort);
    }

    @Test
    void createSkillShouldReturnPendingFutureUntilStorageCompletes() {
        CompletableFuture<Void> writeFuture = new CompletableFuture<>();
        Skill reloaded = Skill.builder()
                .name("test-skill")
                .location(Path.of("test-skill/SKILL.md"))
                .content("Body instructions")
                .build();
        when(skillService.findByName("test-skill"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(reloaded));
        when(storagePort.putText(eq("skills"), eq("test-skill/SKILL.md"), anyString())).thenReturn(writeFuture);

        CompletableFuture<Skill> result = facade.createSkill(
                "test-skill",
                "---\ndescription: Created from prompt\n---\nBody instructions");

        assertFalse(result.isDone());
        verify(skillService, never()).reload();

        writeFuture.complete(null);

        assertEquals("test-skill", result.join().getName());
        verify(skillService).reload();
    }

    @Test
    void updateSkillShouldPersistUsingResolvedManagedPath() {
        CompletableFuture<Void> writeFuture = new CompletableFuture<>();
        Skill current = Skill.builder()
                .name("test-skill")
                .location(Path.of("managed/test-skill/SKILL.md"))
                .metadata(Map.of("name", "test-skill"))
                .content("old")
                .build();
        Skill updated = Skill.builder()
                .name("test-skill")
                .location(Path.of("managed/test-skill/SKILL.md"))
                .content("new")
                .metadata(Map.of("name", "test-skill", "model_tier", "smart"))
                .build();
        when(skillService.findByName("test-skill")).thenReturn(Optional.of(current));
        when(skillMarketplaceService.resolveManagedSkillStoragePath(current)).thenReturn("managed/test-skill/SKILL.md");
        when(skillService.findByLocation("managed/test-skill/SKILL.md")).thenReturn(Optional.of(updated));
        when(storagePort.putText(eq("skills"), eq("managed/test-skill/SKILL.md"), anyString()))
                .thenReturn(writeFuture);

        CompletableFuture<Skill> result = facade.updateSkill(
                "test-skill",
                Map.of("content", "---\nmodel_tier: smart\n---\nnew"));

        assertFalse(result.isDone());
        writeFuture.complete(null);

        assertEquals("test-skill", result.join().getName());
        verify(skillService).reload();
    }

    @Test
    void createSkillShouldRejectInvalidNameBeforeSchedulingWrite() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> facade.createSkill("BadName", "body"));

        assertEquals("Skill name is required and must match [a-z0-9][a-z0-9-]*", exception.getMessage());
        verify(storagePort, never()).putText(anyString(), anyString(), anyString());
    }

    @Test
    void createSkillShouldFailWhenReloadLookupMissesPersistedFile() {
        when(skillService.findByName("test-skill"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(storagePort.putText(eq("skills"), eq("test-skill/SKILL.md"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> facade.createSkill("test-skill", "body").join());

        assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertEquals(
                "Skill persisted but could not be reloaded: test-skill/SKILL.md",
                exception.getCause().getMessage());
    }
}
