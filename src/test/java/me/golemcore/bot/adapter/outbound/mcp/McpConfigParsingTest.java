package me.golemcore.bot.adapter.outbound.mcp;

import me.golemcore.bot.domain.model.McpConfig;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.service.SkillService;
import me.golemcore.bot.domain.service.SkillVariableResolver;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests MCP config parsing from YAML frontmatter via SkillService.
 */
class McpConfigParsingTest {

    private StoragePort storagePort;
    private SkillService skillService;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        BotProperties properties = new BotProperties();
        SkillVariableResolver variableResolver = new SkillVariableResolver(storagePort);
        skillService = new SkillService(storagePort, properties, variableResolver);

        // Mock empty listings for reload
        when(storagePort.listObjects(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
    }

    @Test
    void testParseSkillWithMcpConfig() {
        String content = """
                ---
                name: github-assistant
                description: Work with GitHub
                mcp:
                  command: npx -y @modelcontextprotocol/server-github
                  env:
                    GITHUB_PERSONAL_ACCESS_TOKEN: token123
                  startup_timeout: 60
                  idle_timeout: 10
                ---
                You are a GitHub assistant.
                """;

        when(storagePort.getText("skills", "github-assistant/SKILL.md"))
                .thenReturn(CompletableFuture.completedFuture(content));
        when(storagePort.listObjects("skills", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("github-assistant/SKILL.md")));

        skillService.reload();

        Optional<Skill> skill = skillService.findByName("github-assistant");
        assertTrue(skill.isPresent());
        assertTrue(skill.get().hasMcp());

        McpConfig mcp = skill.get().getMcpConfig();
        assertNotNull(mcp);
        assertEquals("npx -y @modelcontextprotocol/server-github", mcp.getCommand());
        assertEquals("token123", mcp.getEnv().get("GITHUB_PERSONAL_ACCESS_TOKEN"));
        assertEquals(60, mcp.getStartupTimeoutSeconds());
        assertEquals(10, mcp.getIdleTimeoutMinutes());
    }

    @Test
    void testParseSkillWithoutMcp() {
        String content = """
                ---
                name: greeting
                description: Handle greetings
                ---
                You are a friendly greeter.
                """;

        when(storagePort.getText("skills", "greeting/SKILL.md"))
                .thenReturn(CompletableFuture.completedFuture(content));
        when(storagePort.listObjects("skills", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("greeting/SKILL.md")));

        skillService.reload();

        Optional<Skill> skill = skillService.findByName("greeting");
        assertTrue(skill.isPresent());
        assertFalse(skill.get().hasMcp());
        assertNull(skill.get().getMcpConfig());
    }

    @Test
    void testParseSkillMcpWithDefaultTimeouts() {
        String content = """
                ---
                name: filesystem
                description: File operations
                mcp:
                  command: npx -y @modelcontextprotocol/server-filesystem /tmp
                ---
                File system assistant.
                """;

        when(storagePort.getText("skills", "filesystem/SKILL.md"))
                .thenReturn(CompletableFuture.completedFuture(content));
        when(storagePort.listObjects("skills", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("filesystem/SKILL.md")));

        skillService.reload();

        Optional<Skill> skill = skillService.findByName("filesystem");
        assertTrue(skill.isPresent());
        assertTrue(skill.get().hasMcp());

        McpConfig mcp = skill.get().getMcpConfig();
        assertEquals(30, mcp.getStartupTimeoutSeconds()); // default
        assertEquals(5, mcp.getIdleTimeoutMinutes()); // default
        assertTrue(mcp.getEnv().isEmpty());
    }

    @Test
    void testParseSkillMcpEnvWithVarResolution() {
        // Set env var for the test
        // Since we can't set system env vars in test, we test the placeholder pattern
        String content = """
                ---
                name: github-test
                description: GitHub with vars
                vars:
                  GITHUB_TOKEN:
                    description: GitHub token
                    required: true
                    secret: true
                mcp:
                  command: npx -y @modelcontextprotocol/server-github
                  env:
                    GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}
                ---
                GitHub assistant.
                """;

        // Mock the variable resolver to return a resolved value
        when(storagePort.getText("skills", "github-test/SKILL.md"))
                .thenReturn(CompletableFuture.completedFuture(content));
        when(storagePort.listObjects("skills", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("github-test/SKILL.md")));
        // Mock vars.json to provide GITHUB_TOKEN
        when(storagePort.getText("skills", "github-test/vars.json"))
                .thenReturn(CompletableFuture.completedFuture("{\"GITHUB_TOKEN\": \"ghp_test123\"}"));
        when(storagePort.getText("", "variables.json"))
                .thenReturn(CompletableFuture.completedFuture("{}"));

        skillService.reload();

        Optional<Skill> skill = skillService.findByName("github-test");
        assertTrue(skill.isPresent());
        assertTrue(skill.get().hasMcp());

        McpConfig mcp = skill.get().getMcpConfig();
        // The ${GITHUB_TOKEN} should be resolved from resolvedVariables
        assertEquals("ghp_test123", mcp.getEnv().get("GITHUB_PERSONAL_ACCESS_TOKEN"));
    }

    @Test
    void testParseSkillMcpEmptyCommand() {
        String content = """
                ---
                name: empty-mcp
                description: Empty MCP command
                mcp:
                  command: ""
                ---
                No command.
                """;

        when(storagePort.getText("skills", "empty-mcp/SKILL.md"))
                .thenReturn(CompletableFuture.completedFuture(content));
        when(storagePort.listObjects("skills", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("empty-mcp/SKILL.md")));

        skillService.reload();

        Optional<Skill> skill = skillService.findByName("empty-mcp");
        assertTrue(skill.isPresent());
        assertFalse(skill.get().hasMcp());
        assertNull(skill.get().getMcpConfig());
    }
}
