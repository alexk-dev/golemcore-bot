package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillServiceTest {

    private StoragePort storagePort;
    private BotProperties properties;
    private SkillVariableResolver variableResolver;
    private SkillService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        properties = new BotProperties();
        variableResolver = mock(SkillVariableResolver.class);

        when(variableResolver.parseVariableDefinitions(any())).thenReturn(List.of());
        when(variableResolver.resolveVariables(any(), any())).thenReturn(Map.of());
        when(variableResolver.findMissingRequired(any(), any())).thenReturn(List.of());

        when(storagePort.listObjects(eq("skills"), eq("")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        service = new SkillService(storagePort, properties, variableResolver);
    }

    private void loadSkills(String... keys) {
        List<String> keyList = List.of(keys);
        when(storagePort.listObjects("skills", ""))
                .thenReturn(CompletableFuture.completedFuture(keyList));
    }

    private void stubSkillContent(String key, String content) {
        when(storagePort.getText("skills", key))
                .thenReturn(CompletableFuture.completedFuture(content));
    }

    // ==================== reload / init ====================

    @Test
    void reloadLoadsSkillsFromStorage() {
        String skillContent = """
                ---
                name: greeting
                description: Handle greetings
                ---
                You are a friendly greeter.
                """;
        loadSkills("greeting/SKILL.md");
        stubSkillContent("greeting/SKILL.md", skillContent);

        service.reload();

        assertEquals(1, service.getAllSkills().size());
        assertEquals("greeting", service.getAllSkills().get(0).getName());
    }

    @Test
    void reloadClearsOldSkillsAndLoadsNew() {
        String skill1 = """
                ---
                name: skill1
                description: First
                ---
                Content 1
                """;
        loadSkills("skill1/SKILL.md");
        stubSkillContent("skill1/SKILL.md", skill1);
        service.reload();

        assertEquals(1, service.getAllSkills().size());

        // Reload with different skills
        String skill2 = """
                ---
                name: skill2
                description: Second
                ---
                Content 2
                """;
        loadSkills("skill2/SKILL.md");
        stubSkillContent("skill2/SKILL.md", skill2);
        service.reload();

        assertEquals(1, service.getAllSkills().size());
        assertEquals("skill2", service.getAllSkills().get(0).getName());
    }

    @Test
    void reloadHandlesStorageFailure() {
        when(storagePort.listObjects("skills", ""))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("storage error")));

        assertDoesNotThrow(() -> service.reload());
    }

    @Test
    void reloadSkipsNonSkillFiles() {
        loadSkills("readme.md", "notes.txt", "greeting/SKILL.md");
        stubSkillContent("greeting/SKILL.md", """
                ---
                name: greeting
                description: test
                ---
                Content
                """);

        service.reload();

        assertEquals(1, service.getAllSkills().size());
        verify(storagePort, never()).getText("skills", "readme.md");
    }

    @Test
    void reloadLoadsSkillMdAtRoot() {
        loadSkills("SKILL.md");
        stubSkillContent("SKILL.md", """
                ---
                name: root-skill
                description: Root level skill
                ---
                Content
                """);

        service.reload();

        assertEquals(1, service.getAllSkills().size());
    }

    @Test
    void reloadSkipsBlankContent() {
        loadSkills("empty/SKILL.md");
        stubSkillContent("empty/SKILL.md", "   ");

        service.reload();

        assertEquals(0, service.getAllSkills().size());
    }

    @Test
    void reloadSkipsNullContent() {
        loadSkills("null/SKILL.md");
        when(storagePort.getText("skills", "null/SKILL.md"))
                .thenReturn(CompletableFuture.completedFuture(null));

        service.reload();

        assertEquals(0, service.getAllSkills().size());
    }

    // ==================== parseSkill - frontmatter ====================

    @Test
    void parseSkillWithValidFrontmatter() {
        loadSkills("summarize/SKILL.md");
        stubSkillContent("summarize/SKILL.md", """
                ---
                name: summarize
                description: Summarize text
                ---
                You summarize text efficiently.
                """);

        service.reload();

        Optional<Skill> skill = service.findByName("summarize");
        assertTrue(skill.isPresent());
        assertEquals("summarize", skill.get().getName());
        assertEquals("Summarize text", skill.get().getDescription());
        assertEquals("You summarize text efficiently.", skill.get().getContent());
    }

    @Test
    void parseSkillWithoutFrontmatter() {
        loadSkills("simple/SKILL.md");
        stubSkillContent("simple/SKILL.md", "Just plain content without frontmatter.");

        service.reload();

        // Should extract name from path
        Optional<Skill> skill = service.findByName("simple");
        assertTrue(skill.isPresent());
        assertEquals("Just plain content without frontmatter.", skill.get().getContent());
    }

    @Test
    void parseSkillWithInvalidYaml() {
        loadSkills("bad/SKILL.md");
        stubSkillContent("bad/SKILL.md", """
                ---
                name: [invalid yaml here!!!
                ---
                Content body
                """);

        service.reload();

        // Should still load, but with name from path
        Optional<Skill> skill = service.findByName("bad");
        assertTrue(skill.isPresent());
    }

    @Test
    void parseSkillExtractsNameFromPath() {
        loadSkills("my-skill/SKILL.md");
        stubSkillContent("my-skill/SKILL.md", """
                ---
                description: No name in frontmatter
                ---
                Content
                """);

        service.reload();

        // Name should come from directory name
        Optional<Skill> skill = service.findByName("my-skill");
        assertTrue(skill.isPresent());
    }

    @Test
    void parseSkillFrontmatterNameOverridesPath() {
        loadSkills("dir-name/SKILL.md");
        stubSkillContent("dir-name/SKILL.md", """
                ---
                name: custom-name
                description: Custom
                ---
                Content
                """);

        service.reload();

        assertFalse(service.findByName("dir-name").isPresent());
        assertTrue(service.findByName("custom-name").isPresent());
    }

    // ==================== MCP config ====================

    @Test
    void parseSkillWithMcpConfig() {
        loadSkills("github/SKILL.md");
        stubSkillContent("github/SKILL.md", """
                ---
                name: github
                description: GitHub skill
                mcp:
                  command: npx -y @mcp/server-github
                  env:
                    GITHUB_TOKEN: token123
                  startup_timeout: 45
                  idle_timeout: 10
                ---
                GitHub integration skill.
                """);

        service.reload();

        Optional<Skill> skill = service.findByName("github");
        assertTrue(skill.isPresent());
        assertNotNull(skill.get().getMcpConfig());
        assertEquals("npx -y @mcp/server-github", skill.get().getMcpConfig().getCommand());
        assertEquals("token123", skill.get().getMcpConfig().getEnv().get("GITHUB_TOKEN"));
        assertEquals(45, skill.get().getMcpConfig().getStartupTimeoutSeconds());
        assertEquals(10, skill.get().getMcpConfig().getIdleTimeoutMinutes());
    }

    @Test
    void parseSkillMcpConfigWithBlankCommand() {
        loadSkills("nomcp/SKILL.md");
        stubSkillContent("nomcp/SKILL.md", """
                ---
                name: nomcp
                description: test
                mcp:
                  command: ""
                ---
                Content
                """);

        service.reload();

        Optional<Skill> skill = service.findByName("nomcp");
        assertTrue(skill.isPresent());
        assertNull(skill.get().getMcpConfig());
    }

    @Test
    void parseSkillMcpConfigUsesDefaults() {
        loadSkills("defaults/SKILL.md");
        stubSkillContent("defaults/SKILL.md", """
                ---
                name: defaults
                description: test
                mcp:
                  command: some-command
                ---
                Content
                """);

        service.reload();

        Optional<Skill> skill = service.findByName("defaults");
        assertTrue(skill.isPresent());
        assertNotNull(skill.get().getMcpConfig());
        assertEquals(30, skill.get().getMcpConfig().getStartupTimeoutSeconds());
        assertEquals(5, skill.get().getMcpConfig().getIdleTimeoutMinutes());
    }

    // ==================== Pipeline config ====================

    @Test
    void parseSkillWithNextSkill() {
        loadSkills("step1/SKILL.md");
        stubSkillContent("step1/SKILL.md", """
                ---
                name: step1
                description: First step
                next_skill: step2
                ---
                Do step 1
                """);

        service.reload();

        Optional<Skill> skill = service.findByName("step1");
        assertTrue(skill.isPresent());
        assertEquals("step2", skill.get().getNextSkill());
    }

    @Test
    void parseSkillWithConditionalNextSkills() {
        loadSkills("router/SKILL.md");
        stubSkillContent("router/SKILL.md", """
                ---
                name: router
                description: Route skill
                conditional_next_skills:
                  success: happy-path
                  error: error-handler
                ---
                Route based on condition
                """);

        service.reload();

        Optional<Skill> skill = service.findByName("router");
        assertTrue(skill.isPresent());
        assertEquals("happy-path", skill.get().getConditionalNextSkills().get("success"));
        assertEquals("error-handler", skill.get().getConditionalNextSkills().get("error"));
    }

    @Test
    void parseSkillConditionalNextSkillsIgnoresNullValues() {
        loadSkills("nullval/SKILL.md");
        stubSkillContent("nullval/SKILL.md", """
                ---
                name: nullval
                description: test
                conditional_next_skills:
                  key1: value1
                  key2: null
                ---
                Content
                """);

        service.reload();

        Optional<Skill> skill = service.findByName("nullval");
        assertTrue(skill.isPresent());
        // null values should not be in the map
        assertFalse(skill.get().getConditionalNextSkills().containsKey("key2"));
    }

    // ==================== Requirements ====================

    @Test
    void parseSkillWithMissingEnvRequirement() {
        loadSkills("needsenv/SKILL.md");
        stubSkillContent("needsenv/SKILL.md", """
                ---
                name: needsenv
                description: Needs env
                requires:
                  env:
                    - VERY_UNLIKELY_ENV_VAR_12345
                ---
                Content
                """);

        service.reload();

        Optional<Skill> skill = service.findByName("needsenv");
        assertTrue(skill.isPresent());
        assertFalse(skill.get().isAvailable());
    }

    @Test
    void parseSkillAvailableWithNoRequirements() {
        loadSkills("noreq/SKILL.md");
        stubSkillContent("noreq/SKILL.md", """
                ---
                name: noreq
                description: No requirements
                ---
                Content
                """);

        service.reload();

        Optional<Skill> skill = service.findByName("noreq");
        assertTrue(skill.isPresent());
        assertTrue(skill.get().isAvailable());
    }

    // ==================== Variables ====================

    @Test
    void parseSkillWithMissingRequiredVariables() {
        loadSkills("needsvar/SKILL.md");
        stubSkillContent("needsvar/SKILL.md", """
                ---
                name: needsvar
                description: test
                vars:
                  api_key:
                    source: env
                    env_var: MISSING_VAR_XYZ
                    required: true
                ---
                Content
                """);

        when(variableResolver.findMissingRequired(any(), any())).thenReturn(List.of("api_key"));

        service.reload();

        Optional<Skill> skill = service.findByName("needsvar");
        assertTrue(skill.isPresent());
        assertFalse(skill.get().isAvailable());
    }

    // ==================== getAvailableSkills / findByName / getSkillsSummary
    // ====================

    @Test
    void getAvailableSkillsFiltersUnavailable() {
        loadSkills("avail/SKILL.md", "unavail/SKILL.md");
        stubSkillContent("avail/SKILL.md", """
                ---
                name: avail
                description: Available
                ---
                Content
                """);
        stubSkillContent("unavail/SKILL.md", """
                ---
                name: unavail
                description: Unavailable
                requires:
                  env:
                    - NONEXISTENT_ENV_VAR_9876
                ---
                Content
                """);

        service.reload();

        List<Skill> available = service.getAvailableSkills();
        assertEquals(1, available.size());
        assertEquals("avail", available.get(0).getName());
    }

    @Test
    void findByNameReturnsEmptyForUnknown() {
        assertTrue(service.findByName("nonexistent").isEmpty());
    }

    @Test
    void getSkillsSummaryReturnsEmptyWhenNoSkills() {
        assertEquals("", service.getSkillsSummary());
    }

    @Test
    void getSkillContentReturnsNullForUnknown() {
        assertNull(service.getSkillContent("nonexistent"));
    }

    @Test
    void getSkillContentReturnsContent() {
        loadSkills("test/SKILL.md");
        stubSkillContent("test/SKILL.md", """
                ---
                name: test
                description: Test
                ---
                Test content here
                """);

        service.reload();

        assertEquals("Test content here", service.getSkillContent("test"));
    }

    @Test
    void getComponentTypeReturnsSkill() {
        assertEquals("skill", service.getComponentType());
    }
}
