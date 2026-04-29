package me.golemcore.bot.domain.skills;

import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
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
    private SkillDocumentService skillDocumentService;
    private SkillService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        properties = new BotProperties();
        variableResolver = mock(SkillVariableResolver.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        skillDocumentService = new SkillDocumentService();

        when(variableResolver.parseVariableDefinitions(any())).thenReturn(List.of());
        when(variableResolver.resolveVariables(any(), any())).thenReturn(Map.of());
        when(variableResolver.findMissingRequired(any(), any())).thenReturn(List.of());

        when(storagePort.listObjects(eq(SKILLS_DIR), eq("")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        when(runtimeConfigService.isSkillsEnabled()).thenReturn(true);
        when(runtimeConfigService.isSkillsProgressiveLoadingEnabled()).thenReturn(true);

        service = new SkillService(storagePort, me.golemcore.bot.support.TestPorts.settings(properties),
                variableResolver, runtimeConfigService,
                skillDocumentService);
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

    private Optional<Skill> loadSkillAndFind(String name, String content) {
        String key = name + "/" + SKILL_FILE;
        loadSkills(key);
        stubSkillContent(key, content);
        service.reload();
        return service.findByName(name);
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
        Optional<Skill> skill = loadSkillAndFind("summarize", """
                ---
                name: summarize
                description: Summarize text
                ---
                You summarize text efficiently.
                """);
        assertTrue(skill.isPresent());
        assertEquals("summarize", skill.get().getName());
        assertEquals("Summarize text", skill.get().getDescription());
        assertEquals("You summarize text efficiently.", skill.get().getContent());
    }

    @Test
    void parseSkillWithoutFrontmatter() {
        // Should extract name from path
        Optional<Skill> skill = loadSkillAndFind("simple", "Just plain content without frontmatter.");
        assertTrue(skill.isPresent());
        assertEquals("Just plain content without frontmatter.", skill.get().getContent());
    }

    @Test
    void parseSkillWithInvalidYaml() {
        // Should still load, but with name from path
        Optional<Skill> skill = loadSkillAndFind("bad", """
                ---
                name: [invalid yaml here!!!
                ---
                Content body
                """);
        assertTrue(skill.isPresent());
    }

    @Test
    void parseSkillExtractsNameFromPath() {
        // Name should come from directory name
        Optional<Skill> skill = loadSkillAndFind("my-skill", """
                ---
                description: No name in frontmatter
                ---
                Content
                """);
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
        Optional<Skill> skill = loadSkillAndFind("github", """
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
        assertTrue(skill.isPresent());
        assertNotNull(skill.get().getMcpConfig());
        assertEquals("npx -y @mcp/server-github", skill.get().getMcpConfig().getCommand());
        assertEquals("token123", skill.get().getMcpConfig().getEnv().get("GITHUB_TOKEN"));
        assertEquals(45, skill.get().getMcpConfig().getStartupTimeoutSeconds());
        assertEquals(10, skill.get().getMcpConfig().getIdleTimeoutMinutes());
    }

    @Test
    void parseSkillMcpConfigWithBlankCommand() {
        Optional<Skill> skill = loadSkillAndFind("nomcp", """
                ---
                name: nomcp
                description: test
                mcp:
                  command: ""
                ---
                Content
                """);
        assertTrue(skill.isPresent());
        assertNull(skill.get().getMcpConfig());
    }

    @Test
    void parseSkillMcpConfigUsesDefaults() {
        Optional<Skill> skill = loadSkillAndFind("defaults", """
                ---
                name: defaults
                description: test
                mcp:
                  command: some-command
                ---
                Content
                """);
        assertTrue(skill.isPresent());
        assertNotNull(skill.get().getMcpConfig());
        assertEquals(30, skill.get().getMcpConfig().getStartupTimeoutSeconds());
        assertEquals(5, skill.get().getMcpConfig().getIdleTimeoutMinutes());
    }

    // ==================== Pipeline config ====================

    @Test
    void parseSkillWithNextSkill() {
        Optional<Skill> skill = loadSkillAndFind("step1", """
                ---
                name: step1
                description: First step
                next_skill: step2
                ---
                Do step 1
                """);
        assertTrue(skill.isPresent());
        assertEquals("step2", skill.get().getNextSkill());
    }

    @Test
    void parseSkillWithConditionalNextSkills() {
        Optional<Skill> skill = loadSkillAndFind("router", """
                ---
                name: router
                description: Route skill
                conditional_next_skills:
                  success: happy-path
                  error: error-handler
                ---
                Route based on condition
                """);
        assertTrue(skill.isPresent());
        assertEquals("happy-path", skill.get().getConditionalNextSkills().get("success"));
        assertEquals("error-handler", skill.get().getConditionalNextSkills().get("error"));
    }

    @Test
    void parseSkillConditionalNextSkillsIgnoresNullValues() {
        Optional<Skill> skill = loadSkillAndFind("nullval", """
                ---
                name: nullval
                description: test
                conditional_next_skills:
                  key1: value1
                  key2: null
                ---
                Content
                """);
        assertTrue(skill.isPresent());
        // null values should not be in the map
        assertFalse(skill.get().getConditionalNextSkills().containsKey("key2"));
    }

    // ==================== model_tier ====================

    @Test
    void parseSkillWithModelTier() {
        Optional<Skill> skill = loadSkillAndFind("coder", """
                ---
                name: coder
                description: Coding skill
                model_tier: coding
                ---
                You are a coding assistant.
                """);
        assertTrue(skill.isPresent());
        assertEquals("coding", skill.get().getModelTier());
    }

    @Test
    void parseSkillWithReflectionTier() {
        Optional<Skill> skill = loadSkillAndFind("recover", """
                ---
                name: recover
                description: Recovery skill
                reflection_tier: deep
                ---
                You are a recovery assistant.
                """);
        assertTrue(skill.isPresent());
        assertEquals("deep", skill.get().getReflectionTier());
    }

    @Test
    void parseSkillWithoutModelTierReturnsNull() {
        Optional<Skill> skill = loadSkillAndFind("plain", """
                ---
                name: plain
                description: Plain skill
                ---
                Plain content.
                """);
        assertTrue(skill.isPresent());
        assertNull(skill.get().getModelTier());
    }

    // ==================== Requirements ====================

    @Test
    void parseSkillWithMissingEnvRequirement() {
        Optional<Skill> skill = loadSkillAndFind("needsenv", """
                ---
                name: needsenv
                description: Needs env
                requires:
                  env:
                    - VERY_UNLIKELY_ENV_VAR_12345
                ---
                Content
                """);
        assertTrue(skill.isPresent());
        assertFalse(skill.get().isAvailable());
        assertNotNull(skill.get().getRequirements());
        assertEquals(List.of("VERY_UNLIKELY_ENV_VAR_12345"), skill.get().getRequirements().getEnvVars());
    }

    @Test
    void parseSkillAvailableWithNoRequirements() {
        Optional<Skill> skill = loadSkillAndFind("noreq", """
                ---
                name: noreq
                description: No requirements
                ---
                Content
                """);
        assertTrue(skill.isPresent());
        assertTrue(skill.get().isAvailable());
    }

    // ==================== Variables ====================

    @Test
    void parseSkillWithMissingRequiredVariables() {
        when(variableResolver.findMissingRequired(any(), any())).thenReturn(List.of("api_key"));

        Optional<Skill> skill = loadSkillAndFind("needsvar", """
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
        loadSkillAndFind("test", """
                ---
                name: test
                description: Test
                ---
                Test content here
                """);

        assertEquals("Test content here", service.getSkillContent("test"));
    }

    @Test
    void findByLocationReturnsLoadedSkill() {
        loadSkillAndFind("test", """
                ---
                name: test
                description: Test
                requires:
                  binary:
                    - git
                  skills:
                    - helper
                ---
                Test content here
                """);

        Optional<Skill> byLocation = service.findByLocation("test/SKILL.md");
        assertTrue(byLocation.isPresent());
        assertEquals("test", byLocation.get().getName());
        assertNotNull(byLocation.get().getRequirements());
        assertEquals(List.of("git"), byLocation.get().getRequirements().getBinaries());
        assertEquals(List.of("helper"), byLocation.get().getRequirements().getSkills());
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
        Optional<Skill> skill = loadSkillAndFind("noenv", """
                ---
                name: noenv
                description: MCP no env
                mcp:
                  command: some-server
                ---
                Content
                """);
        assertTrue(skill.isPresent());
        assertNotNull(skill.get().getMcpConfig());
        assertEquals("some-server", skill.get().getMcpConfig().getCommand());
        assertTrue(skill.get().getMcpConfig().getEnv().isEmpty());
    }

    @Test
    void parseSkillWithEmptyBody() {
        Optional<Skill> skill = loadSkillAndFind("empty-body", """
                ---
                name: empty-body
                description: Empty body skill
                ---
                """);
        assertTrue(skill.isPresent());
        assertEquals("", skill.get().getContent());
    }

    @Test
    void parseSkillMcpConfigWithNullCommand() {
        Optional<Skill> skill = loadSkillAndFind("nullcmd", """
                ---
                name: nullcmd
                description: test
                mcp:
                  startup_timeout: 10
                ---
                Content
                """);
        assertTrue(skill.isPresent());
        assertNull(skill.get().getMcpConfig());
    }

    @Test
    void parseSkillConditionalNextSkillsWithNonMapType() {
        Optional<Skill> skill = loadSkillAndFind("badcns", """
                ---
                name: badcns
                description: test
                conditional_next_skills: not-a-map
                ---
                Content
                """);
        assertTrue(skill.isPresent());
        assertTrue(skill.get().getConditionalNextSkills().isEmpty());
    }

    // ==================== Dynamic Skill Registration ====================

    @Test
    void registerDynamicSkillShouldSucceedForNewSkill() {
        Skill skill = Skill.builder()
                .name("mcp-github")
                .description("GitHub MCP")
                .available(true)
                .build();

        boolean result = service.registerDynamicSkill(skill);

        assertTrue(result);
        Optional<Skill> found = service.findByName("mcp-github");
        assertTrue(found.isPresent());
        assertEquals("GitHub MCP", found.get().getDescription());
    }

    @Test
    void registerDynamicSkillShouldNotOverrideExisting() {
        Skill first = Skill.builder()
                .name("mcp-github")
                .description("First")
                .available(true)
                .build();
        Skill second = Skill.builder()
                .name("mcp-github")
                .description("Second")
                .available(true)
                .build();

        assertTrue(service.registerDynamicSkill(first));
        assertFalse(service.registerDynamicSkill(second));

        Optional<Skill> found = service.findByName("mcp-github");
        assertTrue(found.isPresent());
        assertEquals("First", found.get().getDescription());
    }

    @Test
    void registerDynamicSkillShouldReturnFalseForNullSkill() {
        assertFalse(service.registerDynamicSkill(null));
    }

    @Test
    void registerDynamicSkillShouldReturnFalseForNullName() {
        Skill skill = Skill.builder().name(null).build();
        assertFalse(service.registerDynamicSkill(skill));
    }

    @Test
    void registerDynamicSkillShouldAppearInAvailableSkills() {
        Skill skill = Skill.builder()
                .name("mcp-slack")
                .description("Slack MCP")
                .available(true)
                .build();

        service.registerDynamicSkill(skill);

        List<Skill> available = service.getAvailableSkills();
        assertTrue(available.stream().anyMatch(s -> "mcp-slack".equals(s.getName())));
    }

    @Test
    void registerDynamicSkillUnavailableShouldNotAppearInAvailable() {
        Skill skill = Skill.builder()
                .name("mcp-broken")
                .description("Broken MCP")
                .available(false)
                .build();

        service.registerDynamicSkill(skill);

        List<Skill> available = service.getAvailableSkills();
        assertFalse(available.stream().anyMatch(s -> "mcp-broken".equals(s.getName())));
        // But should appear in all skills
        List<Skill> all = service.getAllSkills();
        assertTrue(all.stream().anyMatch(s -> "mcp-broken".equals(s.getName())));
    }

    @Test
    void getSkillsSummaryIncludesAvailableSkills() {
        loadSkillAndFind("s1", """
                ---
                name: s1
                description: First skill
                ---
                Content
                """);

        String summary = service.getSkillsSummary();
        assertFalse(summary.isEmpty());
        assertTrue(summary.contains("s1"));
    }
}
