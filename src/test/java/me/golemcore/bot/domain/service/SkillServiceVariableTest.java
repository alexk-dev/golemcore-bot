package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillServiceVariableTest {

    private SkillService skillService;
    private StoragePort mockStorage;

    @BeforeEach
    void setUp() {
        mockStorage = mock(StoragePort.class);
        BotProperties properties = new BotProperties();
        SkillVariableResolver variableResolver = new SkillVariableResolver(mockStorage);
        skillService = new SkillService(mockStorage, properties, variableResolver);

        // Default: no files
        when(mockStorage.getText(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mockStorage.listObjects(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
    }

    @Test
    void parseSkill_withVarsAndResolvedValues() {
        String skillMd = """
                ---
                name: test-skill
                description: "A test skill"
                vars:
                  ENDPOINT:
                    description: "API endpoint"
                    default: "https://default.api.com"
                  TIMEOUT:
                    default: "30"
                ---
                Connect to {{ENDPOINT}} with timeout {{TIMEOUT}}s.
                """;

        when(mockStorage.getText("skills", "test-skill/SKILL.md"))
                .thenReturn(CompletableFuture.completedFuture(skillMd));
        when(mockStorage.listObjects("skills", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("test-skill/SKILL.md")));

        skillService.reload();

        Skill skill = skillService.findByName("test-skill").orElse(null);
        assertNotNull(skill);
        assertTrue(skill.isAvailable());
        assertEquals(2, skill.getVariableDefinitions().size());
        assertEquals("https://default.api.com", skill.getResolvedVariables().get("ENDPOINT"));
        assertEquals("30", skill.getResolvedVariables().get("TIMEOUT"));
    }

    @Test
    void parseSkill_missingRequiredVarMakesUnavailable() {
        String skillMd = """
                ---
                name: needs-key
                description: "Needs API key"
                vars:
                  API_KEY:
                    description: "Required key"
                    required: true
                    secret: true
                ---
                Use key to connect.
                """;

        when(mockStorage.getText("skills", "needs-key/SKILL.md"))
                .thenReturn(CompletableFuture.completedFuture(skillMd));
        when(mockStorage.listObjects("skills", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("needs-key/SKILL.md")));

        skillService.reload();

        Skill skill = skillService.findByName("needs-key").orElse(null);
        assertNotNull(skill);
        assertFalse(skill.isAvailable(), "Skill should be unavailable when required var is missing");
    }

    @Test
    void parseSkill_requiredVarProvidedByVarsJson() {
        String skillMd = """
                ---
                name: has-key
                description: "Has API key"
                vars:
                  API_KEY:
                    required: true
                    secret: true
                ---
                Use key to connect.
                """;

        when(mockStorage.getText("skills", "has-key/SKILL.md"))
                .thenReturn(CompletableFuture.completedFuture(skillMd));
        when(mockStorage.getText("skills", "has-key/vars.json"))
                .thenReturn(CompletableFuture.completedFuture("{\"API_KEY\": \"sk-abc123\"}"));
        when(mockStorage.listObjects("skills", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("has-key/SKILL.md")));

        skillService.reload();

        Skill skill = skillService.findByName("has-key").orElse(null);
        assertNotNull(skill);
        assertTrue(skill.isAvailable());
        assertEquals("sk-abc123", skill.getResolvedVariables().get("API_KEY"));
    }

    @Test
    void parseSkill_noVarsSection_backwardCompatible() {
        String skillMd = """
                ---
                name: simple-skill
                description: "No vars"
                ---
                Just plain content.
                """;

        when(mockStorage.getText("skills", "simple-skill/SKILL.md"))
                .thenReturn(CompletableFuture.completedFuture(skillMd));
        when(mockStorage.listObjects("skills", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("simple-skill/SKILL.md")));

        skillService.reload();

        Skill skill = skillService.findByName("simple-skill").orElse(null);
        assertNotNull(skill);
        assertTrue(skill.isAvailable());
        assertTrue(skill.getVariableDefinitions().isEmpty());
        assertTrue(skill.getResolvedVariables().isEmpty());
        assertEquals("Just plain content.", skill.getContent());
    }
}
