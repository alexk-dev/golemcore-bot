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

    private static final String SKILLS_DIR = "skills";
    private static final String SKILL_FILE = "SKILL.md";
    private static final String GREETING_PREFIX = "greeting/";
    private static final String BAD_PREFIX = "bad/";

    private StoragePort storagePort;
    private BotProperties properties;
    private SkillVariableResolver variableResolver;
    private RuntimeConfigService runtimeConfigService;
    private SkillService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        properties = new BotProperties();
        variableResolver = mock(SkillVariableResolver.class);
        runtimeConfigService = mock(RuntimeConfigService.class);

        when(variableResolver.parseVariableDefinitions(any())).thenReturn(List.of());
        when(variableResolver.resolveVariables(any(), any())).thenReturn(Map.of());
        when(variableResolver.findMissingRequired(any(), any())).thenReturn(List.of());

        when(storagePort.listObjects(eq(SKILLS_DIR), eq("")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        when(runtimeConfigService.isSkillsEnabled()).thenReturn(true);
        when(runtimeConfigService.isSkillsProgressiveLoadingEnabled()).thenReturn(true);

        service = new SkillService(storagePort, properties, variableResolver, runtimeConfigService);
    }

    private void loadSkills(String... keys) {
        List<String> keyList = List.of(keys);
        when(storagePort.listObjects(SKILLS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(keyList));
    }

    private void stubSkillContent(String key, String content) {
        when(storagePort.getText(SKILLS_DIR, key))
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
        loadSkills(GREETING_PREFIX + SKILL_FILE);
        stubSkillContent(GREETING_PREFIX + SKILL_FILE, skillContent);

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
        loadSkills("skill1/" + SKILL_FILE);
        stubSkillContent("skill1/" + SKILL_FILE, skill1);
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
        loadSkills("skill2/" + SKILL_FILE);
        stubSkillContent("skill2/" + SKILL_FILE, skill2);
        service.reload();

        assertEquals(1, service.getAllSkills().size());
        assertEquals("skill2", service.getAllSkills().get(0).getName());
    }

    @Test
    void reloadHandlesStorageFailure() {
        when(storagePort.listObjects(SKILLS_DIR, ""))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("storage error")));

        assertDoesNotThrow(() -> service.reload());
    }

    @Test
    void reloadSkipsNonSkillFiles() {
        loadSkills("readme.md", "notes.txt", GREETING_PREFIX + SKILL_FILE);
        stubSkillContent(GREETING_PREFIX + SKILL_FILE, """
                ---
                name: greeting
                description: test
                ---
                Content
                """);

        service.reload();

        assertEquals(1, service.getAllSkills().size());
        verify(storagePort, never()).getText(SKILLS_DIR, "readme.md");
    }

    @Test
    void reloadLoadsSkillMdAtRoot() {
        loadSkills(SKILL_FILE);
        stubSkillContent(SKILL_FILE, """
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
        loadSkills("empty/" + SKILL_FILE);
        stubSkillContent("empty/" + SKILL_FILE, "   ");

        service.reload();

        assertEquals(0, service.getAllSkills().size());
    }

    @Test
    void reloadSkipsNullContent() {
        loadSkills("null/" + SKILL_FILE);
        when(storagePort.getText(SKILLS_DIR, "null/" + SKILL_FILE))
                .thenReturn(CompletableFuture.completedFuture(null));

        service.reload();

        assertEquals(0, service.getAllSkills().size());
    }

    // ==================== parseSkill - frontmatter ====================

    @Test
    void parseSkillWithValidFrontmatter() {
        loadSkills("summarize/" + SKILL_FILE);
        stubSkillContent("summarize/" + SKILL_FILE, """
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
        loadSkills("simple/" + SKILL_FILE);
        stubSkillContent("simple/" + SKILL_FILE, "Just plain content without frontmatter.");

        service.reload();

        // Should extract name from path
        Optional<Skill> skill = service.findByName("simple");
        assertTrue(skill.isPresent());
        assertEquals("Just plain content without frontmatter.", skill.get().getContent());
    }

    @Test
    void parseSkillWithInvalidYaml() {
        loadSkills(BAD_PREFIX + SKILL_FILE);
        stubSkillContent(BAD_PREFIX + SKILL_FILE, """
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
        loadSkills("my-skill/" + SKILL_FILE);
        stubSkillContent("my-skill/" + SKILL_FILE, """
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
        loadSkills("dir-name/" + SKILL_FILE);
        stubSkillContent("dir-name/" + SKILL_FILE, """
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
        loadSkills("github/" + SKILL_FILE);
        stubSkillContent("github/" + SKILL_FILE, """
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
        loadSkills("nomcp/" + SKILL_FILE);
        stubSkillContent("nomcp/" + SKILL_FILE, """
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
        loadSkills("defaults/" + SKILL_FILE);
        stubSkillContent("defaults/" + SKILL_FILE, """
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
        loadSkills("step1/" + SKILL_FILE);
        stubSkillContent("step1/" + SKILL_FILE, """
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
        loadSkills("router/" + SKILL_FILE);
        stubSkillContent("router/" + SKILL_FILE, """
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
        loadSkills("nullval/" + SKILL_FILE);
        stubSkillContent("nullval/" + SKILL_FILE, """
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

    // ==================== model_tier ====================

    @Test
    void parseSkillWithModelTier() {
        loadSkills("coder/" + SKILL_FILE);
        stubSkillContent("coder/" + SKILL_FILE, """
                ---
                name: coder
                description: Coding skill
                model_tier: coding
                ---
                You are a coding assistant.
                """);

        service.reload();

        Optional<Skill> skill = service.findByName("coder");
        assertTrue(skill.isPresent());
        assertEquals("coding", skill.get().getModelTier());
    }

    @Test
    void parseSkillWithoutModelTierReturnsNull() {
        loadSkills("plain/" + SKILL_FILE);
        stubSkillContent("plain/" + SKILL_FILE, """
                ---
                name: plain
                description: Plain skill
                ---
                Plain content.
                """);

        service.reload();

        Optional<Skill> skill = service.findByName("plain");
        assertTrue(skill.isPresent());
        assertNull(skill.get().getModelTier());
    }

    // ==================== Requirements ====================

    @Test
    void parseSkillWithMissingEnvRequirement() {
        loadSkills("needsenv/" + SKILL_FILE);
        stubSkillContent("needsenv/" + SKILL_FILE, """
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
        loadSkills("noreq/" + SKILL_FILE);
        stubSkillContent("noreq/" + SKILL_FILE, """
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
        loadSkills("needsvar/" + SKILL_FILE);
        stubSkillContent("needsvar/" + SKILL_FILE, """
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
        loadSkills("avail/" + SKILL_FILE, "unavail/" + SKILL_FILE);
        stubSkillContent("avail/" + SKILL_FILE, """
                ---
                name: avail
                description: Available
                ---
                Content
                """);
        stubSkillContent("unavail/" + SKILL_FILE, """
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
        loadSkills("test/" + SKILL_FILE);
        stubSkillContent("test/" + SKILL_FILE, """
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

    // ==================== Edge cases for loadSkillInto null guard
    // ====================

    @Test
    void reloadHandlesGetTextExceptionForSingleSkill() {
        String goodContent = """
                ---
                name: good
                description: Good skill
                ---
                Good content
                """;

        loadSkills(BAD_PREFIX + SKILL_FILE, "good/" + SKILL_FILE);
        when(storagePort.getText(SKILLS_DIR, BAD_PREFIX + SKILL_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("read error")));
        stubSkillContent("good/" + SKILL_FILE, goodContent);

        service.reload();

        assertEquals(1, service.getAllSkills().size());
        assertTrue(service.findByName("good").isPresent());
    }

    @Test
    void extractNameFromPathWithSingleSegment() {
        loadSkills(SKILL_FILE);
        stubSkillContent(SKILL_FILE, """
                ---
                description: Root skill
                ---
                Content
                """);

        service.reload();

        // Single-segment path "SKILL.md" -> extractNameFromPath returns "unknown"
        // because parts.length < 2
        Optional<Skill> skill = service.findByName("unknown");
        assertTrue(skill.isPresent());
        assertEquals("Root skill", skill.get().getDescription());
    }

    @Test
    void parseSkillMcpConfigWithNoEnvSection() {
        loadSkills("noenv/" + SKILL_FILE);
        stubSkillContent("noenv/" + SKILL_FILE, """
                ---
                name: noenv
                description: MCP no env
                mcp:
                  command: some-server
                ---
                Content
                """);

        service.reload();

        Optional<Skill> skill = service.findByName("noenv");
        assertTrue(skill.isPresent());
        assertNotNull(skill.get().getMcpConfig());
        assertEquals("some-server", skill.get().getMcpConfig().getCommand());
        assertTrue(skill.get().getMcpConfig().getEnv().isEmpty());
    }

    @Test
    void parseSkillWithEmptyBody() {
        loadSkills("empty-body/" + SKILL_FILE);
        stubSkillContent("empty-body/" + SKILL_FILE, """
                ---
                name: empty-body
                description: Empty body skill
                ---
                """);

        service.reload();

        Optional<Skill> skill = service.findByName("empty-body");
        assertTrue(skill.isPresent());
        assertEquals("", skill.get().getContent());
    }

    @Test
    void parseSkillMcpConfigWithNullCommand() {
        loadSkills("nullcmd/" + SKILL_FILE);
        stubSkillContent("nullcmd/" + SKILL_FILE, """
                ---
                name: nullcmd
                description: test
                mcp:
                  startup_timeout: 10
                ---
                Content
                """);

        service.reload();

        Optional<Skill> skill = service.findByName("nullcmd");
        assertTrue(skill.isPresent());
        assertNull(skill.get().getMcpConfig());
    }

    @Test
    void parseSkillConditionalNextSkillsWithNonMapType() {
        loadSkills("badcns/" + SKILL_FILE);
        stubSkillContent("badcns/" + SKILL_FILE, """
                ---
                name: badcns
                description: test
                conditional_next_skills: not-a-map
                ---
                Content
                """);

        service.reload();

        Optional<Skill> skill = service.findByName("badcns");
        assertTrue(skill.isPresent());
        assertTrue(skill.get().getConditionalNextSkills().isEmpty());
    }

    @Test
    void getSkillsSummaryIncludesAvailableSkills() {
        loadSkills("s1/" + SKILL_FILE);
        stubSkillContent("s1/" + SKILL_FILE, """
                ---
                name: s1
                description: First skill
                ---
                Content
                """);

        service.reload();

        String summary = service.getSkillsSummary();
        assertFalse(summary.isEmpty());
        assertTrue(summary.contains("s1"));
    }
}
