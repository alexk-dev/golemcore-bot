package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicSkillFactoryTest {

    private DynamicSkillFactory factory;

    @BeforeEach
    void setUp() {
        factory = new DynamicSkillFactory();
    }

    @Test
    void shouldMaterializeCatalogEntryIntoSkill() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .description("GitHub API via MCP")
                .command("npx -y @modelcontextprotocol/server-github")
                .env(Map.of("GITHUB_TOKEN", "test-token"))
                .startupTimeoutSeconds(30)
                .idleTimeoutMinutes(5)
                .enabled(true)
                .build();

        Skill skill = factory.materialize(entry);

        assertEquals("mcp-github", skill.getName());
        assertEquals("GitHub API via MCP", skill.getDescription());
        assertTrue(skill.isAvailable());
        assertTrue(skill.hasMcp());
        assertEquals("npx -y @modelcontextprotocol/server-github", skill.getMcpConfig().getCommand());
        assertEquals("test-token", skill.getMcpConfig().getEnv().get("GITHUB_TOKEN"));
        assertEquals(30, skill.getMcpConfig().getStartupTimeoutSeconds());
        assertEquals(5, skill.getMcpConfig().getIdleTimeoutMinutes());
    }

    @Test
    void shouldGenerateDescriptionWhenMissing() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("postgres")
                .command("npx -y @mcp/server-postgres")
                .build();

        Skill skill = factory.materialize(entry);

        assertEquals("mcp-postgres", skill.getName());
        assertEquals("MCP server: postgres", skill.getDescription());
    }

    @Test
    void shouldMaterializeAllEnabledEntries() {
        List<RuntimeConfig.McpCatalogEntry> catalog = List.of(
                RuntimeConfig.McpCatalogEntry.builder()
                        .name("github")
                        .command("npx github")
                        .enabled(true)
                        .build(),
                RuntimeConfig.McpCatalogEntry.builder()
                        .name("slack")
                        .command("npx slack")
                        .enabled(false)
                        .build(),
                RuntimeConfig.McpCatalogEntry.builder()
                        .name("postgres")
                        .command("npx postgres")
                        .enabled(true)
                        .build());

        List<Skill> skills = factory.materializeAll(catalog);

        assertEquals(2, skills.size());
        assertEquals("mcp-github", skills.get(0).getName());
        assertEquals("mcp-postgres", skills.get(1).getName());
    }

    @Test
    void shouldReturnEmptyListForNullCatalog() {
        List<Skill> skills = factory.materializeAll(null);
        assertTrue(skills.isEmpty());
    }

    @Test
    void shouldSkipEntriesWithBlankCommand() {
        List<RuntimeConfig.McpCatalogEntry> catalog = List.of(
                RuntimeConfig.McpCatalogEntry.builder()
                        .name("empty")
                        .command("")
                        .build());

        List<Skill> skills = factory.materializeAll(catalog);
        assertTrue(skills.isEmpty());
    }

    @Test
    void shouldIdentifyCatalogSkills() {
        assertTrue(factory.isCatalogSkill("mcp-github"));
        assertFalse(factory.isCatalogSkill("github-assistant"));
        assertFalse(factory.isCatalogSkill(null));
    }

    @Test
    void shouldConvertNameToSkillName() {
        assertEquals("mcp-github", factory.toSkillName("github"));
        assertEquals("mcp-brave-search", factory.toSkillName("brave-search"));
    }

    @Test
    void shouldGenerateContentWithDescription() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .description("Work with GitHub repos and issues")
                .command("npx github-mcp")
                .build();

        Skill skill = factory.materialize(entry);

        assertNotNull(skill.getContent());
        assertTrue(skill.getContent().contains("Work with GitHub repos and issues"));
        assertTrue(skill.getContent().contains("github-mcp"));
    }

    @Test
    void shouldResolveEnvPlaceholdersFromSystemEnv() {
        // PATH is always available
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("test")
                .command("npx test")
                .env(Map.of("MY_PATH", "${PATH}"))
                .build();

        Skill skill = factory.materialize(entry);

        String resolvedPath = skill.getMcpConfig().getEnv().get("MY_PATH");
        assertNotNull(resolvedPath);
        assertFalse(resolvedPath.contains("${"));
    }

    @Test
    void shouldPreservePlaceholderWhenEnvVarMissing() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("test")
                .command("npx test")
                .env(Map.of("TOKEN", "${UNLIKELY_NONEXISTENT_VAR_XYZ_123}"))
                .build();

        Skill skill = factory.materialize(entry);

        assertEquals("${UNLIKELY_NONEXISTENT_VAR_XYZ_123}", skill.getMcpConfig().getEnv().get("TOKEN"));
    }

    @Test
    void shouldHandleNullEnvMap() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("test")
                .command("npx test")
                .env(null)
                .build();

        Skill skill = factory.materialize(entry);

        assertNotNull(skill.getMcpConfig().getEnv());
        assertTrue(skill.getMcpConfig().getEnv().isEmpty());
    }

    @Test
    void shouldHandleEmptyEnvMap() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("test")
                .command("npx test")
                .env(Map.of())
                .build();

        Skill skill = factory.materialize(entry);

        assertNotNull(skill.getMcpConfig().getEnv());
        assertTrue(skill.getMcpConfig().getEnv().isEmpty());
    }

    @Test
    void shouldPassThroughPlainEnvValues() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("test")
                .command("npx test")
                .env(Map.of("KEY", "plain-value"))
                .build();

        Skill skill = factory.materialize(entry);

        assertEquals("plain-value", skill.getMcpConfig().getEnv().get("KEY"));
    }

    @Test
    void shouldSetSkillAsAvailable() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("test")
                .command("npx test")
                .build();

        Skill skill = factory.materialize(entry);

        assertTrue(skill.isAvailable());
        assertTrue(skill.hasMcp());
    }

    @Test
    void shouldUseDefaultTimeoutsWhenNull() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("test")
                .command("npx test-server")
                .startupTimeoutSeconds(null)
                .idleTimeoutMinutes(null)
                .build();

        Skill skill = factory.materialize(entry);

        assertEquals(30, skill.getMcpConfig().getStartupTimeoutSeconds());
        assertEquals(5, skill.getMcpConfig().getIdleTimeoutMinutes());
    }
}
